package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.PlatformSocket
import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.RawFrame
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.crossplatform.CrossPlatformTransferManager
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase8CrossPlatformSocketTests {

    private class TestSocketConnection(private val socket: PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }

    @Test
    fun testAndroidToIosHotspotTransferWorkflow() = runBlocking(Dispatchers.IO) {
        val manager = CrossPlatformTransferManager(this)

        // 1. Prepare 6 MiB cross-platform test payload
        val payload = ByteArray(6 * 1024 * 1024) { (it % 199).toByte() }
        val sha = CryptoEngine.sha256(payload)
        val meta = FileMeta("xplat-1", "archive.tar.gz", payload.size.toLong(), "application/gzip", sha)

        // 2. Open server socket on loopback
        val server = createPlatformServerSocket(0)
        val assignedPort = server.localPort

        val creds = HotspotCredentials(
            ssid = "YoDrop-Direct-AP",
            passphrase = "wpa3-pass-9988",
            gatewayIp = "127.0.0.1",
            port = assignedPort
        )

        // 3. Android sender prepares grab & link info
        val linkInfo = manager.prepareAndroidSender(listOf(meta), mapOf(meta.id to payload), creds)
        val advOffer = (manager.senderMachine.state.value as SenderState.Advertising).offer

        // 4. iOS receiver discovers offer, claims, and accepts
        manager.receiverMachine.startScanning()
        manager.receiverMachine.onOfferDiscovered(advOffer)
        val claim = manager.receiverMachine.claimOffer()
        manager.senderMachine.onClaimReceived(claim)

        val decision = manager.receiverMachine.acceptTransfer(linkInfo)
        manager.senderMachine.onDecisionReceived(decision, linkInfo)

        // 5. Connect simulated Hotspot sockets
        val serverDeferred = async {
            val clientSock = server.accept()
            TestSocketConnection(clientSock)
        }

        val clientDeferred = async {
            val sock = createPlatformSocket()
            sock.connect(linkInfo.host, linkInfo.port)
            TestSocketConnection(sock)
        }

        val serverConn = serverDeferred.await()
        val clientConn = clientDeferred.await()

        manager.senderMachine.attachConnection(serverConn)
        manager.receiverMachine.attachConnection(clientConn)

        // 6. Await full encrypted transfer
        withTimeout(20_000) {
            while (manager.senderMachine.state.value !is SenderState.Done ||
                manager.receiverMachine.state.value !is ReceiverState.Done
            ) {
                delay(20)
            }
        }

        assertEquals(SenderState.Done, manager.senderMachine.state.value)
        assertEquals(ReceiverState.Done, manager.receiverMachine.state.value)

        // 7. Verify bit-exact integrity
        val received = manager.receiverMachine.completedFilesData[meta.id]
        assertNotNull(received)
        assertTrue(payload.contentEquals(received))

        serverConn.close()
        clientConn.close()
        server.close()
    }
}
