package com.yodrop.core.transport

import com.yodrop.core.protocol.DeviceCaps
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.protocol.Platform

sealed interface TransportSelection {
    data object BleOnly : TransportSelection
    data class DataLink(val linkType: LinkType) : TransportSelection
    data object Unavailable : TransportSelection
}

/** IPv4 address and actual prefix length of the sender's active LAN interface. */
data class LanAddress(val ip: String, val prefixLength: Int)

object TransportSelector {
    const val SMALL_PAYLOAD_THRESHOLD_BYTES = 64 * 1024L

    /** Sender-owned selection. Missing LAN metadata never implies a /24 network. */
    fun selectTransport(
        senderPlatform: Platform,
        peerPlatform: Platform,
        senderCaps: Int,
        peerCaps: Int,
        senderIp: String?,
        peerIp: String?,
        totalBytes: Long,
        senderPrefixLength: Int? = null
    ): TransportSelection {
        require(totalBytes >= 0) { "Payload size must be non-negative" }
        fun bothHave(cap: Int) = senderCaps and cap != 0 && peerCaps and cap != 0
        fun dataLink(type: LinkType) = TransportSelection.DataLink(type)

        if (totalBytes < SMALL_PAYLOAD_THRESHOLD_BYTES && bothHave(DeviceCaps.CAP_BLE)) {
            return TransportSelection.BleOnly
        }
        if (bothHave(DeviceCaps.CAP_LAN) && senderIp != null && peerIp != null &&
            senderPrefixLength != null && isSameSubnet(senderIp, peerIp, senderPrefixLength)) {
            return dataLink(LinkType.LAN)
        }
        if (senderPlatform == Platform.ANDROID && peerPlatform == Platform.ANDROID &&
            bothHave(DeviceCaps.CAP_WIFI_DIRECT)) {
            return dataLink(LinkType.WIFI_DIRECT)
        }
        if (senderPlatform == Platform.IOS && peerPlatform == Platform.IOS &&
            bothHave(DeviceCaps.CAP_MPC)) {
            return dataLink(LinkType.MPC)
        }
        if (senderPlatform == Platform.ANDROID && peerPlatform == Platform.IOS &&
            senderCaps and DeviceCaps.CAP_HOTSPOT_HOST != 0 &&
            peerCaps and DeviceCaps.CAP_HOTSPOT_CLIENT != 0) {
            return dataLink(LinkType.HOTSPOT)
        }
        return TransportSelection.Unavailable
    }

    fun isSameSubnet(ip1: String, ip2: String, prefixLength: Int): Boolean {
        if (prefixLength !in 0..32) return false
        val address1 = parseIpv4(ip1) ?: return false
        val address2 = parseIpv4(ip2) ?: return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (address1 and mask) == (address2 and mask)
    }

    private fun parseIpv4(ip: String): Int? {
        val octets = ip.split('.')
        if (octets.size != 4) return null
        var result = 0
        for (octet in octets) {
            if (octet.isEmpty() || octet.length > 3 || octet.any { it !in '0'..'9' }) return null
            val value = octet.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            result = (result shl 8) or value
        }
        return result
    }
}
