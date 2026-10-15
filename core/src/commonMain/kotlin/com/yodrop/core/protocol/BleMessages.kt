package com.yodrop.core.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class Platform { ANDROID, IOS }

object DeviceCaps {
    const val CAP_WIFI_DIRECT = 1 shl 0
    const val CAP_HOTSPOT_HOST = 1 shl 1
    const val CAP_LAN = 1 shl 2
    const val CAP_MPC = 1 shl 3
    const val CAP_AWARE = 1 shl 4
}

@Serializable
sealed interface BleControlMessage

@Serializable
data class HelloMessage(
    val deviceId: ByteArray,
    val name: String,
    val platform: Platform,
    val caps: Int
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
    val reason: String? = null
) : BleControlMessage

@Serializable
enum class LinkType { LAN, WIFI_DIRECT, HOTSPOT, MPC }

@Serializable
data class LinkInfoMessage(
    val linkType: LinkType,
    val ssid: String? = null,
    val passphrase: String? = null,
    val host: String,
    val port: Int
) : BleControlMessage
