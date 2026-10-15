package com.yodrop.core.session

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.KeyPair
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.protocol.*
import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.ChunkPlanner
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

class ReceiverStateMachine(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private var myKeyPair: KeyPair? = null
    var sessionKeys: SessionKeys? = null
    var activeOffer: OfferMessage? = null
    private var activeConnection: Connection? = null
    private val receivedFiles = mutableMapOf<String, ByteArray>()
    val completedFilesData: Map<String, ByteArray> get() = receivedFiles

    fun startScanning() {
        _state.value = ReceiverState.Scanning
    }

    fun onOfferDiscovered(offer: OfferMessage) {
        activeOffer = offer
        _state.value = ReceiverState.OfferSeen(offer)
    }

    fun claimOffer(): ClaimMessage {
        val offer = activeOffer ?: throw IllegalStateException("No offer seen")
        val keyPair = CryptoEngine.generateX25519KeyPair()
        myKeyPair = keyPair

        val sharedSecret = CryptoEngine.computeSharedSecret(keyPair.privateKey, offer.senderPubKey)
        val keys = CryptoEngine.deriveSessionKeys(sharedSecret, offer.sessionId)
        sessionKeys = keys

        _state.value = ReceiverState.Claimed
        _state.value = ReceiverState.AcceptPrompt(offer, keys.verifyCode)

        return ClaimMessage(
            sessionId = offer.sessionId,
            receiverPubKey = keyPair.publicKey
        )
    }

    fun acceptTransfer(linkInfo: LinkInfoMessage): DecisionMessage {
        val offer = activeOffer ?: throw IllegalStateException("No active offer")
        _state.value = ReceiverState.Linking(linkInfo)
        return DecisionMessage(sessionId = offer.sessionId, accepted = true)
    }

    fun denyTransfer(reason: String = "User rejected"): DecisionMessage {
        val offer = activeOffer ?: throw IllegalStateException("No active offer")
        _state.value = ReceiverState.Cancelled(reason)
        return DecisionMessage(sessionId = offer.sessionId, accepted = false, reason = reason)
    }

    fun attachConnection(connection: Connection) {
        activeConnection = connection
        _state.value = ReceiverState.Auth
        scope.launch(Dispatchers.IO) {
            try {
                runReceiveLoop(connection)
            } catch (e: CancellationException) {
                _state.value = ReceiverState.Cancelled("Cancelled by receiver")
                connection.close()
            } catch (e: Throwable) {
                _state.value = ReceiverState.Failed("Receive failed: ${e.message}")
                connection.close()
            }
        }
    }

    private suspend fun runReceiveLoop(connection: Connection) {
        val keys = sessionKeys ?: throw IllegalStateException("No session keys")
        val offer = activeOffer ?: throw IllegalStateException("No active offer")
        val sid = offer.sessionId

        val peerAuthFrame = connection.readFrame()
        if (peerAuthFrame.type != FrameType.AUTH) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("Expected AUTH frame")))
            connection.close()
            _state.value = ReceiverState.Failed("Expected AUTH frame, got ${peerAuthFrame.type}")
            return
        }

        val peerAuthMsg = ProtocolCodec.decodePayload<AuthMessage>(peerAuthFrame)
        if (!MessageDigest.isEqual(peerAuthMsg.sessionId, sid)) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("SessionId mismatch")))
            connection.close()
            _state.value = ReceiverState.Failed("SessionId mismatch in AUTH")
            return
        }

        val expectedSenderAuthPayload = "authsender".encodeToByteArray() + sid
        val expectedSenderTag = CryptoEngine.computeHmacSha256(keys.senderToReceiverKey, expectedSenderAuthPayload)
        if (!MessageDigest.isEqual(peerAuthMsg.authTag, expectedSenderTag)) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("AUTH tag verification failed")))
            connection.close()
            _state.value = ReceiverState.Failed("AUTH tag verification failed")
            return
        }

        val myAuthPayload = "authreceiver".encodeToByteArray() + sid
        val myAuthTag = CryptoEngine.computeHmacSha256(keys.receiverToSenderKey, myAuthPayload)
        val myAuthMsg = AuthMessage(sessionId = sid, authTag = myAuthTag)
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, myAuthMsg))

        val manifestFrame = connection.readFrame()
        require(manifestFrame.type == FrameType.MANIFEST) { "Expected MANIFEST frame" }
        val manifest = ProtocolCodec.decodePayload<ManifestMessage>(manifestFrame)
        
        // T4: Enforce manifest bounds and name sanitization
        try {
            manifest.validate()
        } catch (e: Exception) {
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage("Invalid manifest: ${e.message}")))
            connection.close()
            _state.value = ReceiverState.Failed("Invalid manifest: ${e.message}")
            return
        }

        for ((fileIdx, file) in manifest.files.withIndex()) {
            val totalChunks = ChunkPlanner.calculateTotalChunks(file.size, manifest.chunkSize)
            val bitmap = ChunkBitmap(totalChunks)
            val fileBuffer = ByteArray(file.size.toInt())

            while (!bitmap.isComplete() && !connection.isClosed) {
                val missing = bitmap.getMissingChunks(batchSize = 16)
                if (missing.isEmpty()) break

                val reqMsg = ChunkReqMessage(fileId = file.id, chunkIndices = missing)
                connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CHUNK_REQ, reqMsg))

                for (expectedIdx in missing) {
                    val dataFrame = connection.readFrame()
                    require(dataFrame.type == FrameType.CHUNK_DATA) { "Expected CHUNK_DATA frame" }

                    val payload = dataFrame.payload
                    val idLen = payload[0].toInt() and 0xFF
                    val fileId = payload.decodeToString(1, 1 + idLen)
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
                    val ciphertext = payload.copyOfRange(off, payload.size)

                    val nonce = NonceUtils.buildChunkNonce(keys.nonceBase, chunkIdx, attemptCounter)
                    val aad = NonceUtils.buildAad(sid, fileId, chunkIdx)
                    val plaintext = CryptoEngine.decryptAesGcm(keys.senderToReceiverKey, nonce, ciphertext, aad)

                    val (start, _) = ChunkPlanner.getChunkByteRange(chunkIdx, file.size, manifest.chunkSize)
                    plaintext.copyInto(fileBuffer, start.toInt())
                    bitmap.markReceived(chunkIdx)

                    val fileProgress = (fileIdx + (chunkIdx + 1).toFloat() / totalChunks.toFloat()) / manifest.files.size.toFloat()
                    _state.value = ReceiverState.Receiving(fileProgress, file.name)
                }
            }

            _state.value = ReceiverState.Verifying(file.id)
            val hash = CryptoEngine.sha256(fileBuffer)
            val matches = hash.contentEquals(file.sha256)

            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.FILE_DONE, FileDoneMessage(file.id, matches)))
            if (!matches) {
                throw IllegalStateException("Checksum mismatch for file ${file.name}")
            }
            receivedFiles[file.id] = fileBuffer
        }

        val doneFrame = connection.readFrame()
        require(doneFrame.type == FrameType.DONE) { "Expected DONE frame" }
        _state.value = ReceiverState.Done
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
            _state.value = ReceiverState.Cancelled(reason)
        }
    }
}
