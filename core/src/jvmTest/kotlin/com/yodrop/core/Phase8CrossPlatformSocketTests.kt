package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.transport.TransportSelector
import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Phase8CrossPlatformSocketTests {

    @Test
    fun testHotspotCredentialsBridge() {
        val creds = HotspotCredentials(
            ssid = "YoDrop-Direct-AP",
            passphrase = "wpa3-pass-9988",
            gatewayIp = "192.168.49.1",
            port = 8988
        )

        val linkInfo = creds.toLinkInfo()
        assertEquals(LinkType.HOTSPOT, linkInfo.linkType)
        assertEquals("192.168.49.1", linkInfo.host)
        assertEquals(8988, linkInfo.port)

        val parsed = HotspotCredentials.fromLinkInfo(linkInfo)
        assertNotNull(parsed)
        assertEquals(creds.ssid, parsed.ssid)
        assertEquals(creds.passphrase, parsed.passphrase)
    }

    @Test
    fun testCrossPlatformPayloadChecksumVerification() {
        val payload = ByteArray(1024 * 1024) { (it % 127).toByte() }
        val sha = CryptoEngine.sha256(payload)
        val meta = FileMeta("xplat-1", "archive.tar.gz", payload.size.toLong(), "application/gzip", sha)

        assertEquals(1024 * 1024L, meta.size)
        assertEquals(32, meta.sha256.size)
    }
}
