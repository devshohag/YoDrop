package com.yodrop.core

import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.DeviceCaps
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.protocol.Platform
import com.yodrop.core.transport.TransportSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class Phase5SelectorTests {

    @Test
    fun testSameSubnetDetection() {
        assertTrue(TransportSelector.isSameSubnet("192.168.1.10", "192.168.1.55"))
        assertFalse(TransportSelector.isSameSubnet("192.168.1.10", "192.168.2.55"))
        assertTrue(TransportSelector.isSameSubnet("10.0.0.5", "10.0.0.99", prefixLength = 24))
    }

    @Test
    fun testSameLanPicksLanPriority() {
        val link = TransportSelector.selectTransport(
            myPlatform = Platform.ANDROID,
            peerPlatform = Platform.ANDROID,
            myCaps = DeviceCaps.CAP_LAN or DeviceCaps.CAP_WIFI_DIRECT,
            peerCaps = DeviceCaps.CAP_LAN or DeviceCaps.CAP_WIFI_DIRECT,
            myIp = "192.168.1.105",
            peerIp = "192.168.1.120",
            totalBytes = 50 * 1024 * 1024
        )
        assertEquals(LinkType.LAN, link, "When same LAN subnet is detected, LAN must be Priority 1")
    }

    @Test
    fun testAndroidToAndroidDifferentNetworkPicksWifiDirect() {
        val link = TransportSelector.selectTransport(
            myPlatform = Platform.ANDROID,
            peerPlatform = Platform.ANDROID,
            myCaps = DeviceCaps.CAP_WIFI_DIRECT,
            peerCaps = DeviceCaps.CAP_WIFI_DIRECT,
            myIp = "192.168.1.105",
            peerIp = "10.0.0.12",
            totalBytes = 50 * 1024 * 1024
        )
        assertEquals(LinkType.WIFI_DIRECT, link, "Different networks on Android must pick Wi-Fi Direct")
    }

    @Test
    fun testSmallPayloadSkipsDataLink() {
        val link = TransportSelector.selectTransport(
            myPlatform = Platform.ANDROID,
            peerPlatform = Platform.IOS,
            myCaps = DeviceCaps.CAP_LAN,
            peerCaps = DeviceCaps.CAP_LAN,
            myIp = "192.168.1.105",
            peerIp = "192.168.1.120",
            totalBytes = 25 * 1024
        )
        assertNull(link, "Payloads under 64 KB must return null (BLE-direct skip)")
    }

    @Test
    fun testLanTransportLoopbackConnect() = runBlocking(Dispatchers.IO) {
        val server = createPlatformServerSocket(0)
        val assignedPort = server.localPort

        val acceptJob = async {
            server.accept()
        }

        val clientSocket = createPlatformSocket()
        clientSocket.connect("127.0.0.1", assignedPort)

        val serverSocket = acceptJob.await()

        assertTrue(clientSocket.isConnected)
        assertTrue(serverSocket.isConnected)

        clientSocket.close()
        serverSocket.close()
        server.close()
    }
}
