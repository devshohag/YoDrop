package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.protocol.*
import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.ChunkPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase1Tests {

    @Test
    fun testRawFrameEncodeDecode() {
        val payload = "YoDrop Protocol Frame".encodeToByteArray()
        val frame = RawFrame(FrameType.AUTH, payload)
        val encoded = frame.encode()

        val decoded = RawFrame.decode(encoded)
        assertEquals(FrameType.AUTH, decoded.type)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun testManifestSerialization() {
        val files = listOf(
            FileMeta(id = "f1", name = "video.mp4", size = 1024 * 1024 * 500, mime = "video/mp4", sha256 = ByteArray(32) { 0x1A }),
            FileMeta(id = "f2", name = "photo.jpg", size = 1024 * 1024 * 5, mime = "image/jpeg", sha256 = ByteArray(32) { 0x2B })
        )
        val manifest = ManifestMessage(files = files)

        val frame = ProtocolCodec.encodeToFrame(FrameType.MANIFEST, manifest)
        val decoded = ProtocolCodec.decodePayload<ManifestMessage>(frame)

        assertEquals(2, decoded.files.size)
        assertEquals("video.mp4", decoded.files[0].name)
        assertEquals(524288000L, decoded.files[0].size)
    }

    @Test
    fun testX25519EcdhAndSessionDerivation() {
        val alice = CryptoEngine.generateX25519KeyPair()
        val bob = CryptoEngine.generateX25519KeyPair()

        val aliceSecret = CryptoEngine.computeSharedSecret(alice.privateKey, bob.publicKey)
        val bobSecret = CryptoEngine.computeSharedSecret(bob.privateKey, alice.publicKey)

        assertTrue(aliceSecret.contentEquals(bobSecret), "Shared secrets must match")

        val sessionId = ByteArray(16) { 0x42 }
        val aliceSession = CryptoEngine.deriveSessionKeys(aliceSecret, sessionId)
        val bobSession = CryptoEngine.deriveSessionKeys(bobSecret, sessionId)

        assertEquals(aliceSession.verifyCode, bobSession.verifyCode)
        assertTrue(aliceSession.senderToReceiverKey.contentEquals(bobSession.senderToReceiverKey))
        assertTrue(aliceSession.receiverToSenderKey.contentEquals(bobSession.receiverToSenderKey))
        assertTrue(aliceSession.nonceBase.contentEquals(bobSession.nonceBase))
    }

    @Test
    fun testAesGcmEncryptionDecryptionWithNonce() {
        val key = ByteArray(32) { 0x07 }
        val nonceBase = ByteArray(8) { 0x02 }
        val nonce = NonceUtils.build12ByteNonce(nonceBase, counter = 1042)
        val plaintext = "Encrypted chunk data stream test".encodeToByteArray()
        val aad = "Header_Chunk_04".encodeToByteArray()

        val ciphertext = CryptoEngine.encryptAesGcm(key, nonce, plaintext, aad)
        val decrypted = CryptoEngine.decryptAesGcm(key, nonce, ciphertext, aad)

        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test
    fun testOneGbTransferWithForcedDisconnectAndResume() {
        val oneGb = 1024L * 1024L * 1024L
        val chunkSize = ChunkPlanner.DEFAULT_CHUNK_SIZE
        val totalChunks = ChunkPlanner.calculateTotalChunks(oneGb, chunkSize) // 256 chunks

        assertEquals(256, totalChunks)

        val receiverBitmap = ChunkBitmap(totalChunks)

        // 1. Simulate transfer of first 100 chunks
        for (i in 0 until 100) {
            receiverBitmap.markReceived(i)
        }
        assertFalse(receiverBitmap.isComplete())

        // 2. Disconnect & persist
        val savedBytes = receiverBitmap.toByteArray()

        // 3. Reconnect & restore
        val restoredBitmap = ChunkBitmap.fromByteArray(totalChunks, savedBytes)
        for (i in 0 until 100) {
            assertTrue(restoredBitmap.isReceived(i))
        }

        // 4. Request missing chunks in batches of 16
        val missingBatch = restoredBitmap.getMissingChunks(batchSize = 16)
        assertEquals(16, missingBatch.size)
        assertEquals(100, missingBatch.first())
        assertEquals(115, missingBatch.last())

        // 5. Complete all remaining chunks
        for (i in 100 until totalChunks) {
            restoredBitmap.markReceived(i)
        }

        assertTrue(restoredBitmap.isComplete())
        assertTrue(restoredBitmap.getMissingChunks().isEmpty())
    }
}
