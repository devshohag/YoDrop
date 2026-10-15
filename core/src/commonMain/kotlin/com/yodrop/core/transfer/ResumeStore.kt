package com.yodrop.core.transfer

interface ResumeStore {
    suspend fun loadBitmap(sessionId: ByteArray, fileId: String, totalChunks: Int): ChunkBitmap
    suspend fun saveBitmap(sessionId: ByteArray, fileId: String, bitmap: ChunkBitmap)
    suspend fun clear(sessionId: ByteArray, fileId: String)
}

class InMemoryResumeStore : ResumeStore {
    private val store = mutableMapOf<String, ByteArray>()

    private fun key(sessionId: ByteArray, fileId: String): String {
        return sessionId.joinToString("") { "%02x".format(it) } + "_" + fileId
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
