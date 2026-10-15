package com.yodrop.core.protocol

import com.yodrop.core.transfer.ChunkPlanner

object ProtocolBounds {
    const val MAX_CHUNK_SIZE = 4 * 1024 * 1024
    const val MAX_TOTAL_CHUNKS = 1_048_576
    const val MAX_REQUEST_CHUNKS = 64
    const val MAX_FILE_SIZE = MAX_TOTAL_CHUNKS.toLong() * MAX_CHUNK_SIZE

    fun validateFile(file: FileMeta) {
        require(file.size in 0L..MAX_FILE_SIZE) { "Invalid file size" }
        require(file.id.isNotBlank() && file.id.encodeToByteArray().size <= 128) {
            "Invalid file id"
        }
        require(file.sha256.size == 32) { "SHA-256 must be 32 bytes" }

        val name = file.name
        require(name.isNotBlank() && name.encodeToByteArray().size <= 255) {
            "Invalid filename length"
        }
        require(!name.startsWith(".") &&
            !name.endsWith(".") && !name.endsWith(" ")) {
            "Invalid filename"
        }
        require(name.none {
            it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|"
        }) { "Unsafe filename" }

        val stem = name.substringBefore('.').trimEnd().uppercase()
        require(stem !in setOf("CON", "PRN", "AUX", "NUL", "CLOCK$")) {
            "Reserved filename"
        }
        require(!(stem.length == 4 &&
            (stem.startsWith("COM") || stem.startsWith("LPT")) &&
            stem.last() in '1'..'9')) { "Reserved filename" }
    }

    fun validateManifest(manifest: ManifestMessage) {
        require(manifest.files.size in 1..1000) { "Invalid file count" }
        require(manifest.chunkSize in 64 * 1024..MAX_CHUNK_SIZE) {
            "Chunk size must be between 64 KiB and 4 MiB"
        }

        val ids = mutableSetOf<String>()
        var totalChunks = 0L
        for (file in manifest.files) {
            validateFile(file)
            require(ids.add(file.id)) { "Duplicate file id" }
            totalChunks += ChunkPlanner.calculateTotalChunks(
                file.size, manifest.chunkSize
            )
            require(totalChunks <= MAX_TOTAL_CHUNKS) {
                "Manifest chunk count exceeds limit"
            }
        }
    }

    fun validateRequest(
        indices: List<Int>,
        fileSize: Long,
        chunkSize: Int
    ) {
        require(indices.size in 1..MAX_REQUEST_CHUNKS) {
            "Invalid chunk request count"
        }
        val total = ChunkPlanner.calculateTotalChunks(fileSize, chunkSize)
        require(indices.all { it >= 0 && it < total }) {
            "Chunk index outside file"
        }
        require(indices.toSet().size == indices.size) {
            "Duplicate indices in one request"
        }
    }
}