package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.platform.DiskFileSink
import com.yodrop.core.platform.DiskFileSource
import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.*
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.ChunkPlanner
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class T7PipeliningAndStreamsTest {

    private class SocketConn(private val socket: com.yodrop.core.platform.PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }

    @Test
    fun testFourParallelConnectionsAndNackHandling() {
        runBlocking(Dispatchers.IO) {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "yodrop-parallel-test-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            // 16 MiB test file (4 chunks of 4 MiB)
            val testFileSize = 16L * 1024L * 1024L
            val srcFile = File(tempDir, "parallel_source.dat")
            val raf = RandomAccessFile(srcFile, "rw")
            val pattern = ByteArray(1024 * 1024) { (it % 239).toByte() }
            for (i in 0 until 16) {
                raf.write(pattern)
            }
            raf.close()

            val digest = MessageDigest.getInstance("SHA-256")
            val checkRaf = RandomAccessFile(srcFile, "r")
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (checkRaf.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
            checkRaf.close()
            val expectedSha = digest.digest()

            val dstFile = File(tempDir, "parallel_received.dat")
            val sink = DiskFileSink("par-f1", dstFile)

            val meta = FileMeta("par-f1", "parallel_source.dat", testFileSize, "application/octet-stream", expectedSha)
            val source = DiskFileSource(meta.id, srcFile)

            val server = createPlatformServerSocket(0)
            val port = server.localPort

            val sender = SenderStateMachine(this)
            sender.onGrabbed(listOf(meta), mapOf(meta.id to source))
            val offer = (sender.state.value as SenderState.Advertising).offer

            val rxKey = CryptoEngine.generateX25519KeyPair()
            val sharedSecret = CryptoEngine.computeSharedSecret(rxKey.privateKey, offer.senderPubKey)
            val keys = CryptoEngine.deriveSessionKeys(sharedSecret, offer.sessionId)

            val claim = ClaimMessage(offer.sessionId, rxKey.publicKey)
            sender.onClaimReceived(claim)
            sender.onDecisionReceived(
                DecisionMessage(offer.sessionId, true),
                LinkInfoMessage(LinkType.LAN, "127.0.0.1", port)
            )

            val parallelConnectionsCount = 4
            val totalChunks = 4
            val sharedBitmap = ChunkBitmap(totalChunks)
            val lock = Any()

            val activeClientConns = mutableListOf<Connection>()

            val receiverJobs = (0 until parallelConnectionsCount).map { workerId ->
                async {
                    val clientSock = createPlatformSocket()
                    clientSock.connect("127.0.0.1", port)
                    val conn = SocketConn(clientSock)
                    synchronized(activeClientConns) {
                        activeClientConns.add(conn)
                    }

                    // Mutual AUTH
                    val myAuthPayload = "authreceiver".encodeToByteArray() + offer.sessionId
                    val myAuthTag = CryptoEngine.computeHmacSha256(keys.receiverToSenderKey, myAuthPayload)
                    val myAuthMsg = AuthMessage(offer.sessionId, myAuthTag)

                    conn.readFrame()
                    conn.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, myAuthMsg))

                    val manifestFrame = conn.readFrame()
                    val manifest = ProtocolCodec.decodePayload<ManifestMessage>(manifestFrame)

                    // Worker pulls chunk assigned to its workerId
                    val assignedChunk = workerId
                    val req = ChunkReqMessage(meta.id, listOf(assignedChunk))
                    conn.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CHUNK_REQ, req))

                    val dataFrame = conn.readFrame()
                    require(dataFrame.type == FrameType.CHUNK_DATA)

                    val payload = dataFrame.payload
                    val idLen = payload[0].toInt() and 0xFF
                    val fileId = payload.decodeToString(1, 1 + idLen)
                    var off = 1 + idLen
                    val chunkIdx = ((payload[off].toInt() and 0xFF) shl 24) or
                            ((payload[off + 1].toInt() and 0xFF) shl 16) or
                            ((payload[off + 2].toInt() and 0xFF) shl 8) or
                            (payload[off + 3].toInt() and 0xFF)
                    off += 4
                    val attempt = ((payload[off].toInt() and 0xFF) shl 24) or
                            ((payload[off + 1].toInt() and 0xFF) shl 16) or
                            ((payload[off + 2].toInt() and 0xFF) shl 8) or
                            (payload[off + 3].toInt() and 0xFF)
                    off += 4
                    val ciphertext = payload.copyOfRange(off, payload.size)

                    val nonce = NonceUtils.buildChunkNonce(keys.nonceBase, chunkIdx, attempt)
                    val aad = NonceUtils.buildAad(offer.sessionId, fileId, chunkIdx)
                    val plaintext = CryptoEngine.decryptAesGcm(keys.senderToReceiverKey, nonce, ciphertext, aad)

                    val (start, _) = ChunkPlanner.getChunkByteRange(chunkIdx, meta.size, manifest.chunkSize)
                    sink.writeChunk(start, plaintext)

                    synchronized(lock) {
                        sharedBitmap.markReceived(chunkIdx)
                    }

                    // Test NACK handling on worker 0: trigger re-request for chunk 0
                    if (workerId == 0) {
                        val nack = NackMessage(meta.id, listOf(0))
                        conn.writeFrame(ProtocolCodec.encodeToFrame(FrameType.NACK, nack))
                        val retransmittedFrame = conn.readFrame()
                        require(retransmittedFrame.type == FrameType.CHUNK_DATA)
                    }
                }
            }

            // Accept 4 incoming socket connections on Sender side
            for (i in 0 until parallelConnectionsCount) {
                val sock = server.accept()
                sender.attachConnection(SocketConn(sock))
            }

            receiverJobs.awaitAll()

            // Verify all chunks are marked complete across all 4 parallel workers
            assertTrue(sharedBitmap.isComplete(), "All 4 chunks must be marked complete in shared bitmap")

            // Verify file checksum
            val matches = sink.verifySha256(meta.sha256)
            sink.commit()
            sink.close()

            assertTrue(matches, "Final reassembled parallel-stream file must match SHA-256")

            // Send FILE_DONE on connection 0 to signal clean sender completion
            activeClientConns.firstOrNull()?.writeFrame(
                ProtocolCodec.encodeToFrame(FrameType.FILE_DONE, FileDoneMessage(meta.id, true))
            )

            withTimeout(3000) {
                while (sender.state.value !is SenderState.Done) {
                    delay(20)
                }
            }
            assertEquals(SenderState.Done, sender.state.value)

            activeClientConns.forEach { runCatching { it.close() } }
            server.close()
            tempDir.deleteRecursively()
        }
    }
}
