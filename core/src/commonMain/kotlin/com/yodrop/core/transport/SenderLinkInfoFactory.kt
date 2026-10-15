package com.yodrop.core.transport

import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.transport.crossplatform.HotspotCredentials

/** Values come from the sender's actual listener/group/reservation, never a guessed gateway. */
object SenderLinkInfoFactory {
    fun lan(ip: String, port: Int) = LinkInfoMessage(LinkType.LAN, ip, port).also { it.validate() }
    fun wifiDirect(groupOwnerIp: String, port: Int) =
        LinkInfoMessage(LinkType.WIFI_DIRECT, groupOwnerIp, port).also { it.validate() }
    fun hotspot(credentials: HotspotCredentials) = LinkInfoMessage(
        LinkType.HOTSPOT, credentials.gatewayIp, credentials.port,
        mapOf("ssid" to credentials.ssid, "passphrase" to credentials.passphrase)
    ).also { it.validate() }
}
