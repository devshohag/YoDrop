package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.RawFrame
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class T3NonceAndAadTest {

    @Test
    fun testSameChunkReRequestedGetsUniqueNonceAndDecrypts() {
        val nonceBase = ByteArray(12) { (it + 5).toByte() }
        val key = ByteArray(32) { 7 }
        val sessionId = ByteArray(16) { 3 }
        val fileId = "test-doc"
        val chunkIdx = 2
        val plaintext = "YoDrop Chunk Payload Content".encodeToByteArray()

        // 1st request: attempt = 0
        val nonce0 = NonceUtils.buildChunkNonce(nonceBase, chunkIdx, attemptCounter = 0)
        val aad0 = NonceUtils.buildAad(sessionId, fileId, chunkIdx)
        val cipher0 = CryptoEngine.encryptAesGcm(key, nonce0, plaintext, aad0)

        // 2nd request (re-requesting same chunk): attempt = 1
        val nonce1 = NonceUtils.buildChunkNonce(nonceBase, chunkIdx, attemptCounter = 1)
        val aad1 = NonceUtils.buildAad(sessionId, fileId, chunkIdx)
        val cipher1 = CryptoEngine.encryptAesGcm(key, nonce1, plaintext, aad1)

        // Nonces must be strictly distinct
        val noncesMatch = nonce0.contentEquals(nonce1)
        assertEquals(false, noncesMatch, "Nonces for consecutive attempts of same chunk must be distinct")

        // Both decrypt correctly with their respective attempt counter nonce
        val dec0 = CryptoEngine.decryptAesGcm(key, nonce0, cipher0, aad0)
        val dec1 = CryptoEngine.decryptAesGcm(key, nonce1, cipher1, aad1)
        assertTrue(plaintext.contentEquals(dec0))
        assertTrue(plaintext.contentEquals(dec1))
    }

    @Test
    fun testTamperedChunkIndexInAadFailsDecryption() {
        val nonceBase = ByteArray(12) { 9 }
        val key = ByteArray(32) { 1 }
        val sessionId = ByteArray(16) { 2 }
        val fileId = "file-abc"
        val realChunkIdx = 5
        val tamperedChunkIdx = 6
        val plaintext = "Tamper Proof Test".encodeToByteArray()

        val nonce = NonceUtils.buildChunkNonce(nonceBase, realChunkIdx, attemptCounter = 0)
        val senderAad = NonceUtils.buildAad(sessionId, fileId, realChunkIdx)
        val ciphertext = CryptoEngine.encryptAesGcm(key, nonce, plaintext, senderAad)

        // Attacker alters chunkIndex in header
        val receiverAadWithTamperedIndex = NonceUtils.buildAad(sessionId, fileId, tamperedChunkIdx)
        assertFailsWith<Exception> {
            CryptoEngine.decryptAesGcm(key, nonce, ciphertext, receiverAadWithTamperedIndex)
        }
    }

    @Test
    fun testTamperedFileIdInAadFailsDecryption() {
        val nonceBase = ByteArray(12) { 9 }
        val key = ByteArray(32) { 1 }
        val sessionId = ByteArray(16) { 2 }
        val realFileId = "file-1"
        val swappedFileId = "file-2"
        val chunkIdx = 0
        val plaintext = "File Swap Attack Prevention".encodeToByteArray()

        val nonce = NonceUtils.buildChunkNonce(nonceBase, chunkIdx, attemptCounter = 0)
        val senderAad = NonceUtils.buildAad(sessionId, realFileId, chunkIdx)
        val ciphertext = CryptoEngine.encryptAesGcm(key, nonce, plaintext, senderAad)

        val receiverAadWithSwappedFileId = NonceUtils.buildAad(sessionId, swappedFileId, chunkIdx)
        assertFailsWith<Exception> {
            CryptoEngine.decryptAesGcm(key, nonce, ciphertext, receiverAadWithSwappedFileId)
        }
    }
}
