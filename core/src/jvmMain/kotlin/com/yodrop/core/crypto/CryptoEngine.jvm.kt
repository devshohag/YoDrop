package com.yodrop.core.crypto

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

actual object CryptoEngine {
    private val X509_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
    )
    private val PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20
    )

    actual fun generateX25519KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        val pubEncoded = kp.public.encoded
        val privEncoded = kp.private.encoded

        val rawPub = pubEncoded.copyOfRange(pubEncoded.size - 32, pubEncoded.size)
        val rawPriv = privEncoded.copyOfRange(privEncoded.size - 32, privEncoded.size)
        return KeyPair(rawPub, rawPriv)
    }

    actual fun computeSharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        val privDer = PKCS8_PREFIX + myPrivateKey
        val pubDer = X509_PREFIX + theirPublicKey

        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privDer))
        val pub = kf.generatePublic(X509EncodedKeySpec(pubDer))

        val ka = KeyAgreement.getInstance("X25519")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    actual fun deriveSessionKeys(sharedSecret: ByteArray, sessionId: ByteArray): SessionKeys {
        val prk = computeHmacSha256(sessionId, sharedSecret)
        val s2r = hkdfExpand(prk, "s2r".encodeToByteArray(), 32)
        val r2s = hkdfExpand(prk, "r2s".encodeToByteArray(), 32)
        val nonceBase = hkdfExpand(prk, "nonce".encodeToByteArray(), 8)

        val digest = sha256(sharedSecret)
        val codeInt = ((digest[0].toInt() and 0xFF) shl 24) or
                ((digest[1].toInt() and 0xFF) shl 16) or
                ((digest[2].toInt() and 0xFF) shl 8) or
                (digest[3].toInt() and 0xFF)
        val verifyCode = (kotlin.math.abs(codeInt) % 10000).toString().padStart(4, '0')

        return SessionKeys(
            senderToReceiverKey = s2r,
            receiverToSenderKey = r2s,
            nonceBase = nonceBase,
            verifyCode = verifyCode
        )
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

    actual fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    actual fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1

        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()
            val toCopy = kotlin.math.min(t.size, length - offset)
            t.copyInto(out, offset, 0, toCopy)
            offset += toCopy
            counter++
        }
        return out
    }
}
