package com.yodrop.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.ByteString

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
    const val CAP_TRUSTED_PAIRING = 1 shl 6
}

@Serializable
sealed interface BleControlMessage

@Serializable
enum class PairingRole { SENDER, RECEIVER }

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EncryptedPairingMessage(val role: PairingRole, val counter: Long, @ByteString val ciphertext: ByteArray) : BleControlMessage {
    override fun toString() = "EncryptedPairingMessage(role=$role, counter=$counter, ciphertext=<redacted>)"
}

@Serializable
data class HelloMessage(
    val deviceName: String,
    val platform: Platform,
    val capabilities: Int,
    val deviceId: ByteArray,
    val lanIp: String? = null
) : BleControlMessage

@Serializable
data class OfferMessage(
    val sessionId: ByteArray,
    val senderPubKey: ByteArray,
    val fileCount: Int,
    val totalBytes: Long,
    val senderName: String
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
) : BleControlMessage {
    fun validate() {
        require(host.isNotBlank() && host.length <= 255 && port in 1..65535) { "Invalid LINK_INFO endpoint" }
        require(extra.size <= 16 && extra.all {
            it.key.length <= 128 && it.value.encodeToByteArray().size <= 2048
        }) { "LINK_INFO metadata too large" }
        if (linkType == LinkType.HOTSPOT) {
            require(!extra["ssid"].isNullOrBlank() && !extra["passphrase"].isNullOrBlank()) {
                "Missing hotspot credentials"
            }
        }
    }
    override fun toString() = "LinkInfoMessage(linkType=$linkType, host=$host, port=$port, extra=<redacted>)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EncryptedLinkInfoMessage(
    val counter: Long,
    @ByteString val ciphertext: ByteArray
) : BleControlMessage {
    override fun toString() = "EncryptedLinkInfoMessage(counter=$counter, ciphertext=<redacted>)"
}


@Serializable
data class OfferDetails(val previewName: String) {
    fun validate() { require(previewName.encodeToByteArray().size in 1..1024) { "Invalid preview name" } }
    override fun toString() = "OfferDetails(previewName=<redacted>)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EncryptedOfferDetailsMessage(
    val counter: Long,
    @ByteString val ciphertext: ByteArray
) : BleControlMessage {
    override fun toString() = "EncryptedOfferDetailsMessage(counter=$counter, ciphertext=<redacted>)"
}
