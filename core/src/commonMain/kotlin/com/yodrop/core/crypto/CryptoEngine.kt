package com.yodrop.core.crypto

data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

data class SessionKeys(
    val senderToReceiverKey: ByteArray,
    val receiverToSenderKey: ByteArray,
    val nonceBase: ByteArray,
    val verifyCode: String
)

expect object CryptoEngine {
    fun randomBytes(length: Int): ByteArray
    fun generateX25519KeyPair(): KeyPair
    fun computeSharedSecret(myPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray
    fun deriveSessionKeys(sharedSecret: ByteArray, sessionId: ByteArray): SessionKeys
    fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray
    fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray
    fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray
}

object NonceUtils {
    fun build12ByteNonce(nonceBase: ByteArray, counter: Int): ByteArray {
        require(nonceBase.size >= 8) { "nonceBase must be at least 8 bytes" }
        val nonce = ByteArray(12)
        val copyLen = minOf(8, nonceBase.size)
        nonceBase.copyInto(nonce, 0, 0, copyLen)
        nonce[8] = ((counter shr 24) and 0xFF).toByte()
        nonce[9] = ((counter shr 16) and 0xFF).toByte()
        nonce[10] = ((counter shr 8) and 0xFF).toByte()
        nonce[11] = (counter and 0xFF).toByte()
        return nonce
    }

    fun buildChunkNonce(nonceBase: ByteArray, chunkIndex: Int, attemptCounter: Int): ByteArray {
        require(nonceBase.size >= 4) { "nonceBase must be at least 4 bytes" }
        val nonce = ByteArray(12)
        nonceBase.copyInto(nonce, 0, 0, 4)
        nonce[4] = ((chunkIndex shr 24) and 0xFF).toByte()
        nonce[5] = ((chunkIndex shr 16) and 0xFF).toByte()
        nonce[6] = ((chunkIndex shr 8) and 0xFF).toByte()
        nonce[7] = (chunkIndex and 0xFF).toByte()

        nonce[8] = ((attemptCounter shr 24) and 0xFF).toByte()
        nonce[9] = ((attemptCounter shr 16) and 0xFF).toByte()
        nonce[10] = ((attemptCounter shr 8) and 0xFF).toByte()
        nonce[11] = (attemptCounter and 0xFF).toByte()
        return nonce
    }

    fun buildAad(sessionId: ByteArray, fileId: String, chunkIndex: Int): ByteArray {
        val fidBytes = fileId.encodeToByteArray()
        val aad = ByteArray(sessionId.size + fidBytes.size + 4)
        sessionId.copyInto(aad, 0)
        fidBytes.copyInto(aad, sessionId.size)
        val off = sessionId.size + fidBytes.size
        aad[off] = ((chunkIndex shr 24) and 0xFF).toByte()
        aad[off + 1] = ((chunkIndex shr 16) and 0xFF).toByte()
        aad[off + 2] = ((chunkIndex shr 8) and 0xFF).toByte()
        aad[off + 3] = (chunkIndex and 0xFF).toByte()
        return aad
    }
}
