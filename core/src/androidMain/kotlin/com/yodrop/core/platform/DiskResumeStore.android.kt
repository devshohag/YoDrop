package com.yodrop.core.platform

import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.ResumeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DiskResumeStore(private val storageDir: File) : ResumeStore {
    init {
        storageDir.mkdirs()
    }

    private fun getFile(sessionId: ByteArray, fileId: String): File {
        val sHex = sessionId.joinToString("") { "%02x".format(it) }
        val safeFileId = fileId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(storageDir, ".yodrop-resume-${sHex}-${safeFileId}.bin")
    }

    override suspend fun loadBitmap(sessionId: ByteArray, fileId: String, totalChunks: Int): ChunkBitmap = withContext(Dispatchers.IO) {
        val f = getFile(sessionId, fileId)
        if (f.exists() && f.length() > 0) {
            val bytes = f.readBytes()
            ChunkBitmap.fromByteArray(totalChunks, bytes)
        } else {
            ChunkBitmap(totalChunks)
        }
    }

    override suspend fun saveBitmap(sessionId: ByteArray, fileId: String, bitmap: ChunkBitmap) = withContext(Dispatchers.IO) {
        val f = getFile(sessionId, fileId)
        f.writeBytes(bitmap.toByteArray())
    }

    override suspend fun clear(sessionId: ByteArray, fileId: String) = withContext(Dispatchers.IO) {
        val f = getFile(sessionId, fileId)
        if (f.exists()) f.delete()
        Unit
    }
}
