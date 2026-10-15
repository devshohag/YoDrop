package com.yodrop.core.platform

import com.yodrop.core.crypto.CryptoEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface FileSource {
    val fileId: String
    val size: Long
    suspend fun readChunk(offset: Long, length: Int): ByteArray
    suspend fun close()
}

interface FileSink {
    val fileId: String
    suspend fun writeChunk(offset: Long, data: ByteArray)
    suspend fun verifySha256(expectedSha256: ByteArray): Boolean
    suspend fun commit(): Unit
    /** Discard uncommitted partial data on a terminal session. */
    suspend fun discard() { close() }
    suspend fun close()
}

class MemoryFileSource(
    override val fileId: String,
    private val data: ByteArray
) : FileSource {
    override val size: Long get() = data.size.toLong()

    override suspend fun readChunk(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset <= size) { "Invalid offset" }
        require(length >= 0) { "Invalid length" }
        val start = offset.toInt()
        val count = minOf(length, data.size - start)
        return data.copyOfRange(start, start + count)
    }

    override suspend fun close() {}
}

class MemoryFileSink(
    override val fileId: String,
    expectedSize: Int = 0
) : FileSink {
    private val mutex = Mutex()
    private var buffer = ByteArray(expectedSize.also {
        require(it >= 0) { "Invalid expected size" }
    })
    private var writtenSize = 0

    override suspend fun writeChunk(offset: Long, data: ByteArray) {
        require(offset >= 0 && offset <= Int.MAX_VALUE.toLong()) {
            "Invalid offset"
        }
        val endLong = offset + data.size.toLong()
        require(endLong <= Int.MAX_VALUE.toLong()) {
            "Memory sink size exceeds Int range"
        }

        mutex.withLock {
            val start = offset.toInt()
            val end = endLong.toInt()

            if (end > buffer.size) {
                val doubled = minOf(
                    Int.MAX_VALUE.toLong(),
                    maxOf(1L, buffer.size.toLong()) * 2
                ).toInt()
                buffer = buffer.copyOf(maxOf(end, doubled))
            }

            data.copyInto(buffer, start)
            writtenSize = maxOf(writtenSize, end)
        }
    }

    override suspend fun verifySha256(expectedSha256: ByteArray): Boolean {
        val snapshot = toByteArray()
        return CryptoEngine.constantTimeEquals(
            CryptoEngine.sha256(snapshot),
            expectedSha256
        )
    }

    override suspend fun discard() {
        mutex.withLock {
            buffer = ByteArray(0)
            writtenSize = 0
        }
    }

    override suspend fun commit() {}
    override suspend fun close() {}

    suspend fun toByteArray(): ByteArray = mutex.withLock {
        buffer.copyOf(writtenSize)
    }
}