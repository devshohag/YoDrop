package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.InMemoryResumeStore
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class Step20AuditTest {
    @Test fun derivedNoncePrefixIsExactlyEightBytes() {
        val keys = CryptoEngine.deriveSessionKeys(ByteArray(32) { 7 }, ByteArray(16) { 3 })
        assertEquals(8, keys.nonceBase.size)
        assertEquals(32, keys.senderToReceiverKey.size)
        assertEquals(32, keys.receiverToSenderKey.size)
    }

    @Test fun chunkNonceUsesTheSpecPrefixAndGlobalCounterVector() {
        val prefix = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7f)
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7f, 1, 2, 3, 4),
            NonceUtils.buildChunkNonce(prefix, 999, 0x01020304))
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7f, 0, 0, 0, 0),
            NonceUtils.buildChunkNonce(prefix, 0, 0))
    }

    @Test fun allEightPrefixBytesParticipateAndRetryCounterChangesNonce() {
        val prefix = ByteArray(8) { 5 }
        val original = NonceUtils.buildChunkNonce(prefix, 0, 1)
        for (i in prefix.indices) {
            val changed = prefix.copyOf().also { it[i] = 6 }
            assertFalse(original.contentEquals(NonceUtils.buildChunkNonce(changed, 0, 1)))
        }
        assertFalse(original.contentEquals(NonceUtils.buildChunkNonce(prefix, 0, 2)))
        // AAD binds file/index. Nonce uniqueness depends on the existing global allocation, not index.
        assertContentEquals(original, NonceUtils.buildChunkNonce(prefix, 123, 1))
        assertFalse(NonceUtils.buildAad(ByteArray(16), "f", 0).contentEquals(NonceUtils.buildAad(ByteArray(16), "f", 123)))
    }

    @Test fun malformedNonceInputsAreRejectedBeforeEncryption() {
        assertFailsWith<IllegalArgumentException> { NonceUtils.buildChunkNonce(ByteArray(7), 0, 0) }
        assertFailsWith<IllegalArgumentException> { NonceUtils.buildChunkNonce(ByteArray(8), -1, 0) }
        assertFailsWith<IllegalArgumentException> { NonceUtils.buildChunkNonce(ByteArray(8), 0, -1) }
    }

    @Test fun portableResumeKeysKeepSignedBytesSessionsAndFilesDistinct() = runTest {
        val store = InMemoryResumeStore()
        val a = byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte())
        val b = a.copyOf().also { it[0] = 1 }
        val bitmap = ChunkBitmap(4).apply { markReceived(2) }
        store.saveBitmap(a, "a_b", bitmap)
        assertEquals(listOf(0, 1, 3), store.loadBitmap(a, "a_b", 4).getMissingChunks())
        assertEquals(listOf(0, 1, 2, 3), store.loadBitmap(b, "a_b", 4).getMissingChunks())
        assertEquals(listOf(0, 1, 2, 3), store.loadBitmap(a, "a", 4).getMissingChunks())
        store.clear(a, "a_b")
        assertEquals(listOf(0, 1, 2, 3), store.loadBitmap(a, "a_b", 4).getMissingChunks())
    }
}
