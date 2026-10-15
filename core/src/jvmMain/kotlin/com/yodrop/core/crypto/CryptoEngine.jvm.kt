package com.yodrop.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

actual object CryptoEngine {
    private val secureRandom = SecureRandom()

    actual fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    actual fun generateX25519KeyPair(): KeyPair {
        val priv = randomBytes(32)
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127).toByte()
        priv[31] = (priv[31].toInt() or 64).toByte()

        val basePoint = ByteArray(32)
        basePoint[0] = 9
        val pub = rfc7748ScalarMult(priv, basePoint)
        return KeyPair(publicKey = pub, privateKey = priv)
    }

    actual fun computeSharedSecret(myPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(myPrivateKey.size == 32 && peerPublicKey.size == 32) { "Keys must be 32 bytes" }
        return rfc7748ScalarMult(myPrivateKey, peerPublicKey)
    }

    actual fun deriveSessionKeys(sharedSecret: ByteArray, sessionId: ByteArray): SessionKeys {
        val s2rKey = hkdfExpand(sharedSecret, sessionId, "YoDrop-S2R-AES-GCM".encodeToByteArray(), 32)
        val r2sKey = hkdfExpand(sharedSecret, sessionId, "YoDrop-R2S-AES-GCM".encodeToByteArray(), 32)
        val nonceBase = hkdfExpand(sharedSecret, sessionId, "YoDrop-Nonce-Base".encodeToByteArray(), 12)
        val authVerify = hkdfExpand(sharedSecret, sessionId, "YoDrop-Verify-Code".encodeToByteArray(), 4)

        val codeInt = ((authVerify[0].toInt() and 0xFF) shl 24) or
                ((authVerify[1].toInt() and 0xFF) shl 16) or
                ((authVerify[2].toInt() and 0xFF) shl 8) or
                (authVerify[3].toInt() and 0xFF)
        val codePositive = codeInt and 0x7FFFFFFF
        val verifyCode = (codePositive % 10000).toString().padStart(4, '0')

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

    private fun rfc7748ScalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        return try {
            val kf = java.security.KeyFactory.getInstance("XDH")
            val privKeySpec = java.security.spec.PKCS8EncodedKeySpec(createX25519Pkcs8(scalar))
            val pubKeySpec = java.security.spec.X509EncodedKeySpec(createX25519X509(point))
            val priv = kf.generatePrivate(privKeySpec)
            val pub = kf.generatePublic(pubKeySpec)
            val ka = javax.crypto.KeyAgreement.getInstance("XDH")
            ka.init(priv)
            ka.doPhase(pub, true)
            ka.generateSecret()
        } catch (_: Exception) {
            val clamped = scalar.copyOf()
            clamped[0] = (clamped[0].toInt() and 248).toByte()
            clamped[31] = (clamped[31].toInt() and 127).toByte()
            clamped[31] = (clamped[31].toInt() or 64).toByte()
            RFC7748Helper.scalarMult(clamped, point)
        }
    }

    private fun createX25519Pkcs8(priv: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e,
            0x04, 0x22, 0x04, 0x20
        )
        return prefix + priv
    }

    private fun createX25519X509(pub: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
        )
        return prefix + pub
    }
}

private object RFC7748Helper {
    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        val x1 = decodeLittleEndian(u)
        var x2 = java.math.BigInteger.ONE
        var z2 = java.math.BigInteger.ZERO
        var x3 = x1
        var z3 = java.math.BigInteger.ONE
        var swap = 0
        val p = java.math.BigInteger.valueOf(2).pow(255).subtract(java.math.BigInteger.valueOf(19))
        val a24 = java.math.BigInteger.valueOf(121665)

        for (t in 254 downTo 0) {
            val kt = (k[t / 8].toInt() ushr (t % 8)) and 1
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = x2.add(z2).mod(p)
            val aa = a.multiply(a).mod(p)
            val b = x2.subtract(z2).mod(p)
            val bb = b.multiply(b).mod(p)
            val e = aa.subtract(bb).mod(p)
            val c = x3.add(z3).mod(p)
            val d = x3.subtract(z3).mod(p)
            val da = d.multiply(a).mod(p)
            val cb = c.multiply(b).mod(p)
            x3 = da.add(cb).multiply(da.add(cb)).mod(p)
            z3 = x1.multiply(da.subtract(cb).multiply(da.subtract(cb))).mod(p)
            x2 = aa.multiply(bb).mod(p)
            z2 = e.multiply(aa.add(a24.multiply(e))).mod(p)
        }

        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }

        val result = x2.multiply(z2.modInverse(p)).mod(p)
        return encodeLittleEndian(result)
    }

    private fun decodeLittleEndian(b: ByteArray): java.math.BigInteger {
        val rev = b.reversedArray()
        return java.math.BigInteger(1, rev)
    }

    private fun encodeLittleEndian(n: java.math.BigInteger): ByteArray {
        val raw = n.toByteArray()
        val out = ByteArray(32)
        var outIdx = 0
        for (i in raw.indices.reversed()) {
            if (outIdx < 32) out[outIdx++] = raw[i]
        }
        return out
    }
}
