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
    private var committedTarget: File? = null
    val committedFile: File
        get() = checkNotNull(committedTarget) { "File has not been committed" }

    private val partFile = File(targetFile.parentFile ?: File("."), "${targetFile.name}.part")
    private val fileLock = Any()
    private var handle: RandomAccessFile? = null
    private val raf: RandomAccessFile
        get() = handle ?: run {
            partFile.parentFile?.mkdirs()
            RandomAccessFile(partFile, "rw").also { handle = it }
        }

    override suspend fun writeChunk(offset: Long, data: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            raf.seek(offset)
            raf.write(data)
        }
    }

    override suspend fun force() = withContext(Dispatchers.IO) {
        synchronized(fileLock) { raf.channel.force(true) }
    }

    override suspend fun hasDataThrough(length: Long): Boolean = withContext(Dispatchers.IO) {
        require(length >= 0)
        synchronized(fileLock) {
            val valid = length == 0L || (partFile.isFile && partFile.length() >= length)
            if (!valid || !partFile.exists()) {
                // A removed/replaced path must not leave writes targeting an orphaned handle.
                handle?.close()
                handle = null
            }
            valid
        }
    }

    override suspend fun verifySha256(expectedSha256: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        synchronized(fileLock) {
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
        synchronized(fileLock) {
            if (committedTarget != null) return@synchronized
            raf.channel.force(true)
            raf.close()
            handle = null

            val parent = targetFile.parentFile ?: File(".")
            val dot = targetFile.name.lastIndexOf('.')
            val stem = if (dot > 0) targetFile.name.substring(0, dot)
                else targetFile.name
            val extension = if (dot > 0) targetFile.name.substring(dot)
                else ""

            var suffix = 0
            while (true) {
                val candidate = if (suffix == 0) targetFile else File(
                    parent, "$stem ($suffix)$extension"
                )
                try {
                    // No REPLACE_EXISTING: an existing destination is retained.
                    java.nio.file.Files.move(
                        partFile.toPath(), candidate.toPath()
                    )
                    committedTarget = candidate
                    break
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    check(suffix < Int.MAX_VALUE) { "No available filename" }
                    suffix++
                }
            }
        }
        Unit
    }

    override suspend fun discard() = withContext(Dispatchers.IO) {
        close()
        check(!partFile.exists() || partFile.delete()) { "Cannot delete partial file" }
        Unit
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            handle?.close()
            handle = null
        }
        Unit
    }
}
