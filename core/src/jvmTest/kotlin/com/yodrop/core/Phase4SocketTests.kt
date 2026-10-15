package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.PlatformSocket
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase4SocketTests {

    private class SocketConnectionWrapper(private val socket: PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }

    @Test
    fun testRealTcpSocketTransferAndIntegrity() = runTest {
        val server = createPlatformServerSocket(0)
        val port = server.localPort

        val sender = SenderStateMachine(this)
        val receiver = ReceiverStateMachine(this)

        val fileContent = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val sha256 = CryptoEngine.sha256(fileContent)
        val meta = FileMeta("tcp-f1", "large_video.mp4", fileContent.size.toLong(), "video/mp4", sha256)

        sender.onGrabbed(listOf(meta), mapOf(meta.id to fileContent))
        val offer = (sender.state.value as SenderState.Advertising).offer

        receiver.startScanning()
        receiver.onOfferDiscovered(offer)
        val claim = receiver.claimOffer()
        sender.onClaimReceived(claim)

        val linkInfo = LinkInfoMessage(LinkType.WIFI_DIRECT, host = "127.0.0.1", port = port)
        val decision = receiver.acceptTransfer(linkInfo)
        sender.onDecisionReceived(decision, linkInfo)

        val serverAcceptDeferred = async(Dispatchers.IO) {
            val clientSocket = server.accept()
            SocketConnectionWrapper(clientSocket)
        }

        val clientConnectDeferred = async(Dispatchers.IO) {
            val sock = createPlatformSocket()
            sock.connect("127.0.0.1", port)
            SocketConnectionWrapper(sock)
        }

        val serverSideConn = serverAcceptDeferred.await()
        val clientSideConn = clientConnectDeferred.await()

        sender.attachConnection(serverSideConn)
        receiver.attachConnection(clientSideConn)

        withTimeout(15_000) {
            while (sender.state.value !is SenderState.Done || receiver.state.value !is ReceiverState.Done) {
                delay(20)
            }
        }

        assertEquals(SenderState.Done, sender.state.value)
        assertEquals(ReceiverState.Done, receiver.state.value)

        val receivedBytes = receiver.completedFilesData[meta.id]
        assertTrue(receivedBytes != null && fileContent.contentEquals(receivedBytes))

        serverSideConn.close()
        clientSideConn.close()
        server.close()
    }
}
