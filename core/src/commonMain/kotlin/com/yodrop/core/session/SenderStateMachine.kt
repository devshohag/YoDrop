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
    private val scope: CoroutineScope
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
    }

    fun onClaimReceived(claim: ClaimMessage) {
        val keyPair = myKeyPair ?: return
        val sid = sessionId ?: return
        val sharedSecret = CryptoEngine.computeSharedSecret(keyPair.privateKey, claim.receiverPubKey)
        val keys = CryptoEngine.deriveSessionKeys(sharedSecret, sid)
        sessionKeys = keys

        _state.value = SenderState.AwaitAccept(keys.verifyCode)
    }

    fun onDecisionReceived(decision: DecisionMessage, linkInfo: LinkInfoMessage) {
        if (!decision.accepted) {
            _state.value = SenderState.Failed("Receiver denied transfer: ${decision.reason}")
            return
        }
        _state.value = SenderState.Linking(linkInfo)
    }

    fun attachConnection(connection: Connection) {
        synchronized(activeConnections) {
            activeConnections.add(connection)
        }
        _state.value = SenderState.Auth
        scope.launch(Dispatchers.IO) {
            try {
                runTransferWorker(connection)
            } catch (e: CancellationException) {
                _state.value = SenderState.Cancelled("Cancelled by sender")
                connection.close()
            } catch (e: Throwable) {
                _state.value = SenderState.Failed("Transfer worker failed: ${e.message}")
                connection.close()
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
            connection.close()
            _state.value = SenderState.Failed("Expected AUTH frame, got ${peerAuthFrame.type}")
            return
        }

        val peerAuthMsg = ProtocolCodec.decodePayload<AuthMessage>(peerAuthFrame)
        if (!MessageDigest.isEqual(peerAuthMsg.sessionId, sid)) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("SessionId mismatch")))
            connection.close()
            _state.value = SenderState.Failed("SessionId mismatch in AUTH")
            return
        }

        val expectedReceiverAuthPayload = "authreceiver".encodeToByteArray() + sid
        val expectedReceiverTag = CryptoEngine.computeHmacSha256(keys.receiverToSenderKey, expectedReceiverAuthPayload)
        if (!MessageDigest.isEqual(peerAuthMsg.authTag, expectedReceiverTag)) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("AUTH tag verification failed")))
            connection.close()
            _state.value = SenderState.Failed("AUTH tag verification failed")
            return
        }

        val manifest = ManifestMessage(files = filesToSend)
        manifest.validate()
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.MANIFEST, manifest))

        var completedFiles = 0
        while (!connection.isClosed) {
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
                FrameType.ACK -> {
                    // Acknowledgement of chunk receipt
                }
                FrameType.PING -> {
                    connection.writeFrame(RawFrame(FrameType.PONG, ByteArray(0)))
                }
                FrameType.PONG -> {
                    // Liveness confirmed
                }
                FrameType.FILE_DONE -> {
                    val doneMsg = ProtocolCodec.decodePayload<FileDoneMessage>(frame)
                    if (doneMsg.ok) {
                        completedFiles++
                        val progress = completedFiles.toFloat() / filesToSend.size.toFloat()
                        _state.value = SenderState.Transferring(progress, doneMsg.fileId)
                        if (completedFiles >= filesToSend.size) {
                            connection.writeFrame(RawFrame(FrameType.DONE, ByteArray(0)))
                            _state.value = SenderState.Done
                            return
                        }
                    } else {
                        throw IllegalStateException("Receiver reported checksum failure on ${doneMsg.fileId}")
                    }
                }
                FrameType.DONE -> {
                    _state.value = SenderState.Done
                    return
                }
                FrameType.CANCEL -> {
                    val cancelMsg = ProtocolCodec.decodePayload<CancelMessage>(frame)
                    _state.value = SenderState.Cancelled(cancelMsg.reason)
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

    fun cancel(reason: String = "User cancelled") {
        scope.launch {
            synchronized(activeConnections) {
                activeConnections.forEach { conn ->
                    runCatching {
                        if (!conn.isClosed) {
                            runBlocking {
                                conn.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage(reason)))
                                conn.close()
                            }
                        }
                    }
                }
                activeConnections.clear()
            }
            _state.value = SenderState.Cancelled(reason)
        }
    }
}
