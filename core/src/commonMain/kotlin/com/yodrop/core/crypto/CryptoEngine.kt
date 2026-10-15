package com.yodrop.core.crypto

class KeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray
)

class SessionKeys(
    val senderToReceiverKey: ByteArray,
    val receiverToSenderKey: ByteArray,
    val nonceBase: ByteArray,
    val verifyCode: String
)

object NonceUtils {
    fun build12ByteNonce(nonceBase: ByteArray, counter: Int): ByteArray {
        require(nonceBase.size == 8) { "nonceBase must be exactly 8 bytes" }
        val nonce = ByteArray(12)
        nonceBase.copyInto(nonce, 0, 0, 8)
        nonce[8] = ((counter shr 24) and 0xFF).toByte()
        nonce[9] = ((counter shr 16) and 0xFF).toByte()
        nonce[10] = ((counter shr 8) and 0xFF).toByte()
        nonce[11] = (counter and 0xFF).toByte()
        return nonce
    }
}

expect object CryptoEngine {
    fun generateX25519KeyPair(): KeyPair
    fun computeSharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray
    fun deriveSessionKeys(sharedSecret: ByteArray, sessionId: ByteArray): SessionKeys
    fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray
    fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray
}
