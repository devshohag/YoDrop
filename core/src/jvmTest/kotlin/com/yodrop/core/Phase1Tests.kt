package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.FrameType
import com.yodrop.core.protocol.ManifestMessage
import com.yodrop.core.protocol.ProtocolCodec
import com.yodrop.core.protocol.RawFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase1Tests {

    @Test
    fun testX25519EcdhAndSessionDerivation() {
        val alice = CryptoEngine.generateX25519KeyPair()
        val bob = CryptoEngine.generateX25519KeyPair()

        val aliceSecret = CryptoEngine.computeSharedSecret(alice.privateKey, bob.publicKey)
        val bobSecret = CryptoEngine.computeSharedSecret(bob.privateKey, alice.publicKey)

        assertTrue(aliceSecret.contentEquals(bobSecret), "Shared secret must match on both sides")

        val sessionId = CryptoEngine.randomBytes(16)
        val aliceKeys = CryptoEngine.deriveSessionKeys(aliceSecret, sessionId)
        val bobKeys = CryptoEngine.deriveSessionKeys(bobSecret, sessionId)

        assertTrue(aliceKeys.senderToReceiverKey.contentEquals(bobKeys.senderToReceiverKey))
        assertTrue(aliceKeys.receiverToSenderKey.contentEquals(bobKeys.receiverToSenderKey))
        assertTrue(aliceKeys.nonceBase.contentEquals(bobKeys.nonceBase))
        assertEquals(aliceKeys.verifyCode, bobKeys.verifyCode)
    }

    @Test
    fun testAesGcmEncryptionDecryptionWithNonce() {
        val key = CryptoEngine.randomBytes(32)
        val nonceBase = CryptoEngine.randomBytes(12)
        val plaintext = "YoDrop High Speed Encrypted Stream".encodeToByteArray()

        val nonce = NonceUtils.build12ByteNonce(nonceBase, 1)
        val ciphertext = CryptoEngine.encryptAesGcm(key, nonce, plaintext)
        val decrypted = CryptoEngine.decryptAesGcm(key, nonce, ciphertext)

        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test
    fun testRawFrameEncodeDecode() {
        val payload = "Hello Frame".encodeToByteArray()
        val frame = RawFrame(FrameType.MANIFEST, payload)
        val encoded = frame.encode()

        val decoded = RawFrame.decode(encoded)
        assertEquals(FrameType.MANIFEST, decoded.type)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun testManifestSerialization() {
        val sha = CryptoEngine.sha256("content".encodeToByteArray())
        val meta = FileMeta("f-1", "doc.pdf", 1024L, "application/pdf", sha)
        val manifest = ManifestMessage(listOf(meta))

        val frame = ProtocolCodec.encodeToFrame(FrameType.MANIFEST, manifest)
        val decoded = ProtocolCodec.decodePayload<ManifestMessage>(frame)

        assertEquals(1, decoded.files.size)
        assertEquals("doc.pdf", decoded.files[0].name)
        assertTrue(sha.contentEquals(decoded.files[0].sha256))
    }
}
