package com.yodrop.core.crypto

import com.yodrop.core.protocol.EncryptedOfferDetailsMessage
import com.yodrop.core.protocol.OfferDetails
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/** Dedicated S2R control key, fixed nonce domain + u32 counter, AAD = active sessionId. */
@OptIn(ExperimentalSerializationApi::class)
object OfferDetailsCipher {
    const val MAX_COUNTER = 0xffff_ffffL
    const val MAX_PLAINTEXT_BYTES = 2048
    const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 16
    private val cbor = Cbor { ignoreUnknownKeys = true }
    private val info = "YoDrop-BLE-S2R-OFFER_DETAILS-v1".encodeToByteArray()

    private fun controlKey(keys: SessionKeys): ByteArray {
        require(keys.senderToReceiverKey.size == 32)
        // RFC 5869 Expand, one block, with the already-derived directional key as PRK.
        return CryptoEngine.computeHmacSha256(keys.senderToReceiverKey, info + byteArrayOf(1))
    }
    private fun nonce(counter: Long): ByteArray {
        require(counter in 0..MAX_COUNTER) { "OFFER_DETAILS nonce counter exhausted" }
        return "OFRDTLS1".encodeToByteArray() + byteArrayOf(
            (counter ushr 24).toByte(), (counter ushr 16).toByte(),
            (counter ushr 8).toByte(), counter.toByte())
    }

    fun seal(keys: SessionKeys, sessionId: ByteArray, counter: Long, details: OfferDetails): EncryptedOfferDetailsMessage {
        require(sessionId.size == 16)
        details.validate()
        val plaintext = cbor.encodeToByteArray(OfferDetails.serializer(), details)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "OFFER_DETAILS too large" }
        val key = controlKey(keys)
        return try {
            EncryptedOfferDetailsMessage(counter,
                CryptoEngine.encryptAesGcm(key, nonce(counter), plaintext, sessionId))
        } finally { key.fill(0); plaintext.fill(0) }
    }

    fun open(keys: SessionKeys, sessionId: ByteArray, message: EncryptedOfferDetailsMessage): OfferDetails {
        require(sessionId.size == 16)
        require(message.counter in 0..MAX_COUNTER && message.ciphertext.size in 17..MAX_CIPHERTEXT_BYTES) {
            "Invalid encrypted OFFER_DETAILS"
        }
        val key = controlKey(keys)
        var plaintext: ByteArray? = null
        return try {
            plaintext = CryptoEngine.decryptAesGcm(key, nonce(message.counter), message.ciphertext, sessionId)
            cbor.decodeFromByteArray(OfferDetails.serializer(), plaintext).also { it.validate() }
        } catch (_: Exception) {
            // Fixed text; neither credentials nor decrypted CBOR enter errors/logs.
            throw IllegalArgumentException("OFFER_DETAILS authentication failed")
        } finally { key.fill(0); plaintext?.fill(0) }
    }
}
