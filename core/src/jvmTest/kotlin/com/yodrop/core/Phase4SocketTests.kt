package com.yodrop.core

import kotlinx.coroutines.flow.first

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase4SocketTests {

    private class SocketConnectionWrapper(private val socket: com.yodrop.core.platform.PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }

    @Test
    fun testRealTcpSocketTransferAndIntegrity() = runBlocking<Unit>(Dispatchers.IO) {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "yodrop-test-p4-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val srcFile = File(tempDir, "tcp_test.bin")
        val fileContent = ByteArray(64 * 1024) { (it % 251).toByte() }
        srcFile.writeBytes(fileContent)

        val dstFile = File(tempDir, "tcp_received.bin")

        val sha256 = CryptoEngine.sha256(fileContent)
        val meta = FileMeta("tcp-f1", "tcp_test.bin", fileContent.size.toLong(), "application/octet-stream", sha256)
        val source = DiskFileSource(meta.id, srcFile)

        val server = createPlatformServerSocket(0)
        val port = server.localPort
        val latch = CountDownLatch(2)

        val scope = CoroutineScope(Dispatchers.IO)
        val sender = SenderStateMachine(scope)
        val receiver = ReceiverStateMachine(scope, sinkFactory = { DiskFileSink(it.id, dstFile) })

        sender.onGrabbed(listOf(meta), mapOf(meta.id to source))
        val offer = (sender.state.value as SenderState.Grabbed).offer

        receiver.startScanning()
        receiver.onOfferDiscovered(offer)
        val claim = receiver.claimOffer()
        sender.onClaimReceived(claim)
        receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))

        val linkInfo = LinkInfoMessage(LinkType.WIFI_DIRECT, "127.0.0.1", port)
        receiver.state.first { it is ReceiverState.AcceptPrompt }
        val decision = receiver.acceptTransfer(linkInfo)
        sender.onDecisionReceived(decision, linkInfo)

        thread(start = true, isDaemon = true) {
            runBlocking {
                val clientSock = server.accept()
                val serverConn = SocketConnectionWrapper(clientSock)
                sender.attachConnection(serverConn)

                while (sender.state.value !is SenderState.Done && sender.state.value !is SenderState.Failed) {
                    delay(20)
                }
                serverConn.close()
                latch.countDown()
            }
        }

        thread(start = true, isDaemon = true) {
            runBlocking {
                val clientSock = createPlatformSocket()
                clientSock.connect("127.0.0.1", port)
                val clientConn = SocketConnectionWrapper(clientSock)
                receiver.attachConnection(clientConn)

                while (receiver.state.value !is ReceiverState.Done && receiver.state.value !is ReceiverState.Failed) {
                    delay(20)
                }
                clientConn.close()
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        runBlocking {
            server.close()
        }

        assertTrue(dstFile.exists(), "Target file must exist after transfer commit")
        assertEquals(srcFile.length(), dstFile.length())
        tempDir.deleteRecursively()
    }
}
