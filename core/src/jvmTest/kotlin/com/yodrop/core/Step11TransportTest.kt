package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.protocol.*
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.*
import kotlinx.coroutines.*
import kotlin.test.*

class Step11TransportTest {
    private fun select(
        sender: Platform = Platform.ANDROID,
        peer: Platform = Platform.ANDROID,
        senderCaps: Int = 0,
        peerCaps: Int = 0,
        senderIp: String? = null,
        peerIp: String? = null,
        prefix: Int? = null,
        bytes: Long = 1024 * 1024
    ) = TransportSelector.selectTransport(sender, peer, senderCaps, peerCaps,
        senderIp, peerIp, bytes, prefix)

    @Test
    fun lanUsesReal16And23PrefixesAcross24Boundaries() {
        for ((local, remote, prefix) in listOf(
            Triple("10.5.1.10", "10.5.240.20", 16),
            Triple("192.168.2.10", "192.168.3.20", 23))) {
            assertEquals(TransportSelection.DataLink(LinkType.LAN), select(
                senderCaps = DeviceCaps.CAP_LAN or DeviceCaps.CAP_WIFI_DIRECT,
                peerCaps = DeviceCaps.CAP_LAN or DeviceCaps.CAP_WIFI_DIRECT,
                senderIp = local, peerIp = remote, prefix = prefix))
        }
        assertEquals(TransportSelection.Unavailable, select(
            senderCaps = DeviceCaps.CAP_LAN, peerCaps = DeviceCaps.CAP_LAN,
            senderIp = "192.168.3.10", peerIp = "192.168.4.20", prefix = 23))
    }

    @Test
    fun missingPrefixOrAddressNeverInventsLan() {
        for (address in listOf(null, "192.168.1.20")) {
            assertEquals(TransportSelection.Unavailable, select(
                senderCaps = DeviceCaps.CAP_LAN, peerCaps = DeviceCaps.CAP_LAN,
                senderIp = "192.168.1.10", peerIp = address))
        }
        assertEquals(TransportSelection.Unavailable, select(
            senderCaps = DeviceCaps.CAP_LAN, peerCaps = DeviceCaps.CAP_LAN,
            senderIp = null, peerIp = "192.168.1.20", prefix = 24))
    }

    @Test
    fun invalidAddressesAndPrefixesCannotMatch() {
        for (bad in listOf("256.1.1.1", "-1.1.1.1", "+1.1.1.1", "1.2.3",
            "1.2.3.4.5", "1..3.4", " 1.2.3.4", "name.local", "::1", "9999999999.2.3.4")) {
            assertFalse(TransportSelector.isSameSubnet(bad, bad, 24), bad)
        }
        for (prefix in listOf(Int.MIN_VALUE, -1, 33, Int.MAX_VALUE)) {
            assertFalse(TransportSelector.isSameSubnet("10.0.0.1", "10.0.0.1", prefix))
        }
        assertTrue(TransportSelector.isSameSubnet("10.0.0.1", "192.168.1.1", 0))
        assertTrue(TransportSelector.isSameSubnet("10.0.0.1", "10.0.0.1", 32))
        assertFalse(TransportSelector.isSameSubnet("10.0.0.1", "10.0.0.2", 32))
    }

    @Test
    fun lanAndWifiDirectRequireBothCapabilities() {
        for ((localCaps, remoteCaps) in listOf(0 to DeviceCaps.CAP_WIFI_DIRECT,
            DeviceCaps.CAP_WIFI_DIRECT to 0)) {
            assertEquals(TransportSelection.Unavailable,
                select(senderCaps = localCaps, peerCaps = remoteCaps))
        }
        for ((localCaps, remoteCaps) in listOf(0 to DeviceCaps.CAP_LAN, DeviceCaps.CAP_LAN to 0)) {
            assertEquals(TransportSelection.Unavailable, select(
                senderCaps = localCaps, peerCaps = remoteCaps,
                senderIp = "10.0.0.1", peerIp = "10.0.0.2", prefix = 16))
        }
    }

    @Test
    fun crossPlatformHotspotRequiresSenderHostAndPeerClient() {
        assertEquals(TransportSelection.DataLink(LinkType.HOTSPOT), select(
            peer = Platform.IOS, senderCaps = DeviceCaps.CAP_HOTSPOT_HOST,
            peerCaps = DeviceCaps.CAP_HOTSPOT_CLIENT))
        assertEquals(TransportSelection.Unavailable, select(
            peer = Platform.IOS, senderCaps = DeviceCaps.CAP_HOTSPOT_HOST))
        assertEquals(TransportSelection.Unavailable, select(
            peer = Platform.IOS, peerCaps = DeviceCaps.CAP_HOTSPOT_CLIENT))
        assertEquals(TransportSelection.Unavailable, select(
            sender = Platform.IOS, peer = Platform.ANDROID,
            senderCaps = DeviceCaps.CAP_HOTSPOT_CLIENT, peerCaps = DeviceCaps.CAP_HOTSPOT_HOST))
        assertEquals(TransportSelection.Unavailable, select(
            senderCaps = DeviceCaps.CAP_HOTSPOT_HOST, peerCaps = DeviceCaps.CAP_HOTSPOT_CLIENT))
    }

    @Test
    fun iosPeersUseMpcOnlyWithBothCapabilitiesAndLanStillWins() {
        assertEquals(TransportSelection.DataLink(LinkType.MPC), select(
            sender = Platform.IOS, peer = Platform.IOS,
            senderCaps = DeviceCaps.CAP_MPC, peerCaps = DeviceCaps.CAP_MPC))
        assertEquals(TransportSelection.Unavailable, select(
            sender = Platform.IOS, peer = Platform.IOS, senderCaps = DeviceCaps.CAP_MPC))
        assertEquals(TransportSelection.DataLink(LinkType.LAN), select(
            sender = Platform.IOS, peer = Platform.IOS,
            senderCaps = DeviceCaps.CAP_MPC or DeviceCaps.CAP_LAN,
            peerCaps = DeviceCaps.CAP_MPC or DeviceCaps.CAP_LAN,
            senderIp = "10.0.0.1", peerIp = "10.0.1.2", prefix = 16))
    }

    @Test
    fun bleIsStrictlyBelow64KiBAndDistinctFromUnavailable() {
        for (size in listOf(0L, 1L, 64 * 1024L - 1)) {
            assertEquals(TransportSelection.BleOnly, select(
                senderCaps = DeviceCaps.CAP_BLE, peerCaps = DeviceCaps.CAP_BLE, bytes = size))
        }
        for (size in listOf(64 * 1024L, 64 * 1024L + 1)) {
            assertEquals(TransportSelection.Unavailable, select(
                senderCaps = DeviceCaps.CAP_BLE, peerCaps = DeviceCaps.CAP_BLE, bytes = size))
            assertEquals(TransportSelection.DataLink(LinkType.WIFI_DIRECT), select(
                senderCaps = DeviceCaps.CAP_BLE or DeviceCaps.CAP_WIFI_DIRECT,
                peerCaps = DeviceCaps.CAP_BLE or DeviceCaps.CAP_WIFI_DIRECT, bytes = size))
        }
        assertEquals(TransportSelection.Unavailable, select(bytes = 1))
        assertEquals(TransportSelection.Unavailable, select(senderCaps = DeviceCaps.CAP_BLE, bytes = 1))
        assertFailsWith<IllegalArgumentException> { select(bytes = -1) }
    }

    @Test
    fun noCommonLinkDoesNotFallBackToSenderOnlyWifiDirectOrLan() {
        assertEquals(TransportSelection.Unavailable, select())
        assertEquals(TransportSelection.Unavailable,
            select(senderCaps = DeviceCaps.CAP_WIFI_DIRECT, peer = Platform.IOS))
        assertEquals(TransportSelection.Unavailable,
            select(senderCaps = DeviceCaps.CAP_LAN, peerCaps = DeviceCaps.CAP_MPC))
    }

    @Test
    fun senderUsesPeerHelloAndActualOfferSizeWithoutAdvancingState() = runBlocking {
        withTimeout(5_000) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sender = SenderStateMachine(owner)
            try {
                val peer = HelloMessage("Receiver", Platform.IOS, DeviceCaps.CAP_HOTSPOT_CLIENT, ByteArray(16))
                assertFailsWith<IllegalStateException> {
                    sender.selectTransport(Platform.ANDROID, DeviceCaps.CAP_HOTSPOT_HOST, peer)
                }
                val size = 64 * 1024L
                val data = ByteArray(size.toInt())
                val file = FileMeta("f", "payload.bin", size, "application/octet-stream",
                    CryptoEngine.sha256(data))
                assertTrue(sender.onGrabbed(listOf(file), mapOf("f" to MemoryFileSource("f", data))))
                val offer = (sender.state.value as SenderState.Grabbed).offer
                val receiverKey = CryptoEngine.generateX25519KeyPair()
                assertTrue(sender.onClaimReceived(ClaimMessage(offer.sessionId, receiverKey.publicKey)))
                val before = sender.state.value
                assertEquals(TransportSelection.DataLink(LinkType.HOTSPOT), sender.selectTransport(
                    Platform.ANDROID, DeviceCaps.CAP_HOTSPOT_HOST or DeviceCaps.CAP_BLE,
                    peer.copy(capabilities = DeviceCaps.CAP_HOTSPOT_CLIENT or DeviceCaps.CAP_BLE)))
                assertEquals(TransportSelection.Unavailable, sender.selectTransport(
                    Platform.ANDROID, DeviceCaps.CAP_HOTSPOT_HOST,
                    peer.copy(capabilities = 0)))
                assertEquals(before, sender.state.value)
            } finally {
                sender.cancel("test teardown")
                owner.coroutineContext.cancelChildren()
            }
        }
    }

    @Test
    fun sharedTcpAdapterReadsWritesAndClosesBothLanDirections() = runBlocking {
        withTimeout(5_000) {
            val transport = LanTransport()
            // ConnectionListener does not expose an ephemeral port; release a reservation first.
            val reservation = createPlatformServerSocket(0)
            val port = reservation.localPort
            reservation.close()
            val server = transport.listen(port)
            val accepted = async(Dispatchers.IO) { server.accept() }
            val client = transport.connect(LinkInfoMessage(LinkType.LAN, "127.0.0.1", port))
            val peer = accepted.await()
            try {
                assertIs<TcpConnection>(client)
                assertIs<TcpConnection>(peer)
                assertFalse(client.isClosed)
                assertFalse(peer.isClosed)
                val outgoing = RawFrame(0x02, byteArrayOf(1, 2, 3))
                client.writeFrame(outgoing)
                val received = peer.readFrame()
                assertEquals(outgoing.type, received.type)
                assertContentEquals(outgoing.payload, received.payload)
                peer.writeFrame(RawFrame(0x05, byteArrayOf(9)))
                assertContentEquals(byteArrayOf(9), client.readFrame().payload)
            } finally {
                client.close()
                peer.close()
                server.close()
            }
            assertTrue(client.isClosed)
            assertTrue(peer.isClosed)
        }
    }
}
