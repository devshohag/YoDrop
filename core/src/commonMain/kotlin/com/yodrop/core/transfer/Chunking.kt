package com.yodrop.core.transfer

import kotlinx.serialization.Serializable
import com.yodrop.core.protocol.ProtocolBounds

@Serializable
class ChunkBitmap(val totalChunks: Int) {
    init {
        require(totalChunks in 0..ProtocolBounds.MAX_TOTAL_CHUNKS) {
            "Bitmap chunk count exceeds limit"
        }
    }
    private val bits = LongArray((totalChunks + 63) / 64)

    fun markReceived(index: Int) {
        require(index in 0 until totalChunks) { "Index $index out of bounds [0, $totalChunks)" }
        val arrayIdx = index / 64
        val bitIdx = index % 64
        bits[arrayIdx] = bits[arrayIdx] or (1L shl bitIdx)
    }

    fun isReceived(index: Int): Boolean {
        require(index in 0 until totalChunks) { "Index $index out of bounds" }
        val arrayIdx = index / 64
        val bitIdx = index % 64
        return (bits[arrayIdx] and (1L shl bitIdx)) != 0L
    }

    fun isComplete(): Boolean {
        for (i in 0 until totalChunks) {
            if (!isReceived(i)) return false
        }
        return true
    }

    fun getMissingChunks(batchSize: Int = 16): List<Int> {
        val missing = mutableListOf<Int>()
        for (i in 0 until totalChunks) {
            if (!isReceived(i)) {
                missing.add(i)
                if (missing.size == batchSize) break
            }
        }
        return missing
    }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(bits.size * 8)
        for (i in bits.indices) {
            val v = bits[i]
            for (b in 0..7) {
                bytes[i * 8 + b] = ((v shr (b * 8)) and 0xFF).toByte()
            }
        }
        return bytes
    }

    companion object {
        fun fromByteArray(totalChunks: Int, bytes: ByteArray): ChunkBitmap {
            val bitmap = ChunkBitmap(totalChunks)
            for (i in bitmap.bits.indices) {
                var v = 0L
                for (b in 0..7) {
                    val idx = i * 8 + b
                    if (idx < bytes.size) {
                        v = v or ((bytes[idx].toLong() and 0xFF) shl (b * 8))
                    }
                }
                bitmap.bits[i] = v
            }
            return bitmap
        }
    }
}

object ChunkPlanner {
    const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024

    fun calculateTotalChunks(
        fileSize: Long,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): Int {
        require(fileSize >= 0) { "Negative file size" }
        require(chunkSize > 0) { "Invalid chunk size" }

        val total = fileSize / chunkSize +
            if (fileSize % chunkSize == 0L) 0 else 1
        require(total <= ProtocolBounds.MAX_TOTAL_CHUNKS) {
            "File chunk count exceeds limit"
        }
        return total.toInt()
    }

    fun getChunkByteRange(
        chunkIndex: Int,
        fileSize: Long,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): Pair<Long, Long> {
        val total = calculateTotalChunks(fileSize, chunkSize)
        require(chunkIndex >= 0 && chunkIndex < total) {
            "Chunk index outside file"
        }
        val start = chunkIndex.toLong() * chunkSize
        return start to minOf(start + chunkSize.toLong(), fileSize)
    }
}