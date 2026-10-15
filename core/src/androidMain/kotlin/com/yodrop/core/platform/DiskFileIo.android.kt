package com.yodrop.core.platform

import com.yodrop.core.crypto.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class DiskFileSource(
    override val fileId: String,
    private val file: File
) : FileSource {
    override val size: Long get() = file.length()
    private val raf: RandomAccessFile by lazy { RandomAccessFile(file, "r") }

    override suspend fun readChunk(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(length)
        synchronized(raf) {
            raf.seek(offset)
            var totalRead = 0
            while (totalRead < length) {
                val read = raf.read(buffer, totalRead, length - totalRead)
                if (read == -1) break
                totalRead += read
            }
            if (totalRead < length) buffer.copyOf(totalRead) else buffer
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { raf.close() }
        Unit
    }
}

class DiskFileSink(
    override val fileId: String,
    private val targetFile: File
) : FileSink {
    private val partFile = File(targetFile.parentFile ?: File("."), "${targetFile.name}.part")
    private val raf: RandomAccessFile by lazy {
        partFile.parentFile?.mkdirs()
        RandomAccessFile(partFile, "rw")
    }

    override suspend fun writeChunk(offset: Long, data: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(raf) {
            raf.seek(offset)
            raf.write(data)
        }
    }

    override suspend fun verifySha256(expectedSha256: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        synchronized(raf) {
            raf.seek(0)
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (raf.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val computed = digest.digest()
        computed.contentEquals(expectedSha256)
    }

    override suspend fun commit(): Unit = withContext(Dispatchers.IO) {
        synchronized(raf) {
            runCatching { raf.close() }
        }
        if (targetFile.exists()) targetFile.delete()
        val success = partFile.renameTo(targetFile)
        if (!success) {
            throw IllegalStateException("Failed to commit file from ${partFile.name} to${targetFile.name}")
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { raf.close() }
        Unit
    }
}
