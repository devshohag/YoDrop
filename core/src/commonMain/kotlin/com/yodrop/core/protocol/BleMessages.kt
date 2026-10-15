package com.yodrop.core.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class Platform {
    ANDROID,
    IOS
}

@Serializable
enum class LinkType {
    WIFI_DIRECT,
    HOTSPOT,
    LAN,
    MPC
}

object DeviceCaps {
    const val CAP_BLE = 1 shl 0
    const val CAP_WIFI_DIRECT = 1 shl 1
    const val CAP_HOTSPOT_HOST = 1 shl 2
    const val CAP_HOTSPOT_CLIENT = 1 shl 3
    const val CAP_LAN = 1 shl 4
    const val CAP_MPC = 1 shl 5
}

@Serializable
sealed interface BleControlMessage

@Serializable
data class HelloMessage(
    val deviceName: String,
    val platform: Platform,
    val capabilities: Int
) : BleControlMessage

@Serializable
data class OfferMessage(
    val sessionId: ByteArray,
    val senderPubKey: ByteArray,
    val fileCount: Int,
    val totalBytes: Long,
    val previewName: String
) : BleControlMessage

@Serializable
data class ClaimMessage(
    val sessionId: ByteArray,
    val receiverPubKey: ByteArray
) : BleControlMessage

@Serializable
data class DecisionMessage(
    val sessionId: ByteArray,
    val accepted: Boolean,
    val reason: String = ""
) : BleControlMessage

@Serializable
data class LinkInfoMessage(
    val linkType: LinkType,
    val host: String,
    val port: Int,
    val extra: Map<String, String> = emptyMap()
) : BleControlMessage
