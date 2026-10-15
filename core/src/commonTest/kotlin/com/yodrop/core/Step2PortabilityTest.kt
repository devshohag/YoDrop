package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.MemoryFileSink
import com.yodrop.core.platform.MemoryFileSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Step2PortabilityTest {
    @Test
    fun comparisonHandlesEqualDifferentAndUnequalLengths() {
        assertTrue(CryptoEngine.constantTimeEquals(
            byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)
        ))
        assertFalse(CryptoEngine.constantTimeEquals(
            byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)
        ))
        assertFalse(CryptoEngine.constantTimeEquals(
            byteArrayOf(1, 2), byteArrayOf(1, 2, 0)
        ))
        assertTrue(CryptoEngine.constantTimeEquals(
            byteArrayOf(), byteArrayOf()
        ))
    }

    @Test
    fun sinkSupportsOutOfOrderWritesAndOverwrite() = runTest {
        val sink = MemoryFileSink("file", 1)
        sink.writeChunk(3, byteArrayOf(4, 5, 6))
        sink.writeChunk(0, byteArrayOf(1, 2, 3))
        sink.writeChunk(1, byteArrayOf(9))

        val expected = byteArrayOf(1, 9, 3, 4, 5, 6)
        assertContentEquals(expected, sink.toByteArray())
        assertTrue(sink.verifySha256(CryptoEngine.sha256(expected)))
        assertFalse(sink.verifySha256(
            CryptoEngine.sha256(byteArrayOf(0))
        ))
    }

    @Test
    fun sinkSupportsConcurrentNonOverlappingWrites() = runTest {
        val sink = MemoryFileSink("file")
        val jobs = (0 until 32).map { index ->
            launch {
                sink.writeChunk(
                    index.toLong() * 8,
                    ByteArray(8) { index.toByte() }
                )
            }
        }
        jobs.forEach { it.join() }

        assertContentEquals(
            ByteArray(256) { (it / 8).toByte() },
            sink.toByteArray()
        )
    }

    @Test
    fun sourceReturnsOnlyAvailableBytesAtEnd() = runTest {
        val source = MemoryFileSource("file", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(3), source.readChunk(2, 8))
        assertContentEquals(byteArrayOf(), source.readChunk(3, 8))
    }
}