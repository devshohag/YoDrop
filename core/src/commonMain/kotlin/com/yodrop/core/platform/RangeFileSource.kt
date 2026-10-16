package com.yodrop.core.platform

import com.yodrop.core.protocol.ProtocolBounds
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Platform reader; seek returns false only when the provider cannot seek. */
interface FileReadHandle {
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun seek(offset: Long): Boolean
    suspend fun close()
}

/** Bounded random chunk reads, including sequential/pipe document providers. No source copy. */
class RangeFileSource(
    override val fileId: String,
    override val size: Long,
    private val open: suspend () -> FileReadHandle,
    private val release: suspend () -> Unit = {}
) : FileSource {
    private val mutex = Mutex()
    private var handle: FileReadHandle? = null
    private var position = 0L
    private var closed = false
    init { require(size in 0L..ProtocolBounds.MAX_FILE_SIZE) }

    override suspend fun readChunk(offset: Long, length: Int): ByteArray = mutex.withLock {
        check(!closed) { "Selected document is closed" }
        require(offset in 0L..size && length in 0..ProtocolBounds.MAX_CHUNK_SIZE) { "Invalid chunk range" }
        val count = minOf(length.toLong(), size - offset).toInt()
        if (count == 0) return@withLock ByteArray(0)
        try {
            var reader = handle ?: open().also { handle = it; position = 0 }
            if (offset != position && reader.seek(offset)) position = offset
            if (offset < position) {
                handle = null
                reader.close()
                reader = open().also { handle = it; position = 0 }
            }
            if (position < offset) {
                val scratch = ByteArray(64 * 1024)
                while (position < offset) {
                    coroutineContext.ensureActive()
                    val n = reader.read(scratch, 0, minOf(scratch.size.toLong(), offset - position).toInt())
                    check(n in 1..minOf(scratch.size.toLong(), offset - position).toInt()) { "Selected document ended while seeking" }
                    position += n
                }
            }
            val result = ByteArray(count)
            var read = 0
            while (read < count) {
                coroutineContext.ensureActive()
                val n = reader.read(result, read, count - read)
                check(n in 1..(count - read)) { "Selected document changed or ended early" }
                read += n
                position += n
            }
            result
        } catch (failure: Exception) {
            val old = handle; handle = null; position = 0
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { old?.close() } }
            throw failure
        }
    }

    override suspend fun close() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        mutex.withLock {
            if (!closed) {
                closed = true
                val old = handle; handle = null
                try { old?.close() } finally { release() }
            }
        }
    }
}
