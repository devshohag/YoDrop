package com.yodrop.core

import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.DeviceCaps
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.protocol.Platform
import com.yodrop.core.transport.TransportSelector
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
        assertEquals(LinkType.LAN, link)
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
        assertEquals(LinkType.WIFI_DIRECT, link)
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
        assertNull(link)
    }

    @Test
    fun testLanTransportLoopbackConnect() {
        val server = createPlatformServerSocket(0)
        val assignedPort = server.localPort
        val latch = CountDownLatch(1)
        var serverConnected = false

        val serverThread = thread(start = true, isDaemon = true) {
            runBlocking {
                val sock = server.accept()
                serverConnected = sock.isConnected
                latch.countDown()
                sock.close()
            }
        }

        runBlocking {
            val clientSocket = createPlatformSocket()
            clientSocket.connect("127.0.0.1", assignedPort)
            assertTrue(clientSocket.isConnected)
            latch.await(5, TimeUnit.SECONDS)
            assertTrue(serverConnected)
            clientSocket.close()
            server.close()
        }
    }
}
