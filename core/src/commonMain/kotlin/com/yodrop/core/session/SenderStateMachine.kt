package com.yodrop.core.session

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.KeyPair
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.platform.FileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.transfer.ChunkPlanner
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.LanAddress
import com.yodrop.core.transport.TransportSelection
import com.yodrop.core.transport.TransportSelector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SenderStateMachine(
    private val scope: CoroutineScope,
    private val onCleanup: (() -> Unit)? = null,
    private val timeouts: Timeouts = Timeouts()
) {
    private val _state = MutableStateFlow<SenderState>(SenderState.Idle)
    val state: StateFlow<SenderState> = _state.asStateFlow()

    private val eventMutex = Mutex()
    private val _transitions = MutableSharedFlow<SenderState>(
        replay = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transitions = _transitions.asSharedFlow()

    init {
        _transitions.tryEmit(SenderState.Idle)
    }

    private var myKeyPair: KeyPair? = null
    private var sessionKeys: SessionKeys? = null
    private var sessionId: ByteArray? = null
    private var filesToSend: List<FileMeta> = emptyList()
    private var fileSourceMap: Map<String, FileSource> = emptyMap()
    private val activeConnections = mutableListOf<SessionConnection>()
    private val connectionsMutex = Mutex()
    private val authenticated = mutableSetOf<SessionConnection>()
    private val attemptMutex = Mutex()
    private var attemptCounter = 0
    private val workers = mutableListOf<Job>()
    private var cleanupJob: Job? = null
    private var linksClosed = CompletableDeferred<Unit>()
    private var cleanupStarted = false
    private var lifetimeJob: Job? = null
    private var reconnectJob: Job? = null
    private val completedFiles = mutableSetOf<String>()

    private var offerTimeoutJob: Job? = null
    private var acceptTimeoutJob: Job? = null
    private var linkingTimeoutJob: Job? = null

    private fun watchLifetime() {
        lifetimeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { this@SenderStateMachine.cancel("Sender scope cancelled") }
            }
        }
    }

    private fun publishLocked(next: SenderState) {
        _state.value = next
        _transitions.tryEmit(next)
    }

    private fun failLocked(reason: String) {
        if (_state.value is SenderState.Idle ||
            SessionPolicy.senderTerminal(_state.value)) return
        publishLocked(SenderState.Failed(reason))
        cleanup()
    }

    private suspend fun setState(next: SenderState): Boolean =
        eventMutex.withLock {
            val current = _state.value
            val allowed = when (next) {
                is SenderState.Transferring ->
                    current is SenderState.Auth ||
                    current is SenderState.Transferring
                SenderState.Done -> current is SenderState.Transferring
                else -> false
            }
            if (!allowed) return@withLock false
            publishLocked(next)
            true
        }

    suspend fun onGrabbed(
        files: List<FileMeta>,
        sources: Map<String, FileSource>
    ): Boolean = eventMutex.withLock {
        if (!scope.isActive ||
            !SessionPolicy.sender(_state.value, SessionEvent.GRAB)) {
            return@withLock false
        }

        ManifestMessage(files).validate()
        watchLifetime()
        publishLocked(SenderState.Grabbed)
        filesToSend = files.map { it.copy(sha256 = it.sha256.copyOf()) }
        fileSourceMap = sources.toMap()

        val keyPair = CryptoEngine.generateX25519KeyPair()
        myKeyPair = keyPair
        val sid = CryptoEngine.randomBytes(16)
        sessionId = sid

        val offer = OfferMessage(
            sid.copyOf(),
            keyPair.publicKey.copyOf(),
            files.size,
            files.sumOf { it.size },
            files.first().name
        )
        publishLocked(SenderState.Advertising(offer))

        offerTimeoutJob = scope.launch {
            delay(timeouts.offerMs)
            eventMutex.withLock {
                if (_state.value is SenderState.Advertising) {
                    failLocked("Offer expired")
                }
            }
        }
        true
    }

    suspend fun onClaimReceived(claim: ClaimMessage): Boolean =
        eventMutex.withLock {
            if (!scope.isActive ||
                !SessionPolicy.sender(_state.value, SessionEvent.CLAIM)) {
                return@withLock false
            }
            val sid = sessionId ?: return@withLock false
            if (!CryptoEngine.constantTimeEquals(sid, claim.sessionId) ||
                claim.receiverPubKey.size != 32) return@withLock false

            // Single-send: the serialized first valid CLAIM wins.
            // Group claim-window behavior belongs to P10.
            val keyPair = myKeyPair ?: return@withLock false
            val sharedSecret = try {
                CryptoEngine.computeSharedSecret(
                    keyPair.privateKey, claim.receiverPubKey
                )
            } catch (_: IllegalArgumentException) {
                return@withLock false
            }

            offerTimeoutJob?.cancel()
            publishLocked(SenderState.Claimed)
            val keys = CryptoEngine.deriveSessionKeys(sharedSecret, sid)
            sessionKeys = keys
            publishLocked(SenderState.AwaitAccept(keys.verifyCode))

            acceptTimeoutJob = scope.launch {
                delay(timeouts.acceptPromptMs)
                eventMutex.withLock {
                    if (_state.value is SenderState.AwaitAccept) {
                        failLocked("Accept prompt timed out")
                    }
                }
            }
            true
        }

    /** Query after CLAIM, before link setup. Peer capabilities come from its HELLO. */
    suspend fun selectTransport(
        senderPlatform: Platform,
        senderCapabilities: Int,
        peer: HelloMessage,
        senderLan: LanAddress? = null,
        peerIp: String? = null
    ): TransportSelection = eventMutex.withLock {
        check(_state.value is SenderState.AwaitAccept) {
            "Transport selection requires a claimed sender awaiting acceptance"
        }
        TransportSelector.selectTransport(
            senderPlatform = senderPlatform,
            peerPlatform = peer.platform,
            senderCaps = senderCapabilities,
            peerCaps = peer.capabilities,
            senderIp = senderLan?.ip,
            peerIp = peerIp,
            totalBytes = filesToSend.sumOf { it.size },
            senderPrefixLength = senderLan?.prefixLength
        )
    }

    suspend fun onDecisionReceived(
        decision: DecisionMessage,
        linkInfo: LinkInfoMessage
    ): Boolean = eventMutex.withLock {
        if (!scope.isActive ||
            !SessionPolicy.sender(_state.value, SessionEvent.DECISION)) {
            return@withLock false
        }
        val sid = sessionId ?: return@withLock false
        if (!CryptoEngine.constantTimeEquals(sid, decision.sessionId)) {
            return@withLock false
        }
        acceptTimeoutJob?.cancel()

        if (!decision.accepted) {
            failLocked("Receiver denied transfer: ${decision.reason}")
            return@withLock true
        }

        publishLocked(SenderState.Linking(linkInfo))
        linkingTimeoutJob = scope.launch {
            delay(timeouts.linkingMs)
            eventMutex.withLock {
                if (_state.value is SenderState.Linking) {
                    failLocked("Transport linking timed out")
                }
            }
        }
        true
    }

    suspend fun attachConnection(connection: Connection): Boolean =
        eventMutex.withLock {
            if (!scope.isActive ||
                !SessionPolicy.sender(_state.value, SessionEvent.ATTACH) ||
                connectionsMutex.withLock { activeConnections.size >= 4 }) {
                withContext(NonCancellable) {
                    runCatching { connection.close() }
                }
                return@withLock false
            }

            linkingTimeoutJob?.cancel()
            reconnectJob?.cancel()
            val wire = SessionConnection(connection, timeouts, consumeAcks = true)
            connectionsMutex.withLock {
                activeConnections.add(wire)
            }
            if (_state.value is SenderState.Linking ||
                _state.value is SenderState.Failed) {
                publishLocked(SenderState.Auth)
            }

            val job = scope.launch {
                try {
                    wire.run { runTransferWorker(it) }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { this@SenderStateMachine.cancel("Sender scope cancelled") }
                    throw e
                } catch (e: PeerCancelledException) {
                    cancel(e.reason, notifyPeer = false)
                } catch (e: ConnectionLostException) {
                    connectionLost(wire, e)
                } catch (e: Exception) {
                    fail("Transfer worker failed: ${e.message}")
                } finally {
                    withContext(NonCancellable) {
                        runCatching { wire.close() }
                        connectionsMutex.withLock { activeConnections.remove(wire); authenticated.remove(wire) }
                    }
                }
            }
            workers.add(job)
            true
        }
    private suspend fun runTransferWorker(connection: SessionConnection) {
        val keys = sessionKeys ?: throw IllegalStateException("No session keys derived")
        val sid = sessionId ?: throw IllegalStateException("No sessionId initialized")

        val authPayload = "authsender".encodeToByteArray() + sid
        val myAuthTag = CryptoEngine.computeHmacSha256(keys.senderToReceiverKey, authPayload)
        val myAuthMsg = AuthMessage(sessionId = sid, authTag = myAuthTag)
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, myAuthMsg))

        val peerAuthFrame = connection.readFrame()
        if (peerAuthFrame.type != FrameType.AUTH) {
            fail("Expected AUTH frame, got ${peerAuthFrame.type}")
            return
        }

        val peerAuthMsg = ProtocolCodec.decodePayload<AuthMessage>(peerAuthFrame)
        if (!CryptoEngine.constantTimeEquals(peerAuthMsg.sessionId, sid)) {
            fail("SessionId mismatch in AUTH")
            return
        }

        val expectedReceiverAuthPayload = "authreceiver".encodeToByteArray() + sid
        val expectedReceiverTag = CryptoEngine.computeHmacSha256(keys.receiverToSenderKey, expectedReceiverAuthPayload)
        if (!CryptoEngine.constantTimeEquals(peerAuthMsg.authTag, expectedReceiverTag)) {
            fail("AUTH tag verification failed")
            return
        }

        connectionsMutex.withLock { authenticated.add(connection) }
        val manifest = ManifestMessage(files = filesToSend)
        manifest.validate()
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.MANIFEST, manifest))

        if (!setState(SenderState.Transferring(
                0f, filesToSend.firstOrNull()?.name ?: ""
            ))) return

        while (currentCoroutineContext().isActive) {
            val frame = connection.readFrame()

            when (frame.type) {
                FrameType.CHUNK_REQ -> {
                    val req = ProtocolCodec.decodePayload<ChunkReqMessage>(frame)
                    sendRequestedChunks(connection, req.fileId, req.chunkIndices, manifest.chunkSize, keys, sid)
                }
                FrameType.NACK -> {
                    val nack = ProtocolCodec.decodePayload<NackMessage>(frame)
                    sendRequestedChunks(connection, nack.fileId, nack.chunkIndices, manifest.chunkSize, keys, sid)
                }
                FrameType.ACK -> {}
                FrameType.FILE_DONE -> {
                    val doneMsg = ProtocolCodec.decodePayload<FileDoneMessage>(frame)
                    require(filesToSend.any { it.id == doneMsg.fileId }) {
                        "Unknown FILE_DONE file id"
                    }
                    if (doneMsg.ok) {
                        val complete = eventMutex.withLock {
                            if (SessionPolicy.senderTerminal(_state.value)) return@withLock false
                            completedFiles.add(doneMsg.fileId)
                            val count = completedFiles.size
                            if (count == filesToSend.size) {
                                publishLocked(SenderState.Done)
                                true
                            } else {
                                publishLocked(SenderState.Transferring(
                                    count.toFloat() / filesToSend.size, doneMsg.fileId))
                                false
                            }
                        }
                        if (complete) {
                            // Keep delivery alive even if one completed socket closes early.
                            withContext(NonCancellable) {
                                val peers = connectionsMutex.withLock { authenticated.toList() }
                                coroutineScope {
                                    peers.map { peer -> launch { runCatching { peer.completeAndClose() } } }.joinAll()
                                }
                                eventMutex.withLock { cleanup() }
                            }
                            return
                        }
                    } else {
                        fail("Receiver reported checksum failure on ${doneMsg.fileId}")
                        return
                    }
                }
                FrameType.DONE -> throw IllegalArgumentException("Unexpected receiver DONE")
                else -> throw IllegalArgumentException("Unexpected sender frame ${frame.type}")
            }
        }
    }

    private suspend fun sendRequestedChunks(
        connection: Connection,
        fileId: String,
        chunkIndices: List<Int>,
        chunkSize: Int,
        keys: SessionKeys,
        sid: ByteArray
    ) {
        val meta = filesToSend.firstOrNull { it.id == fileId }
            ?: throw IllegalArgumentException("Unknown requested file id")
        ProtocolBounds.validateRequest(chunkIndices, meta.size, chunkSize)

        val source = fileSourceMap[fileId]
            ?: throw IllegalStateException("File source not found: $fileId")
        require(source.size == meta.size) { "Source size changed" }
        val fileSize = meta.size

        for (chunkIdx in chunkIndices) {
            val (start, end) = ChunkPlanner.getChunkByteRange(chunkIdx, fileSize, chunkSize)
            val length = (end - start).toInt()
            val slice = source.readChunk(start, length)
            require(slice.size == length) { "Source returned a short chunk" }

            val currentAttempt = attemptMutex.withLock {
                check(attemptCounter < Int.MAX_VALUE) {
                    "Attempt counter exhausted"
                }
                attemptCounter += 1
                attemptCounter
            }
            val nonce = NonceUtils.buildChunkNonce(keys.nonceBase, chunkIdx, currentAttempt)
            val aad = NonceUtils.buildAad(sid, fileId, chunkIdx)
            val ciphertext = CryptoEngine.encryptAesGcm(keys.senderToReceiverKey, nonce, slice, aad)

            val fileIdBytes = fileId.encodeToByteArray()
            val payload = ByteArray(1 + fileIdBytes.size + 4 + 4 + ciphertext.size)
            payload[0] = fileIdBytes.size.toByte()
            fileIdBytes.copyInto(payload, 1)
            var off = 1 + fileIdBytes.size
            payload[off] = ((chunkIdx shr 24) and 0xFF).toByte()
            payload[off + 1] = ((chunkIdx shr 16) and 0xFF).toByte()
            payload[off + 2] = ((chunkIdx shr 8) and 0xFF).toByte()
            payload[off + 3] = (chunkIdx and 0xFF).toByte()
            off += 4
            payload[off] = ((currentAttempt shr 24) and 0xFF).toByte()
            payload[off + 1] = ((currentAttempt shr 16) and 0xFF).toByte()
            payload[off + 2] = ((currentAttempt shr 8) and 0xFF).toByte()
            payload[off + 3] = (currentAttempt and 0xFF).toByte()
            off += 4
            ciphertext.copyInto(payload, off)

            connection.writeFrame(RawFrame(FrameType.CHUNK_DATA, payload))
        }
    }

    private suspend fun fail(reason: String) {
        eventMutex.withLock {
            failLocked(reason)
        }
        withContext(NonCancellable) { linksClosed.await() }
    }

    suspend fun cancel(reason: String = "User cancelled"): Boolean =
        cancel(reason, notifyPeer = true)

    private suspend fun cancel(reason: String, notifyPeer: Boolean): Boolean =
        eventMutex.withLock {
            if (!SessionPolicy.sender(_state.value, SessionEvent.CANCEL)) {
                return@withLock false
            }
            publishLocked(SenderState.Cancelled(reason))
            cleanup(if (notifyPeer) reason else null)
            true
        }

    private suspend fun connectionLost(wire: SessionConnection, failure: Throwable) {
        eventMutex.withLock {
            connectionsMutex.withLock { activeConnections.remove(wire); authenticated.remove(wire) }
            if (cleanupStarted || SessionPolicy.senderTerminal(_state.value)) return@withLock
            if (connectionsMutex.withLock { activeConnections.isNotEmpty() }) return@withLock
            publishLocked(SenderState.Failed(failure.message ?: "Connection lost", canResume = true))
            // Reconnecting uses the existing LINKING deadline, not a transfer-size limit.
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(timeouts.linkingMs)
                eventMutex.withLock {
                    if ((_state.value as? SenderState.Failed)?.canResume == true) {
                        failLocked("Reconnect deadline expired")
                    }
                }
            }
        }
    }

    suspend fun reset(): Boolean {
        val pending = eventMutex.withLock {
            if (!SessionPolicy.sender(_state.value, SessionEvent.RESET)) {
                return false
            }
            workers.toList() to cleanupJob
        }
        pending.first.forEach { it.cancelAndJoin() }
        eventMutex.withLock { cleanupJob }?.join()

        return eventMutex.withLock {
            if (!SessionPolicy.sender(_state.value, SessionEvent.RESET)) {
                return@withLock false
            }
            workers.clear()
            myKeyPair = null
            sessionKeys = null
            sessionId = null
            filesToSend = emptyList()
            fileSourceMap = emptyMap()
            cleanupJob = null
            linksClosed = CompletableDeferred()
            cleanupStarted = false
            lifetimeJob = null
            reconnectJob = null
            completedFiles.clear()
            connectionsMutex.withLock { authenticated.clear() }
            publishLocked(SenderState.Idle)
            true
        }
    }

    // Called under eventMutex; the captured job survives cancellation of scope.
    private fun cleanup(reason: String? = (_state.value as? SenderState.Failed)?.error) {
        if (cleanupStarted) return
        cleanupStarted = true
        offerTimeoutJob?.cancel()
        acceptTimeoutJob?.cancel()
        linkingTimeoutJob?.cancel()
        reconnectJob?.cancel()
        lifetimeJob?.cancel()
        val sources = fileSourceMap.values.distinct()
        val jobs = workers.toList()

        cleanupJob = scope.launch(NonCancellable) {
            try {
                val connections = connectionsMutex.withLock {
                    activeConnections.toList().also { activeConnections.clear() }
                }
                connections.forEach { runCatching { it.cancelAndClose(reason) } }
                linksClosed.complete(Unit)
                jobs.forEach { it.cancel() }
                jobs.joinAll()
                lifetimeJob?.join()
                sources.forEach { runCatching { it.close() } }
            } finally {
                linksClosed.complete(Unit)
                runCatching { onCleanup?.invoke() }
            }
        }
    }
}
