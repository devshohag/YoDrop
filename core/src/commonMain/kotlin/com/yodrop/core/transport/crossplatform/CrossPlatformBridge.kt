package com.yodrop.core.transport.crossplatform

import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import kotlinx.serialization.Serializable

@Serializable
data class HotspotCredentials(
    val ssid: String,
    val passphrase: String,
    val gatewayIp: String = "192.168.49.1",
    val port: Int = 8988
) {
    fun toLinkInfo(): LinkInfoMessage {
        return LinkInfoMessage(
            linkType = LinkType.HOTSPOT,
            host = gatewayIp,
            port = port
        )
    }

    companion object {
        fun fromLinkInfo(linkInfo: LinkInfoMessage): HotspotCredentials? {
            if (linkInfo.linkType != LinkType.HOTSPOT) return null
            return HotspotCredentials(
                ssid = "YoDrop-Direct-AP",
                passphrase = "wpa3-pass-9988",
                gatewayIp = linkInfo.host,
                port = linkInfo.port
            )
        }
    }
}

interface CrossPlatformBridge {
    suspend fun startHotspot(): HotspotCredentials
    suspend fun connectToHotspot(credentials: HotspotCredentials): Boolean
    suspend fun stopHotspot()
}
