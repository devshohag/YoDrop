package com.yodrop.core.crypto

import com.yodrop.core.protocol.EncryptedLinkInfoMessage
import com.yodrop.core.protocol.LinkInfoMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/** Dedicated S2R control key, fixed nonce domain + u32 counter, AAD = active sessionId. */
@OptIn(ExperimentalSerializationApi::class)
object LinkInfoCipher {
    const val MAX_COUNTER = 0xffff_ffffL
    const val MAX_PLAINTEXT_BYTES = 2048
    const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 16
    private val cbor = Cbor { ignoreUnknownKeys = true }
    private val info = "YoDrop-BLE-S2R-LINK_INFO-v1".encodeToByteArray()

    private fun controlKey(keys: SessionKeys): ByteArray {
        require(keys.senderToReceiverKey.size == 32)
        // RFC 5869 Expand, one block, with the already-derived directional key as PRK.
        return CryptoEngine.computeHmacSha256(keys.senderToReceiverKey, info + byteArrayOf(1))
    }
    private fun nonce(counter: Long): ByteArray {
        require(counter in 0..MAX_COUNTER) { "LINK_INFO nonce counter exhausted" }
        return "LINKINFO".encodeToByteArray() + byteArrayOf(
            (counter ushr 24).toByte(), (counter ushr 16).toByte(),
            (counter ushr 8).toByte(), counter.toByte())
    }

    fun seal(keys: SessionKeys, sessionId: ByteArray, counter: Long, link: LinkInfoMessage): EncryptedLinkInfoMessage {
        require(sessionId.size == 16)
        link.validate()
        val plaintext = cbor.encodeToByteArray(LinkInfoMessage.serializer(), link)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "LINK_INFO too large" }
        val key = controlKey(keys)
        return try {
            EncryptedLinkInfoMessage(counter,
                CryptoEngine.encryptAesGcm(key, nonce(counter), plaintext, sessionId))
        } finally { key.fill(0); plaintext.fill(0) }
    }

    fun open(keys: SessionKeys, sessionId: ByteArray, message: EncryptedLinkInfoMessage): LinkInfoMessage {
        require(sessionId.size == 16)
        require(message.counter in 0..MAX_COUNTER && message.ciphertext.size in 17..MAX_CIPHERTEXT_BYTES) {
            "Invalid encrypted LINK_INFO"
        }
        val key = controlKey(keys)
        var plaintext: ByteArray? = null
        return try {
            plaintext = CryptoEngine.decryptAesGcm(key, nonce(message.counter), message.ciphertext, sessionId)
            cbor.decodeFromByteArray(LinkInfoMessage.serializer(), plaintext).also { it.validate() }
        } catch (_: Exception) {
            // Fixed text; neither credentials nor decrypted CBOR enter errors/logs.
            throw IllegalArgumentException("LINK_INFO authentication failed")
        } finally { key.fill(0); plaintext?.fill(0) }
    }
}
