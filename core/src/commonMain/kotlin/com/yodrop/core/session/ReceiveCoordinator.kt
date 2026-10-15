package com.yodrop.core.session

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.platform.FileSink
import com.yodrop.core.protocol.*
import com.yodrop.core.transfer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Session-owned progress and leases; sockets own only their bounded request windows. */
internal class ReceiveCoordinator(
    private val sid: ByteArray,
    private val store: ResumeStore,
    private val sinkFactory: (FileMeta) -> FileSink
) {
    internal class FileWork(val meta: FileMeta, val sink: FileSink, var bitmap: ChunkBitmap) {
        val leases = mutableMapOf<Int, SessionConnection>()
        val retries = mutableMapOf<Int, Int>()
        var verifier: SessionConnection? = null
        var committed = false
        var discarded = false
        var bytesReceived = 0L
    }
    internal data class Work(
        val requests: List<ChunkReqMessage>,
        val verify: FileWork?,
        val committed: List<String>
    )
    internal data class Delivery(val fileId: String, val index: Int, val progress: Float, val name: String)
    internal class ChecksumMismatch(val fileId: String, name: String) : Exception("Checksum mismatch for file $name")

    private val mutex = Mutex()
    private var manifest: ManifestMessage? = null
    private val files = linkedMapOf<String, FileWork>()
    val version = MutableStateFlow(0L)
    private fun changed() { version.value += 1 }

    suspend fun prepare(next: ManifestMessage) = mutex.withLock {
        next.validate()
        val previous = manifest
        require(previous == null || (previous.chunkSize == next.chunkSize &&
            previous.files.size == next.files.size && previous.files.zip(next.files).all { (a, b) ->
                a.id == b.id && a.name == b.name && a.size == b.size && a.mime == b.mime &&
                    a.sha256.contentEquals(b.sha256)
            })) { "Manifest changed on reconnect" }
        manifest = next
        for (meta in next.files) {
            val total = ChunkPlanner.calculateTotalChunks(meta.size, next.chunkSize)
            val file = files[meta.id] ?: FileWork(meta, sinkFactory(meta),
                store.loadBitmap(sid, meta.id, total)).also { files[meta.id] = it }
            if (file.committed) continue
            var required = 0L
            var bytes = 0L
            for (i in 0 until total) if (file.bitmap.isReceived(i)) {
                val (start, end) = ChunkPlanner.getChunkByteRange(i, meta.size, next.chunkSize)
                required = end
                bytes += end - start
            }
            if (!file.sink.hasDataThrough(required)) {
                file.bitmap = ChunkBitmap(total)
                file.retries.clear()
                store.clear(sid, meta.id)
                bytes = 0
            }
            file.bytesReceived = bytes
        }
        changed()
    }

    suspend fun schedule(connection: SessionConnection, window: Int): Work = mutex.withLock {
        val reported = files.values.filter { it.committed }.map { it.meta.id }
        var free = window - files.values.sumOf { file -> file.leases.values.count { it === connection } }
        if (free == window) {
            val verify = files.values.firstOrNull {
                !it.committed && it.verifier == null && it.leases.isEmpty() && it.bitmap.isComplete()
            }
            if (verify != null) {
                verify.verifier = connection
                return@withLock Work(emptyList(), verify, reported)
            }
        }
        val requests = mutableListOf<ChunkReqMessage>()
        for (file in files.values) {
            if (free == 0) break
            if (file.committed || file.verifier != null) continue
            val indices = mutableListOf<Int>()
            for (index in 0 until file.bitmap.totalChunks) {
                if (free == 0) break
                if (!file.bitmap.isReceived(index) && index !in file.leases) {
                    file.leases[index] = connection
                    indices.add(index)
                    free--
                }
            }
            if (indices.isNotEmpty()) requests.add(ChunkReqMessage(file.meta.id, indices))
        }
        Work(requests, null, reported)
    }

    private data class Parsed(val file: FileWork, val index: Int, val attempt: Int,
        val offset: Int, val start: Long, val length: Int)

    suspend fun receive(connection: SessionConnection, frame: RawFrame, keys: SessionKeys): Delivery? {
        require(frame.type == FrameType.CHUNK_DATA) { "Expected CHUNK_DATA frame" }
        val payload = frame.payload
        require(payload.size >= 26) { "Truncated chunk header" }
        val idLength = payload[0].toInt() and 0xff
        require(idLength in 1..128 && payload.size >= 1 + idLength + 8 + 16) { "Invalid chunk header" }
        val id = payload.decodeToString(1, 1 + idLength, throwOnInvalidSequence = true)
        fun intAt(offset: Int): Int = ((payload[offset].toInt() and 0xff) shl 24) or
            ((payload[offset + 1].toInt() and 0xff) shl 16) or
            ((payload[offset + 2].toInt() and 0xff) shl 8) or (payload[offset + 3].toInt() and 0xff)
        val index = intAt(1 + idLength)
        val attempt = intAt(5 + idLength)
        val parsed = mutex.withLock {
            val file = files[id] ?: throw IllegalArgumentException("Wrong chunk file id")
            require(file.leases[index] === connection && !file.bitmap.isReceived(index)) {
                "Unrequested or duplicate chunk index"
            }
            require(attempt >= 0) { "Invalid attempt counter" }
            val (start, end) = ChunkPlanner.getChunkByteRange(index, file.meta.size, checkNotNull(manifest).chunkSize)
            val length = (end - start).toInt()
            require(payload.size - (9 + idLength) == length + 16) { "Wrong encrypted chunk length" }
            Parsed(file, index, attempt, 9 + idLength, start, length)
        }
        val plaintext = try {
            CryptoEngine.decryptAesGcm(keys.senderToReceiverKey,
                NonceUtils.buildChunkNonce(keys.nonceBase, parsed.index, parsed.attempt),
                payload.copyOfRange(parsed.offset, payload.size), NonceUtils.buildAad(sid, id, parsed.index))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val failures = mutex.withLock {
                val count = (parsed.file.retries[index] ?: 0) + 1
                parsed.file.retries[index] = count
                count
            }
            if (failures >= 3) throw ConnectionLostException("Chunk retry limit reached; reassigning $id/$index")
            connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.NACK,
                NackMessage(id, listOf(index), "CHUNK_AUTHENTICATION_FAILED")))
            return null
        }
        require(plaintext.size == parsed.length) { "Wrong plaintext chunk length" }
        return mutex.withLock {
            val file = parsed.file
            require(file.leases[index] === connection) { "Chunk lease no longer belongs to connection" }
            file.sink.writeChunk(parsed.start, plaintext)
            file.sink.force()
            file.bitmap.markReceived(index)
            store.saveBitmap(sid, id, file.bitmap)
            file.leases.remove(index)
            file.retries.remove(index)
            file.bytesReceived += plaintext.size
            changed()
            val totalBytes = files.values.sumOf { it.meta.size }
            val received = files.values.sumOf { it.bytesReceived }
            Delivery(id, index, if (totalBytes == 0L) 1f else received.toFloat() / totalBytes, file.meta.name)
        }
    }

    suspend fun verify(file: FileWork) {
        val matches = file.sink.verifySha256(file.meta.sha256)
        withContext(NonCancellable) {
            mutex.withLock {
                if (!matches) {
                    file.sink.discard()
                    file.sink.close()
                    file.discarded = true
                    store.clear(sid, file.meta.id)
                    throw ChecksumMismatch(file.meta.id, file.meta.name)
                }
                file.sink.commit()
                file.committed = true
                file.verifier = null
                store.clear(sid, file.meta.id)
                changed()
            }
        }
    }

    suspend fun release(connection: SessionConnection) = mutex.withLock {
        var released = false
        for (file in files.values) {
            val indices = file.leases.filterValues { it === connection }.keys.toList()
            for (index in indices) { file.leases.remove(index); file.retries.remove(index) }
            if (file.verifier === connection) { file.verifier = null; released = true }
            released = released || indices.isNotEmpty()
        }
        if (released) changed()
    }

    suspend fun allCommitted(): Boolean = mutex.withLock {
        manifest != null && files.values.all { it.committed }
    }

    suspend fun cleanup() = mutex.withLock {
        for (file in files.values) {
            if (!file.committed && !file.discarded) runCatching { file.sink.discard() }
            runCatching { file.sink.close() }
            runCatching { store.clear(sid, file.meta.id) }
        }
    }
}
