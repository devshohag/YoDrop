package com.yodrop.core.trust

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

actual object IdentitySignatures {
    actual fun verify(publicKey: ByteArray, transcript: ByteArray, signature: ByteArray): Boolean = try {
        IdentityKeys.validate(publicKey)
        if (signature.size !in 8..72) false else {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            publicKey.contentEquals(key.encoded) && Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(transcript); verify(signature)
            }
        }
    } catch (_: Exception) { false }
}
