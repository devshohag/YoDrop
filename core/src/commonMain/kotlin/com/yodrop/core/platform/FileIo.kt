package com.yodrop.core.platform

import com.yodrop.core.crypto.CryptoEngine

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
    suspend fun close()
}

class MemoryFileSource(
    override val fileId: String,
    private val data: ByteArray
) : FileSource {
    override val size: Long get() = data.size.toLong()

    override suspend fun readChunk(offset: Long, length: Int): ByteArray {
        val start = offset.toInt()
        val end = minOf(data.size, start + length)
        return data.copyOfRange(start, end)
    }

    override suspend fun close() {}
}

class MemoryFileSink(
    override val fileId: String,
    private val expectedSize: Int = 0
) : FileSink {
    private val buffer = java.io.ByteArrayOutputStream(expectedSize)

    override suspend fun writeChunk(offset: Long, data: ByteArray) {
        synchronized(buffer) {
            buffer.write(data)
        }
    }

    override suspend fun verifySha256(expectedSha256: ByteArray): Boolean {
        val bytes = buffer.toByteArray()
        val hash = CryptoEngine.sha256(bytes)
        return hash.contentEquals(expectedSha256)
    }

    override suspend fun commit() {}
    override suspend fun close() {}

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
