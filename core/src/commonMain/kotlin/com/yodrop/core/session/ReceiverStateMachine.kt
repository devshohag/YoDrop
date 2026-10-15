package com.yodrop.core.session

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.KeyPair
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.platform.FileSink
import com.yodrop.core.protocol.*
import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.ChunkPlanner
import com.yodrop.core.transfer.InMemoryResumeStore
import com.yodrop.core.transfer.ResumeStore
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReceiverStateMachine(
    private val scope: CoroutineScope,
    private val resumeStore: ResumeStore = InMemoryResumeStore(),
    private val onCleanup: (() -> Unit)? = null,
    private val sinkFactory: (FileMeta) -> FileSink = { meta ->
        throw NotImplementedError("sinkFactory must be provided for receiving files")
    },
    private val timeouts: Timeouts = Timeouts()
) {
    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val eventMutex = Mutex()
    private val _transitions = MutableSharedFlow<ReceiverState>(
        replay = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transitions = _transitions.asSharedFlow()

    init {
        _transitions.tryEmit(ReceiverState.Idle)
    }

    private var myKeyPair: KeyPair? = null
    private var sessionKeys: SessionKeys? = null
    private var activeOffer: OfferMessage? = null
    private var activeConnection: Connection? = null
    private var worker: Job? = null
    private var promptJob: Job? = null
    private var cleanupJob: Job? = null
    private var claimWindowJob: Job? = null
    private var acceptTimeoutJob: Job? = null
    private var linkingTimeoutJob: Job? = null

    private fun copyOffer(offer: OfferMessage): OfferMessage = offer.copy(
        sessionId = offer.sessionId.copyOf(),
        senderPubKey = offer.senderPubKey.copyOf()
    )

    private fun publishLocked(next: ReceiverState) {
        _state.value = next
        _transitions.tryEmit(next)
    }

    private fun failLocked(reason: String) {
        if (_state.value is ReceiverState.Idle ||
            SessionPolicy.receiverTerminal(_state.value)) return
        publishLocked(ReceiverState.Failed(reason))
        cleanup()
    }

    private fun startTimer(
        durationMs: Long,
        message: String,
        stillWaiting: () -> Boolean
    ): Job = scope.launch {
        delay(durationMs)
        eventMutex.withLock {
            if (stillWaiting()) {
                failLocked(message)
            }
        }
    }

    private suspend fun setState(next: ReceiverState): Boolean =
        eventMutex.withLock {
            val current = _state.value
            val allowed = when (next) {
                is ReceiverState.Receiving ->
                    current is ReceiverState.Auth ||
                    current is ReceiverState.Receiving ||
                    current is ReceiverState.Verifying
                is ReceiverState.Verifying ->
                    current is ReceiverState.Auth ||
                    current is ReceiverState.Receiving
                ReceiverState.Done -> current is ReceiverState.Verifying
                else -> false
            }
            if (!allowed) return@withLock false
            publishLocked(next)
            true
        }

    suspend fun startScanning(): Boolean = eventMutex.withLock {
        if (!scope.isActive ||
            !SessionPolicy.receiver(_state.value, SessionEvent.SCAN)) {
            return@withLock false
        }
        publishLocked(ReceiverState.Scanning)
        true
    }

    suspend fun onOfferDiscovered(offer: OfferMessage): Boolean =
        eventMutex.withLock {
            if (!scope.isActive ||
                !SessionPolicy.receiver(_state.value, SessionEvent.OFFER)) {
                return@withLock false
            }
            if (offer.sessionId.size != 16 ||
                offer.senderPubKey.size != 32 ||
                offer.fileCount !in 1..1000 ||
                offer.totalBytes < 0) return@withLock false

            activeOffer = copyOffer(offer)
            publishLocked(ReceiverState.OfferSeen(copyOffer(offer)))
            claimWindowJob = startTimer(
                timeouts.claimWindowMs,
                "Receiver claim window expired"
            ) { _state.value is ReceiverState.OfferSeen }
            true
        }

    suspend fun claimOffer(): ClaimMessage = eventMutex.withLock {
        check(scope.isActive &&
            SessionPolicy.receiver(_state.value, SessionEvent.CLAIM)) {
            "CLAIM is not valid in the current state"
        }
        val offer = checkNotNull(activeOffer)
        claimWindowJob?.cancel()
        val keyPair = CryptoEngine.generateX25519KeyPair()
        val sharedSecret = try {
            CryptoEngine.computeSharedSecret(
                keyPair.privateKey, offer.senderPubKey
            )
        } catch (e: IllegalArgumentException) {
            failLocked("Invalid sender public key")
            throw e
        }
        val keys = CryptoEngine.deriveSessionKeys(sharedSecret, offer.sessionId)
        myKeyPair = keyPair
        sessionKeys = keys
        publishLocked(ReceiverState.Claimed)

        promptJob = scope.launch {
            yield()
            eventMutex.withLock {
                if (_state.value is ReceiverState.Claimed &&
                    activeOffer === offer) {
                    publishLocked(ReceiverState.AcceptPrompt(
                        copyOffer(offer), keys.verifyCode
                    ))
                    acceptTimeoutJob = startTimer(
                        timeouts.acceptPromptMs,
                        "Receiver accept prompt timed out"
                    ) { _state.value is ReceiverState.AcceptPrompt }
                }
            }
        }
        ClaimMessage(offer.sessionId.copyOf(), keyPair.publicKey.copyOf())
    }

    suspend fun acceptTransfer(linkInfo: LinkInfoMessage): DecisionMessage =
        eventMutex.withLock {
            check(scope.isActive &&
                SessionPolicy.receiver(_state.value, SessionEvent.ACCEPT)) {
                "ACCEPT is not valid in the current state"
            }
            val offer = checkNotNull(activeOffer)
            acceptTimeoutJob?.cancel()
            publishLocked(ReceiverState.Linking(linkInfo))
            linkingTimeoutJob = startTimer(
                timeouts.linkingMs,
                "Receiver transport linking timed out"
            ) { _state.value is ReceiverState.Linking }
            DecisionMessage(offer.sessionId.copyOf(), true)
        }

    suspend fun denyTransfer(
        reason: String = "User rejected"
    ): DecisionMessage = eventMutex.withLock {
        check(SessionPolicy.receiver(_state.value, SessionEvent.DENY)) {
            "DENY is not valid in the current state"
        }
        val offer = checkNotNull(activeOffer)
        publishLocked(ReceiverState.Cancelled(reason))
        cleanup()
        DecisionMessage(offer.sessionId.copyOf(), false, reason)
    }

    suspend fun attachConnection(connection: Connection): Boolean =
        eventMutex.withLock {
            if (!scope.isActive ||
                !SessionPolicy.receiver(_state.value, SessionEvent.ATTACH)) {
                withContext(NonCancellable) {
                    runCatching { connection.close() }
                }
                return@withLock false
            }

            linkingTimeoutJob?.cancel()
            activeConnection = connection
            publishLocked(ReceiverState.Auth)
            worker = scope.launch {
                try {
                    runReceiveLoop(connection)
                } catch (e: CancellationException) {
                    cancel("Cancelled by receiver")
                    throw e
                } catch (e: Throwable) {
                    fail("Receive failed: ${e.message}")
                }
            }
            true
        }
    private suspend fun runReceiveLoop(connection: Connection) {
        val keys = sessionKeys ?: throw IllegalStateException("No session keys")
        val offer = activeOffer ?: throw IllegalStateException("No active offer")
        val sid = offer.sessionId

        val peerAuthFrame = readProtocolFrame(connection)
        if (peerAuthFrame.type != FrameType.AUTH) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("Expected AUTH frame")))
            fail("Expected AUTH frame, got ${peerAuthFrame.type}")
            return
        }

        val peerAuthMsg = ProtocolCodec.decodePayload<AuthMessage>(peerAuthFrame)
        if (!CryptoEngine.constantTimeEquals(peerAuthMsg.sessionId, sid)) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("SessionId mismatch")))
            fail("SessionId mismatch in AUTH")
            return
        }

        val expectedSenderAuthPayload = "authsender".encodeToByteArray() + sid
        val expectedSenderTag = CryptoEngine.computeHmacSha256(keys.senderToReceiverKey, expectedSenderAuthPayload)
        if (!CryptoEngine.constantTimeEquals(peerAuthMsg.authTag, expectedSenderTag)) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("AUTH tag verification failed")))
            fail("AUTH tag verification failed")
            return
        }

        val myAuthPayload = "authreceiver".encodeToByteArray() + sid
        val myAuthTag = CryptoEngine.computeHmacSha256(keys.receiverToSenderKey, myAuthPayload)
        val myAuthMsg = AuthMessage(sessionId = sid, authTag = myAuthTag)
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, myAuthMsg))

        val manifestFrame = readProtocolFrame(connection)
        require(manifestFrame.type == FrameType.MANIFEST) { "Expected MANIFEST frame" }
        val manifest = ProtocolCodec.decodePayload<ManifestMessage>(manifestFrame)
        
        try {
            manifest.validate()
        } catch (e: Exception) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("Invalid manifest: ${e.message}")))
            fail("Invalid manifest: ${e.message}")
            return
        }

        for ((fileIdx, file) in manifest.files.withIndex()) {
            val totalChunks = ChunkPlanner.calculateTotalChunks(file.size, manifest.chunkSize)
            val bitmap = resumeStore.loadBitmap(sid, file.id, totalChunks)
            if (!setState(ReceiverState.Receiving(
                    fileIdx.toFloat() / manifest.files.size, file.name
                ))) return
            val sink = sinkFactory(file)

            try {
                while (!bitmap.isComplete() && !connection.isClosed) {
                    val missing = bitmap.getMissingChunks(batchSize = 16)
                    if (missing.isEmpty()) break

                    val reqMsg = ChunkReqMessage(fileId = file.id, chunkIndices = missing)
                    connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CHUNK_REQ, reqMsg))

                    val pending = missing.toMutableSet()
                    repeat(missing.size) {
                        val dataFrame = readProtocolFrame(connection)
                        require(dataFrame.type == FrameType.CHUNK_DATA) { "Expected CHUNK_DATA frame" }

                        val payload = dataFrame.payload
                        require(payload.size >= 26) { "Truncated chunk header" }
                        val idLen = payload[0].toInt() and 0xFF
                        require(idLen in 1..128 &&
                            payload.size >= 1 + idLen + 8 + 16) {
                            "Invalid chunk header"
                        }
                        val fileId = payload.decodeToString(
                            1, 1 + idLen, throwOnInvalidSequence = true
                        )
                        require(fileId == file.id) { "Wrong chunk file id" }
                        var off = 1 + idLen
                        val chunkIdx = ((payload[off].toInt() and 0xFF) shl 24) or
                                ((payload[off + 1].toInt() and 0xFF) shl 16) or
                                ((payload[off + 2].toInt() and 0xFF) shl 8) or
                                (payload[off + 3].toInt() and 0xFF)
                        off += 4
                        val attemptCounter = ((payload[off].toInt() and 0xFF) shl 24) or
                                ((payload[off + 1].toInt() and 0xFF) shl 16) or
                                ((payload[off + 2].toInt() and 0xFF) shl 8) or
                                (payload[off + 3].toInt() and 0xFF)
                        off += 4
                        require(pending.remove(chunkIdx)) {
                            "Unrequested or duplicate chunk index"
                        }
                        require(attemptCounter >= 0) {
                            "Invalid attempt counter"
                        }
                        val (chunkStart, chunkEnd) =
                            ChunkPlanner.getChunkByteRange(
                                chunkIdx, file.size, manifest.chunkSize
                            )
                        val expectedLength = (chunkEnd - chunkStart).toInt()
                        require(payload.size - off == expectedLength + 16) {
                            "Wrong encrypted chunk length"
                        }
                        val ciphertext = payload.copyOfRange(off, payload.size)

                        val nonce = NonceUtils.buildChunkNonce(keys.nonceBase, chunkIdx, attemptCounter)
                        val aad = NonceUtils.buildAad(sid, fileId, chunkIdx)
                        val plaintext = CryptoEngine.decryptAesGcm(keys.senderToReceiverKey, nonce, ciphertext, aad)

                        require(plaintext.size == expectedLength) {
                            "Wrong plaintext chunk length"
                        }
                        val start = chunkStart
                        sink.writeChunk(start, plaintext)
                        bitmap.markReceived(chunkIdx)

                        resumeStore.saveBitmap(sid, file.id, bitmap)

                        val fileProgress = (fileIdx + (chunkIdx + 1).toFloat() / totalChunks.toFloat()) / manifest.files.size.toFloat()
                        if (!setState(ReceiverState.Receiving(fileProgress, file.name))) return
                    }
                }

                if (!setState(ReceiverState.Verifying(file.id))) return
                val matches = sink.verifySha256(file.sha256)

                if (!matches) {
                    connection.writeFrame(
                        ProtocolCodec.encodeToFrame(
                            FrameType.FILE_DONE, FileDoneMessage(file.id, false)
                        )
                    )
                    fail("Checksum mismatch for file ${file.name}")
                    return
                }
                sink.commit()
                resumeStore.clear(sid, file.id)
                connection.writeFrame(
                    ProtocolCodec.encodeToFrame(
                        FrameType.FILE_DONE, FileDoneMessage(file.id, true)
                    )
                )
            } finally {
                sink.close()
            }
        }

        val doneFrame = readProtocolFrame(connection)
        require(doneFrame.type == FrameType.DONE) { "Expected DONE frame" }
        if (!setState(ReceiverState.Done)) return
        cleanup()
    }

    private suspend fun readProtocolFrame(connection: Connection): RawFrame {
        while (true) {
            val frame = readFrameWithIdleTimeout(
                connection, timeouts.socketIdleMs
            )
            if (frame.type != FrameType.PING) return frame

            val ping = ProtocolCodec.decodePayload<PingPongMessage>(frame)
            if (!ping.reply) {
                connection.writeFrame(
                    ProtocolCodec.encodeToFrame(
                        FrameType.PING, ping.copy(reply = true)
                    )
                )
            }
        }
    }

    private suspend fun fail(reason: String) {
        eventMutex.withLock {
            failLocked(reason)
        }
    }

    suspend fun cancel(reason: String = "User cancelled"): Boolean =
        eventMutex.withLock {
            if (!SessionPolicy.receiver(_state.value, SessionEvent.CANCEL)) {
                return@withLock false
            }
            publishLocked(ReceiverState.Cancelled(reason))
            cleanup()
            true
        }

    suspend fun reset(): Boolean {
        val pending = eventMutex.withLock {
            if (!SessionPolicy.receiver(_state.value, SessionEvent.RESET)) {
                return false
            }
            Triple(worker, promptJob, cleanupJob)
        }
        pending.first?.cancelAndJoin()
        pending.second?.cancelAndJoin()
        pending.third?.join()

        return eventMutex.withLock {
            if (!SessionPolicy.receiver(_state.value, SessionEvent.RESET)) {
                return@withLock false
            }
            worker = null
            promptJob = null
            cleanupJob = null
            activeConnection = null
            activeOffer = null
            sessionKeys = null
            myKeyPair = null
            publishLocked(ReceiverState.Idle)
            true
        }
    }

    private fun cleanup() {
        claimWindowJob?.cancel()
        acceptTimeoutJob?.cancel()
        linkingTimeoutJob?.cancel()
        promptJob?.cancel()
        val connection = activeConnection
        activeConnection = null
        cleanupJob = scope.launch(NonCancellable) {
            runCatching { connection?.close() }
        }
        onCleanup?.invoke()
    }
}