package com.yodrop.core.platform

import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.ResumeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.ConcurrentHashMap

class DiskResumeStore(private val storageDir: File) : ResumeStore {
    // Different store instances in this process must share the same reader/writer gate.
    private val mutex = directoryLocks.computeIfAbsent(storageDir.canonicalPath) { Mutex() }

    private companion object {
        val directoryLocks = ConcurrentHashMap<String, Mutex>()
    }

    init {
        check(storageDir.isDirectory || storageDir.mkdirs()) { "Cannot create resume directory" }
    }

    private fun getFile(sessionId: ByteArray, fileId: String): File {
        val sHex = sessionId.joinToString("") { "%02x".format(it) }
        val safeFileId = fileId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(storageDir, ".yodrop-resume-${sHex}-${safeFileId}.bin")
    }

    override suspend fun loadBitmap(
        sessionId: ByteArray, fileId: String, totalChunks: Int
    ): ChunkBitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            val empty = ChunkBitmap(totalChunks)
            val expectedBytes = ((totalChunks + 63) / 64) * 8L
            val file = getFile(sessionId, fileId)
            try {
                // Read size and bytes through the same NIO handle, under the shared gate.
                // This avoids a separate path check followed by a racing legacy stream open.
                FileChannel.open(file.toPath(), READ).use { channel ->
                    if (channel.size() != expectedBytes) return@use empty
                    val buffer = ByteBuffer.allocate(expectedBytes.toInt())
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) < 0) return@use empty
                    }
                    ChunkBitmap.fromByteArray(totalChunks, buffer.array())
                }
            } catch (_: NoSuchFileException) {
                empty
            }
        }
    }

    override suspend fun saveBitmap(
        sessionId: ByteArray, fileId: String, bitmap: ChunkBitmap
    ) {
        val bytes = bitmap.toByteArray()
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                val destination = getFile(sessionId, fileId)
                val temporary = File.createTempFile(".yodrop-resume-", ".tmp", storageDir)
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(bytes)
                        output.channel.force(true)
                    }
                    // Same directory/filesystem. Failure retains the old record;
                    // never fall back to a non-atomic overwrite.
                    Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    forceDirectory()
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    override suspend fun clear(sessionId: ByteArray, fileId: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                val file = getFile(sessionId, fileId)
                check(!file.exists() || file.delete()) { "Cannot delete resume record" }
                forceDirectory()
            }
        }

    private fun forceDirectory() {
        // Directory handles/force are unsupported on some platforms (notably Windows).
        // The data file is always forced and replacement is always atomic.
        runCatching { FileChannel.open(storageDir.toPath(), READ).use { it.force(true) } }
        Unit
    }
}
