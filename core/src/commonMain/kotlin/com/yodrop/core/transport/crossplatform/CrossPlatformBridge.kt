package com.yodrop.core.transport.crossplatform

import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import kotlinx.serialization.Serializable

@Serializable
data class HotspotCredentials(
    val ssid: String,
    val passphrase: String,
    val gatewayIp: String,
    val port: Int
) {
    fun toLinkInfo(): LinkInfoMessage {
        return LinkInfoMessage(
            linkType = LinkType.HOTSPOT,
            host = gatewayIp,
            port = port,
            extra = mapOf(
                "ssid" to ssid,
                "passphrase" to passphrase
            )
        )
    }

    companion object {
        fun fromLinkInfo(linkInfo: LinkInfoMessage): HotspotCredentials? {
            if (linkInfo.linkType != LinkType.HOTSPOT) return null
            val ssidStr = linkInfo.extra["ssid"] ?: return null
            val passStr = linkInfo.extra["passphrase"] ?: return null
            return HotspotCredentials(
                ssid = ssidStr,
                passphrase = passStr,
                gatewayIp = linkInfo.host,
                port = linkInfo.port
            )
        }
    }
}

interface MpcSessionDelegate {
    fun onPeerConnected(peerId: String)
    fun onPeerDisconnected(peerId: String)
    fun onDataReceived(data: ByteArray)
}
