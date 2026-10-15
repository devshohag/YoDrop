package com.yodrop.core.session

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.KeyPair
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.platform.FileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.transfer.ChunkPlanner
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class SenderStateMachine(
    private val scope: CoroutineScope,
    private val onCleanup: (() -> Unit)? = null
) {
    private val _state = MutableStateFlow<SenderState>(SenderState.Idle)
    val state: StateFlow<SenderState> = _state.asStateFlow()

    private var myKeyPair: KeyPair? = null
    var sessionKeys: SessionKeys? = null
    var sessionId: ByteArray? = null
    private var filesToSend: List<FileMeta> = emptyList()
    private var fileSourceMap: Map<String, FileSource> = emptyMap()
    private val activeConnections = mutableListOf<Connection>()
    private val attemptCounter = AtomicInteger(0)

    private var offerTimeoutJob: Job? = null
    private var claimTimeoutJob: Job? = null
    private var linkingTimeoutJob: Job? = null

    fun onGrabbed(files: List<FileMeta>, sources: Map<String, FileSource>) {
        filesToSend = files
        fileSourceMap = sources
        attemptCounter.set(0)

        val keyPair = CryptoEngine.generateX25519KeyPair()
        myKeyPair = keyPair
        
        val sid = CryptoEngine.randomBytes(16)
        sessionId = sid

        val totalBytes = files.sumOf { it.size }
        val offer = OfferMessage(
            sessionId = sid,
            senderPubKey = keyPair.publicKey,
            fileCount = files.size,
            totalBytes = totalBytes,
            previewName = files.firstOrNull()?.name ?: "Unknown"
        )
        _state.value = SenderState.Advertising(offer)

        offerTimeoutJob?.cancel()
        offerTimeoutJob = scope.launch {
            delay(SessionTimeouts.OFFER_TIMEOUT_MS)
            if (_state.value is SenderState.Advertising) {
                fail("Offer expired after ${SessionTimeouts.OFFER_TIMEOUT_MS}ms")
            }
        }
    }

    fun onClaimReceived(claim: ClaimMessage) {
        offerTimeoutJob?.cancel()
        val keyPair = myKeyPair ?: return
        val sid = sessionId ?: return
        val sharedSecret = CryptoEngine.computeSharedSecret(keyPair.privateKey, claim.receiverPubKey)
        val keys = CryptoEngine.deriveSessionKeys(sharedSecret, sid)
        sessionKeys = keys

        _state.value = SenderState.AwaitAccept(keys.verifyCode)

        claimTimeoutJob?.cancel()
        claimTimeoutJob = scope.launch {
            delay(SessionTimeouts.CLAIM_TIMEOUT_MS)
            if (_state.value is SenderState.AwaitAccept) {
                fail("Claim decision timed out after ${SessionTimeouts.CLAIM_TIMEOUT_MS}ms")
            }
        }
    }

    fun onDecisionReceived(decision: DecisionMessage, linkInfo: LinkInfoMessage) {
        claimTimeoutJob?.cancel()
        if (!decision.accepted) {
            fail("Receiver denied transfer: ${decision.reason}")
            return
        }
        _state.value = SenderState.Linking(linkInfo)

        linkingTimeoutJob?.cancel()
        linkingTimeoutJob = scope.launch {
            delay(SessionTimeouts.LINKING_TIMEOUT_MS)
            if (_state.value is SenderState.Linking) {
                fail("Transport linking timed out after ${SessionTimeouts.LINKING_TIMEOUT_MS}ms")
            }
        }
    }

    fun attachConnection(connection: Connection) {
        linkingTimeoutJob?.cancel()
        synchronized(activeConnections) {
            activeConnections.add(connection)
        }
        _state.value = SenderState.Auth
        scope.launch {
            try {
                runTransferWorker(connection)
            } catch (e: CancellationException) {
                cancel("Cancelled by sender")
            } catch (e: Throwable) {
                if (!connection.isClosed && _state.value !is SenderState.Done) {
                    fail("Transfer worker failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun runTransferWorker(connection: Connection) {
        val keys = sessionKeys ?: throw IllegalStateException("No session keys derived")
        val sid = sessionId ?: throw IllegalStateException("No sessionId initialized")

        val authPayload = "authsender".encodeToByteArray() + sid
        val myAuthTag = CryptoEngine.computeHmacSha256(keys.senderToReceiverKey, authPayload)
        val myAuthMsg = AuthMessage(sessionId = sid, authTag = myAuthTag)
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, myAuthMsg))

        val peerAuthFrame = connection.readFrame()
        if (peerAuthFrame.type != FrameType.AUTH) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("Expected AUTH frame")))
            fail("Expected AUTH frame, got ${peerAuthFrame.type}")
            return
        }

        val peerAuthMsg = ProtocolCodec.decodePayload<AuthMessage>(peerAuthFrame)
        if (!MessageDigest.isEqual(peerAuthMsg.sessionId, sid)) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("SessionId mismatch")))
            fail("SessionId mismatch in AUTH")
            return
        }

        val expectedReceiverAuthPayload = "authreceiver".encodeToByteArray() + sid
        val expectedReceiverTag = CryptoEngine.computeHmacSha256(keys.receiverToSenderKey, expectedReceiverAuthPayload)
        if (!MessageDigest.isEqual(peerAuthMsg.authTag, expectedReceiverTag)) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("AUTH tag verification failed")))
            fail("AUTH tag verification failed")
            return
        }

        val manifest = ManifestMessage(files = filesToSend)
        manifest.validate()
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.MANIFEST, manifest))

        var completedFiles = 0
        while (!connection.isClosed && _state.value !is SenderState.Done && _state.value !is SenderState.Failed) {
            val frame = try {
                withTimeout(SessionTimeouts.SOCKET_IDLE_TIMEOUT_MS) {
                    connection.readFrame()
                }
            } catch (e: Exception) {
                // Peer closed stream normally or timeout
                break
            }

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
                FrameType.PING -> {
                    connection.writeFrame(RawFrame(FrameType.PONG, ByteArray(0)))
                }
                FrameType.PONG -> {}
                FrameType.FILE_DONE -> {
                    val doneMsg = ProtocolCodec.decodePayload<FileDoneMessage>(frame)
                    if (doneMsg.ok) {
                        completedFiles++
                        val progress = completedFiles.toFloat() / filesToSend.size.toFloat()
                        _state.value = SenderState.Transferring(progress, doneMsg.fileId)
                        if (completedFiles >= filesToSend.size) {
                            connection.writeFrame(RawFrame(FrameType.DONE, ByteArray(0)))
                            _state.value = SenderState.Done
                            cleanup()
                            return
                        }
                    } else {
                        fail("Receiver reported checksum failure on ${doneMsg.fileId}")
                        return
                    }
                }
                FrameType.DONE -> {
                    _state.value = SenderState.Done
                    cleanup()
                    return
                }
                FrameType.CANCEL -> {
                    val cancelMsg = ProtocolCodec.decodePayload<CancelMessage>(frame)
                    cancel(cancelMsg.reason)
                    return
                }
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
        val source = fileSourceMap[fileId] ?: throw IllegalStateException("File source not found: $fileId")
        val fileSize = source.size

        for (chunkIdx in chunkIndices) {
            val (start, end) = ChunkPlanner.getChunkByteRange(chunkIdx, fileSize, chunkSize)
            val length = (end - start).toInt()
            val slice = source.readChunk(start, length)

            val currentAttempt = attemptCounter.incrementAndGet()
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

    fun fail(reason: String) {
        _state.value = SenderState.Failed(reason)
        cleanup()
    }

    fun cancel(reason: String = "User cancelled") {
        _state.value = SenderState.Cancelled(reason)
        cleanup()
    }

    fun cleanup() {
        offerTimeoutJob?.cancel()
        claimTimeoutJob?.cancel()
        linkingTimeoutJob?.cancel()

        val connsToClose = synchronized(activeConnections) {
            val list = activeConnections.toList()
            activeConnections.clear()
            list
        }
        val sourcesToClose = fileSourceMap.values.toList()

        scope.launch {
            connsToClose.forEach { runCatching { it.close() } }
            sourcesToClose.forEach { runCatching { it.close() } }
        }
        onCleanup?.invoke()
    }
}
