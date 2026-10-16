package com.yodrop.core.platform

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
    /** Make preceding writes durable before persisting received bits. */
    suspend fun force() {}
    /** Unknown sink implementations conservatively reject stored progress. */
    suspend fun hasDataThrough(length: Long): Boolean = length == 0L
    suspend fun commit(): Unit
    /** Discard uncommitted partial data on a terminal session. */
    suspend fun discard() { close() }
    suspend fun close()
}
