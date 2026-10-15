package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
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
    fun testRealTcpSocketTransferAndIntegrity() {
        val server = createPlatformServerSocket(0)
        val port = server.localPort

        val fileContent = ByteArray(64 * 1024) { (it % 251).toByte() }
        val sha256 = CryptoEngine.sha256(fileContent)
        val meta = FileMeta("tcp-f1", "test.bin", fileContent.size.toLong(), "application/octet-stream", sha256)

        val latch = CountDownLatch(2)

        thread(start = true, isDaemon = true) {
            runBlocking {
                val sender = SenderStateMachine(this)
                sender.onGrabbed(listOf(meta), mapOf(meta.id to fileContent))
                val offer = (sender.state.value as SenderState.Advertising).offer

                val clientSock = server.accept()
                val serverConn = SocketConnectionWrapper(clientSock)

                val rxKey = CryptoEngine.generateX25519KeyPair()
                sender.onClaimReceived(com.yodrop.core.protocol.ClaimMessage(offer.sessionId, rxKey.publicKey))
                sender.onDecisionReceived(
                    com.yodrop.core.protocol.DecisionMessage(offer.sessionId, true),
                    LinkInfoMessage(LinkType.WIFI_DIRECT, "127.0.0.1", port)
                )

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
                val receiver = ReceiverStateMachine(this)
                val dummyOffer = com.yodrop.core.protocol.OfferMessage(
                    ByteArray(16) { 1 },
                    CryptoEngine.generateX25519KeyPair().publicKey,
                    1,
                    fileContent.size.toLong(),
                    "test.bin"
                )
                receiver.startScanning()
                receiver.onOfferDiscovered(dummyOffer)

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

        latch.await(5, TimeUnit.SECONDS)
        runBlocking {
            server.close()
        }
    }
}
