package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.transfer.ChunkPlanner
import kotlin.test.Test
import kotlin.test.assertEquals

class Phase9AcceptanceTests {

    @Test
    fun test1GBStreamingChunkPlannerValidation() {
        val oneGigabyteBytes = 1024L * 1024L * 1024L
        val chunkSize = ChunkPlanner.DEFAULT_CHUNK_SIZE

        val totalChunks = ChunkPlanner.calculateTotalChunks(oneGigabyteBytes, chunkSize)
        assertEquals(256, totalChunks)

        val (start0, end0) = ChunkPlanner.getChunkByteRange(0, oneGigabyteBytes, chunkSize)
        assertEquals(0L, start0)
        assertEquals(4L * 1024L * 1024L, end0)

        val (start255, end255) = ChunkPlanner.getChunkByteRange(255, oneGigabyteBytes, chunkSize)
        assertEquals(255L * 4 * 1024 * 1024, start255)
        assertEquals(oneGigabyteBytes, end255)
    }

    @Test
    fun test100FilesBatchMetaValidation() {
        val fileCount = 100
        val filesMeta = ArrayList<FileMeta>(fileCount)

        for (i in 1..fileCount) {
            val content = "YoDrop Batch Payload #$i".encodeToByteArray()
            val sha = CryptoEngine.sha256(content)
            val meta = FileMeta("f-$i", "file_$i.txt", content.size.toLong(), "text/plain", sha)
            filesMeta.add(meta)
        }

        assertEquals(100, filesMeta.size)
        val totalBytes = filesMeta.sumOf { it.size }
        assertEquals(true, totalBytes > 0)
    }
}
