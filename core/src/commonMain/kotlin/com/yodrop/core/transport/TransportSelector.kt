package com.yodrop.core.transport

import com.yodrop.core.protocol.*

object TransportSelector {

    const val SMALL_PAYLOAD_THRESHOLD_BYTES = 64 * 1024L // 64 KB

    fun selectTransport(
        myPlatform: Platform,
        peerPlatform: Platform,
        myCaps: Int,
        peerCaps: Int,
        myIp: String?,
        peerIp: String?,
        totalBytes: Long
    ): LinkType? {
        // Priority 5: Payload < 64 KB -> BLE direct (no Wi-Fi/data link setup needed)
        if (totalBytes in 1..SMALL_PAYLOAD_THRESHOLD_BYTES) {
            return null // Signal to use BLE data channel directly
        }

        // Priority 1: Same LAN (both have CAP_LAN and same subnet)
        val bothHaveLanCap = (myCaps and DeviceCaps.CAP_LAN != 0) && (peerCaps and DeviceCaps.CAP_LAN != 0)
        if (bothHaveLanCap && myIp != null && peerIp != null && isSameSubnet(myIp, peerIp)) {
            return LinkType.LAN
        }

        // Priority 2: Android <-> Android -> Wi-Fi Direct
        if (myPlatform == Platform.ANDROID && peerPlatform == Platform.ANDROID) {
            val bothSupportWfd = (myCaps and DeviceCaps.CAP_WIFI_DIRECT != 0) && (peerCaps and DeviceCaps.CAP_WIFI_DIRECT != 0)
            if (bothSupportWfd) {
                return LinkType.WIFI_DIRECT
            }
        }

        // Priority 3: iOS <-> iOS -> MultipeerConnectivity
        if (myPlatform == Platform.IOS && peerPlatform == Platform.IOS) {
            val bothSupportMpc = (myCaps and DeviceCaps.CAP_MPC != 0) && (peerCaps and DeviceCaps.CAP_MPC != 0)
            if (bothSupportMpc) {
                return LinkType.MPC
            }
        }

        // Priority 4: Android -> iOS / Cross-Platform -> Hotspot
        val hostCanHotspot = (myCaps and DeviceCaps.CAP_HOTSPOT_HOST != 0)
        if (hostCanHotspot) {
            return LinkType.HOTSPOT
        }

        // Fallback to Wi-Fi Direct if available
        if (myCaps and DeviceCaps.CAP_WIFI_DIRECT != 0) {
            return LinkType.WIFI_DIRECT
        }

        return LinkType.LAN
    }

    fun isSameSubnet(ip1: String, ip2: String, prefixLength: Int = 24): Boolean {
        return try {
            val p1 = ip1.split(".").map { it.toInt() }
            val p2 = ip2.split(".").map { it.toInt() }
            if (p1.size != 4 || p2.size != 4) return false

            val int1 = (p1[0] shl 24) or (p1[1] shl 16) or (p1[2] shl 8) or p1[3]
            val int2 = (p2[0] shl 24) or (p2[1] shl 16) or (p2[2] shl 8) or p2[3]

            val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
            (int1 and mask) == (int2 and mask)
        } catch (_: Exception) {
            false
        }
    }
}
