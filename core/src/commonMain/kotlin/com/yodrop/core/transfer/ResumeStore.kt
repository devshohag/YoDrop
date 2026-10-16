package com.yodrop.core.transfer

interface ResumeStore {
    suspend fun loadBitmap(sessionId: ByteArray, fileId: String, totalChunks: Int): ChunkBitmap
    suspend fun saveBitmap(sessionId: ByteArray, fileId: String, bitmap: ChunkBitmap)
    suspend fun clear(sessionId: ByteArray, fileId: String)
}

class InMemoryResumeStore : ResumeStore {
    private val store = mutableMapOf<String, ByteArray>()

    private fun key(sessionId: ByteArray, fileId: String): String {
        val hex = "0123456789abcdef"
        return buildString(sessionId.size * 2 + 1 + fileId.length) {
            for (byte in sessionId) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4]); append(hex[value and 15])
            }
            append('_'); append(fileId)
        }
    }

    override suspend fun loadBitmap(sessionId: ByteArray, fileId: String, totalChunks: Int): ChunkBitmap {
        val bytes = store[key(sessionId, fileId)]
        return if (bytes != null) ChunkBitmap.fromByteArray(totalChunks, bytes) else ChunkBitmap(totalChunks)
    }

    override suspend fun saveBitmap(sessionId: ByteArray, fileId: String, bitmap: ChunkBitmap) {
        store[key(sessionId, fileId)] = bitmap.toByteArray()
    }

    override suspend fun clear(sessionId: ByteArray, fileId: String) {
        store.remove(key(sessionId, fileId))
    }
}
