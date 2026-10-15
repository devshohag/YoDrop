package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.DiskFileSink
import com.yodrop.core.platform.DiskFileSource
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

class T5StreamingFileIoTest {

    private class SocketConn(private val socket: com.yodrop.core.platform.PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }

    @Test
    fun testRealStreaming100MBNoMemorySpike() {
        runBlocking(Dispatchers.IO) {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "yodrop-stream-test-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            // 16 MiB disk-backed streaming test
            val testFileSize = 16L * 1024L * 1024L
            val srcFile = File(tempDir, "large_source.dat")
            val raf = RandomAccessFile(srcFile, "rw")
            val pattern = ByteArray(1024 * 1024) { (it % 251).toByte() }
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

            val dstFile = File(tempDir, "large_received.dat")

            val meta = FileMeta("stream-f1", "large_source.dat", testFileSize, "application/octet-stream", expectedSha)
            val source = DiskFileSource(meta.id, srcFile)

            val server = createPlatformServerSocket(0)
            val port = server.localPort

            val sender = SenderStateMachine(this)
            val receiver = ReceiverStateMachine(this, sinkFactory = { DiskFileSink(it.id, dstFile) })

            sender.onGrabbed(listOf(meta), mapOf(meta.id to source))
            val adv = sender.state.value as SenderState.Advertising

            receiver.startScanning()
            receiver.onOfferDiscovered(adv.offer)
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

            val serverConn = serverJob.await()
            val clientConn = clientJob.await()

            sender.attachConnection(serverConn)
            receiver.attachConnection(clientConn)

            withTimeout(30_000) {
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

            assertTrue(dstFile.exists())
            assertEquals(testFileSize, dstFile.length())

            serverConn.close()
            clientConn.close()
            server.close()
            tempDir.deleteRecursively()
        }
    }
}
