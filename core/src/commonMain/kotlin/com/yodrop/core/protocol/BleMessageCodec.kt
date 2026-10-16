package com.yodrop.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import com.yodrop.core.crypto.LinkInfoCipher
import com.yodrop.core.crypto.OfferDetailsCipher
import com.yodrop.core.transport.TransportSelector

object BleMessageType {
    const val HELLO = 0x01
    const val OFFER = 0x02
    const val CLAIM = 0x03
    const val ACCEPT = 0x04
    const val DENY = 0x05
    const val LINK_INFO = 0x06
    const val OFFER_DETAILS = 0x07
    const val PAIRING = 0x08
    const val GATT_PROBE = 0x7f
}

/** One type byte followed by exactly that message's CBOR payload, before fragmentation. */
@OptIn(ExperimentalSerializationApi::class)
object BleMessageCodec {
    const val MAX_MESSAGE_BYTES = 16 * 1024
    private val offerCbor = Cbor { ignoreUnknownKeys = false }
    private val cbor = Cbor { ignoreUnknownKeys = true }

    fun encode(message: BleControlMessage): ByteArray {
        validate(message)
        val (type, payload) = when (message) {
            is HelloMessage -> BleMessageType.HELLO to cbor.encodeToByteArray(HelloMessage.serializer(), message)
            is OfferMessage -> BleMessageType.OFFER to offerCbor.encodeToByteArray(OfferMessage.serializer(), message)
            is ClaimMessage -> BleMessageType.CLAIM to cbor.encodeToByteArray(ClaimMessage.serializer(), message)
            is DecisionMessage -> (if (message.accepted) BleMessageType.ACCEPT else BleMessageType.DENY) to
                cbor.encodeToByteArray(DecisionMessage.serializer(), message)
            is EncryptedOfferDetailsMessage -> BleMessageType.OFFER_DETAILS to
                cbor.encodeToByteArray(EncryptedOfferDetailsMessage.serializer(), message)
            is EncryptedLinkInfoMessage -> BleMessageType.LINK_INFO to
                cbor.encodeToByteArray(EncryptedLinkInfoMessage.serializer(), message)
            is EncryptedPairingMessage -> BleMessageType.PAIRING to
                cbor.encodeToByteArray(EncryptedPairingMessage.serializer(), message)
            is LinkInfoMessage -> BleMessageType.GATT_PROBE to cbor.encodeToByteArray(LinkInfoMessage.serializer(), message)
        }
        require(payload.size < MAX_MESSAGE_BYTES) { "BLE control message too large" }
        return byteArrayOf(type.toByte()) + payload
    }

    fun decode(bytes: ByteArray): BleControlMessage {
        require(bytes.size in 2..MAX_MESSAGE_BYTES) { "Invalid BLE control message length" }
        val type = bytes[0].toInt() and 0xff
        val payload = bytes.copyOfRange(1, bytes.size)
        val message = when (type) {
            BleMessageType.HELLO -> cbor.decodeFromByteArray(HelloMessage.serializer(), payload)
            BleMessageType.OFFER -> offerCbor.decodeFromByteArray(OfferMessage.serializer(), payload)
            BleMessageType.CLAIM -> cbor.decodeFromByteArray(ClaimMessage.serializer(), payload)
            BleMessageType.ACCEPT, BleMessageType.DENY ->
                cbor.decodeFromByteArray(DecisionMessage.serializer(), payload).also {
                    require(it.accepted == (type == BleMessageType.ACCEPT)) { "BLE decision/type mismatch" }
                }
            BleMessageType.OFFER_DETAILS -> cbor.decodeFromByteArray(EncryptedOfferDetailsMessage.serializer(), payload)
            BleMessageType.LINK_INFO -> cbor.decodeFromByteArray(EncryptedLinkInfoMessage.serializer(), payload)
            BleMessageType.PAIRING -> cbor.decodeFromByteArray(EncryptedPairingMessage.serializer(), payload)
            BleMessageType.GATT_PROBE -> cbor.decodeFromByteArray(LinkInfoMessage.serializer(), payload)
            else -> throw IllegalArgumentException("Unknown BLE message type $type")
        }
        validate(message)
        return message
    }

    private fun validate(message: BleControlMessage) {
        when (message) {
            is HelloMessage -> {
                require(message.deviceId.size == 16) { "HELLO deviceId must be 16 bytes" }
                require(message.deviceName.encodeToByteArray().size in 1..256) { "Invalid device name" }
                require(message.lanIp == null || TransportSelector.isSameSubnet(message.lanIp, message.lanIp, 32)) {
                    "Invalid HELLO LAN address"
                }
            }
            is OfferMessage -> {
                require(message.sessionId.size == 16 && message.senderPubKey.size == 32) { "Invalid OFFER keys" }
                require(message.fileCount in 1..1000 && message.totalBytes >= 0) { "Invalid OFFER size" }
                require(message.senderName.encodeToByteArray().size in 1..256) { "Invalid sender name" }
            }
            is ClaimMessage -> require(message.sessionId.size == 16 && message.receiverPubKey.size == 32) {
                "Invalid CLAIM keys"
            }
            is DecisionMessage -> {
                require(message.sessionId.size == 16) { "Invalid decision session" }
                require(message.reason.encodeToByteArray().size <= 512) { "Decision reason too long" }
            }
            is EncryptedOfferDetailsMessage -> require(message.counter in 0..OfferDetailsCipher.MAX_COUNTER &&
                message.ciphertext.size in 17..OfferDetailsCipher.MAX_CIPHERTEXT_BYTES) { "Invalid encrypted OFFER_DETAILS" }
            is EncryptedLinkInfoMessage -> require(message.counter in 0..LinkInfoCipher.MAX_COUNTER &&
                message.ciphertext.size in 17..LinkInfoCipher.MAX_CIPHERTEXT_BYTES) { "Invalid encrypted LINK_INFO" }
            is EncryptedPairingMessage -> require(message.counter in 0..0xffff_ffffL &&
                message.ciphertext.size in 17..2064) { "Invalid encrypted pairing control" }
            is LinkInfoMessage -> {
                message.validate()
                require(GattDiagnosticProbe.isProbe(message) && message.linkType == LinkType.LAN && message.port == 1 &&
                    message.extra.keys == setOf(GattDiagnosticProbe.MODE, "token", "padding") &&
                    message.extra[GattDiagnosticProbe.MODE] in setOf("Q", "R") &&
                    message.extra.getValue("token").let { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } &&
                    message.extra.getValue("padding").let { it.length <= 1024 && it.all { c -> c == 'x' } }) {
                    "Plaintext LINK_INFO is forbidden"
                }
            }
        }
    }
}
