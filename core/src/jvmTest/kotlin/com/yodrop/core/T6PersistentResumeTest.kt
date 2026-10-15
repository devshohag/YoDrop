package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.DiskFileSink
import com.yodrop.core.platform.DiskFileSource
import com.yodrop.core.platform.DiskResumeStore
import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.protocol.RawFrame
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class T6PersistentResumeTest {

    private class SocketConn(private val socket: com.yodrop.core.platform.PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }

    @Test
    fun testRealResumeTransfersOnlyMissingChunksAfterDisconnect() {
        runBlocking(Dispatchers.IO) {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "yodrop-resume-test-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            // 8 MiB test file (two 4 MiB chunks)
            val testFileSize = 8L * 1024L * 1024L
            val srcFile = File(tempDir, "resume_source.dat")
            val raf = RandomAccessFile(srcFile, "rw")
            val pattern = ByteArray(1024 * 1024) { (it % 241).toByte() }
            for (i in 0 until 8) {
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

            val dstFile = File(tempDir, "resume_received.dat")
            val resumeStore = DiskResumeStore(File(tempDir, "resume_store"))

            val meta = FileMeta("res-f1", "resume_source.dat", testFileSize, "application/octet-stream", expectedSha)
            val source = DiskFileSource(meta.id, srcFile)

            // Step 1: Pre-seed resume store with chunk 0 already completed (simulating prior partial transfer)
            val totalChunks = 2
            val preBitmap = resumeStore.loadBitmap(ByteArray(16) { 1 }, meta.id, totalChunks)
            preBitmap.markReceived(0)
            resumeStore.saveBitmap(ByteArray(16) { 1 }, meta.id, preBitmap)

            // Also write chunk 0 directly to sink to simulate disk state from first session
            val sink = DiskFileSink(meta.id, dstFile)
            val chunk0Data = source.readChunk(0, 4 * 1024 * 1024)
            sink.writeChunk(0, chunk0Data)
            sink.close()

            // Step 2: Start session with pre-seeded resume store; receiver should ONLY request chunk 1
            val server = createPlatformServerSocket(0)
            val port = server.localPort

            val sender = SenderStateMachine(this)
            val receiver = ReceiverStateMachine(this, resumeStore = resumeStore, sinkFactory = { DiskFileSink(it.id, dstFile) })

            // Override sessionId to match pre-seeded resume key
            val fixedSid = ByteArray(16) { 1 }
            sender.onGrabbed(listOf(meta), mapOf(meta.id to source))
            sender.sessionId = fixedSid
            val realOffer = com.yodrop.core.protocol.OfferMessage(
                sessionId = fixedSid,
                senderPubKey = (sender.state.value as SenderState.Advertising).offer.senderPubKey,
                fileCount = 1,
                totalBytes = testFileSize,
                previewName = "resume_source.dat"
            )

            receiver.startScanning()
            receiver.onOfferDiscovered(realOffer)
            val claim = receiver.claimOffer()
            sender.onClaimReceived(claim)

            val linkInfo = LinkInfoMessage(LinkType.LAN, "127.0.0.1", port)
            val decision = receiver.acceptTransfer(linkInfo)
            sender.onDecisionReceived(decision, linkInfo)

            val serverJob = async {
                val sock = server.accept()
                SocketConn(sock)
            }
            val clientJob = async {
                val sock = createPlatformSocket()
                sock.connect("127.0.0.1", port)
                SocketConn(sock)
            }

            val sConn = serverJob.await()
            val rConn = clientJob.await()

            sender.attachConnection(sConn)
            receiver.attachConnection(rConn)

            withTimeout(20_000) {
                while (sender.state.value !is SenderState.Done || receiver.state.value !is ReceiverState.Done) {
                    val s = sender.state.value
                    val r = receiver.state.value
                    if (s is SenderState.Failed) error("Sender failed: ${s.error}")
                    if (r is ReceiverState.Failed) error("Receiver failed: ${r.error}")
                    delay(20)
                }
            }

            assertEquals(SenderState.Done, sender.state.value)
            assertEquals(ReceiverState.Done, receiver.state.value)

            assertTrue(dstFile.exists(), "Target file must exist after resumed completion")
            assertEquals(testFileSize, dstFile.length())

            // Final SHA-256 integrity check across both chunks (one pre-seeded, one streamed)
            val resDigest = MessageDigest.getInstance("SHA-256")
            val resRaf = RandomAccessFile(dstFile, "r")
            val resBuf = ByteArray(64 * 1024)
            var resRead: Int
            while (resRaf.read(resBuf).also { resRead = it } != -1) {
                resDigest.update(resBuf, 0, resRead)
            }
            resRaf.close()
            assertTrue(resDigest.digest().contentEquals(expectedSha), "Resumed file SHA-256 must match source")

            sConn.close()
            rConn.close()
            server.close()
            tempDir.deleteRecursively()
        }
    }
}
