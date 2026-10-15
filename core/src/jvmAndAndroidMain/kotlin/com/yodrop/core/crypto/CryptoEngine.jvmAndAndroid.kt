package com.yodrop.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

actual object CryptoEngine {
    actual fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    private val secureRandom = SecureRandom()

    actual fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    actual fun generateX25519KeyPair(): KeyPair {
        val privateKey = ByteArray(32)
        org.bouncycastle.math.ec.rfc7748.X25519.generatePrivateKey(
            secureRandom, privateKey
        )
        val publicKey = ByteArray(32)
        org.bouncycastle.math.ec.rfc7748.X25519.generatePublicKey(
            privateKey, 0, publicKey, 0
        )
        return KeyPair(publicKey = publicKey, privateKey = privateKey)
    }

    actual fun computeSharedSecret(
        myPrivateKey: ByteArray,
        peerPublicKey: ByteArray
    ): ByteArray {
        require(myPrivateKey.size == 32) {
            "X25519 private key must be 32 bytes"
        }
        require(peerPublicKey.size == 32) {
            "X25519 public key must be 32 bytes"
        }

        // RFC 7748: ignore bit 255 without modifying the caller's array.
        val peer = peerPublicKey.copyOf()
        peer[31] = (peer[31].toInt() and 0x7f).toByte()

        val secret = ByteArray(32)
        val accepted =
            org.bouncycastle.math.ec.rfc7748.X25519.calculateAgreement(
                myPrivateKey, 0, peer, 0, secret, 0
            )

        var accumulated = 0
        for (value in secret) {
            accumulated = accumulated or (value.toInt() and 0xff)
        }

        if (!accepted || accumulated == 0) {
            secret.fill(0)
            throw IllegalArgumentException(
                "X25519 rejected a low-order public key"
            )
        }
        return secret
    }
    actual fun deriveSessionKeys(sharedSecret: ByteArray, sessionId: ByteArray): SessionKeys {
        val s2rKey = hkdfExpand(sharedSecret, sessionId, "YoDrop-S2R-AES-GCM".encodeToByteArray(), 32)
        val r2sKey = hkdfExpand(sharedSecret, sessionId, "YoDrop-R2S-AES-GCM".encodeToByteArray(), 32)
        val nonceBase = hkdfExpand(sharedSecret, sessionId, "YoDrop-Nonce-Base".encodeToByteArray(), 12)
        val verifyCode = VerifyCode.fromHash(sha256(sharedSecret))

        return SessionKeys(
            senderToReceiverKey = s2rKey,
            receiverToSenderKey = r2sKey,
            nonceBase = nonceBase,
            verifyCode = verifyCode
        )
    }

    actual fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    actual fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    actual fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    actual fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    private fun hkdfExpand(prk: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val actualPrk = mac.doFinal(prk)

        mac.init(SecretKeySpec(actualPrk, "HmacSHA256"))
        val input = info + byteArrayOf(0x01)
        val t1 = mac.doFinal(input)
        return t1.copyOf(length)
    }

}
