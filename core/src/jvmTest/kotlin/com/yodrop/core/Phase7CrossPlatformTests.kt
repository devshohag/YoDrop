package com.yodrop.core

import com.yodrop.core.protocol.DeviceCaps
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.protocol.Platform
import com.yodrop.core.transport.TransportSelector
import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase7CrossPlatformTests {

    @Test
    fun testHotspotCredentialsSerializationBridge() {
        val creds = HotspotCredentials(
            ssid = "YoDrop-A8F2",
            passphrase = "secret-passphrase-99",
            gatewayIp = "192.168.43.1",
            port = 8988
        )

        val linkInfo = creds.toLinkInfo()
        assertEquals(LinkType.HOTSPOT, linkInfo.linkType)
        assertEquals("192.168.43.1", linkInfo.host)
        assertEquals(8988, linkInfo.port)

        val parsedBack = HotspotCredentials.fromLinkInfo(linkInfo)
        assertNotNull(parsedBack)
        assertEquals(creds.ssid, parsedBack.ssid)
        assertEquals(creds.passphrase, parsedBack.passphrase)
    }

    @Test
    fun testIosToIosPicksMpcPriority() {
        val link = TransportSelector.selectTransport(
            myPlatform = Platform.IOS,
            peerPlatform = Platform.IOS,
            myCaps = DeviceCaps.CAP_MPC,
            peerCaps = DeviceCaps.CAP_MPC,
            myIp = null,
            peerIp = null,
            totalBytes = 20 * 1024 * 1024
        )
        assertEquals(LinkType.MPC, link, "iOS to iOS with MPC capability must select MPC transport")
    }

    @Test
    fun testAndroidToIosCrossPlatformSelectsHotspot() {
        val link = TransportSelector.selectTransport(
            myPlatform = Platform.ANDROID,
            peerPlatform = Platform.IOS,
            myCaps = DeviceCaps.CAP_HOTSPOT_HOST or DeviceCaps.CAP_WIFI_DIRECT,
            peerCaps = DeviceCaps.CAP_HOTSPOT_CLIENT or DeviceCaps.CAP_MPC,
            myIp = null,
            peerIp = null,
            totalBytes = 150 * 1024 * 1024
        )
        assertEquals(LinkType.HOTSPOT, link, "Android sender with Hotspot capability to iOS must select HOTSPOT")
    }
}
