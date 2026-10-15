package com.yodrop.core.session

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.KeyPair
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.protocol.*
import com.yodrop.core.transfer.ChunkPlanner
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SenderStateMachine(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<SenderState>(SenderState.Idle)
    val state: StateFlow<SenderState> = _state.asStateFlow()

    private var myKeyPair: KeyPair? = null
    private var sessionKeys: SessionKeys? = null
    private var sessionId: ByteArray? = null
    private var filesToSend: List<FileMeta> = emptyList()
    private var fileDataMap: Map<String, ByteArray> = emptyMap()
    private var activeConnection: Connection? = null
    private var messageCounter = 0

    fun onGrabbed(files: List<FileMeta>, dataMap: Map<String, ByteArray>) {
        filesToSend = files
        fileDataMap = dataMap
        messageCounter = 0

        val keyPair = CryptoEngine.generateX25519KeyPair()
        myKeyPair = keyPair
        val sid = ByteArray(16) { (it * 7 + 13).toByte() }
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
        activeConnection = connection
        _state.value = SenderState.Auth
        scope.launch(Dispatchers.IO) {
            try {
                runTransferLoop(connection)
            } catch (e: CancellationException) {
                _state.value = SenderState.Cancelled("Cancelled by sender")
                connection.close()
            } catch (e: Throwable) {
                _state.value = SenderState.Failed("Transfer failed: ${e.message}")
                connection.close()
            }
        }
    }

    private suspend fun runTransferLoop(connection: Connection) {
        val keys = sessionKeys ?: throw IllegalStateException("No session keys derived")
        val sid = sessionId ?: throw IllegalStateException("No sessionId initialized")

        // 1. Send AUTH frame
        val authTag = CryptoEngine.computeHmacSha256(keys.senderToReceiverKey, "authsender".encodeToByteArray())
        val authMsg = AuthMessage(sessionId = sid, authTag = authTag)
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, authMsg))

        // 2. Read Peer AUTH
        val peerAuthFrame = connection.readFrame()
        require(peerAuthFrame.type == FrameType.AUTH) { "Expected AUTH frame" }

        // 3. Send MANIFEST
        val manifest = ManifestMessage(files = filesToSend)
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.MANIFEST, manifest))

        // 4. Handle Receiver Driven Chunk Requests
        var completedFiles = 0
        while (completedFiles < filesToSend.size && !connection.isClosed) {
            val frame = connection.readFrame()
            when (frame.type) {
                FrameType.CHUNK_REQ -> {
                    val req = ProtocolCodec.decodePayload<ChunkReqMessage>(frame)
                    val rawData = fileDataMap[req.fileId] ?: throw IllegalStateException("File not found: ${req.fileId}")
                    val fileSize = rawData.size.toLong()

                    for (chunkIdx in req.chunkIndices) {
                        val (start, end) = ChunkPlanner.getChunkByteRange(chunkIdx, fileSize)
                        val slice = rawData.copyOfRange(start.toInt(), end.toInt())

                        val nonce = NonceUtils.build12ByteNonce(keys.nonceBase, messageCounter++)
                        val ciphertext = CryptoEngine.encryptAesGcm(keys.senderToReceiverKey, nonce, slice)

                        val fileIdBytes = req.fileId.encodeToByteArray()
                        val payload = ByteArray(1 + fileIdBytes.size + 4 + ciphertext.size)
                        payload[0] = fileIdBytes.size.toByte()
                        fileIdBytes.copyInto(payload, 1)
                        var off = 1 + fileIdBytes.size
                        payload[off] = ((chunkIdx shr 24) and 0xFF).toByte()
                        payload[off + 1] = ((chunkIdx shr 16) and 0xFF).toByte()
                        payload[off + 2] = ((chunkIdx shr 8) and 0xFF).toByte()
                        payload[off + 3] = (chunkIdx and 0xFF).toByte()
                        ciphertext.copyInto(payload, off + 4)

                        connection.writeFrame(RawFrame(FrameType.CHUNK_DATA, payload))
                    }
                }
                FrameType.FILE_DONE -> {
                    val doneMsg = ProtocolCodec.decodePayload<FileDoneMessage>(frame)
                    if (doneMsg.ok) {
                        completedFiles++
                        val progress = completedFiles.toFloat() / filesToSend.size.toFloat()
                        _state.value = SenderState.Transferring(progress, doneMsg.fileId)
                    } else {
                        throw IllegalStateException("Receiver reported checksum failure on ${doneMsg.fileId}")
                    }
                }
                FrameType.CANCEL -> {
                    val cancelMsg = ProtocolCodec.decodePayload<CancelMessage>(frame)
                    _state.value = SenderState.Cancelled(cancelMsg.reason)
                    return
                }
            }
        }

        // 5. Transfer Complete
        connection.writeFrame(RawFrame(FrameType.DONE, ByteArray(0)))
        _state.value = SenderState.Done
    }

    fun cancel(reason: String = "User cancelled") {
        scope.launch {
            try {
                activeConnection?.let {
                    if (!it.isClosed) {
                        val frame = ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage(reason))
                        it.writeFrame(frame)
                        it.close()
                    }
                }
            } catch (_: Exception) {}
            _state.value = SenderState.Cancelled(reason)
        }
    }
}
