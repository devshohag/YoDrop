package com.yodrop.android.storage

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yodrop.core.platform.DiskFileSink
import com.yodrop.core.platform.FileSink
import com.yodrop.core.protocol.FileMeta
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Application context only: receive/publish remains valid after an Activity is destroyed. */
class AndroidFileStorage(private val context: Context) {
    fun createScopedStorageFileSink(meta: FileMeta): FileSink {
        val tempFile = File(context.cacheDir, "yodrop_recv_${meta.id}.part")
        val baseSink = DiskFileSink(meta.id, tempFile)

        return object : FileSink {
            override val fileId: String get() = baseSink.fileId

            override suspend fun writeChunk(offset: Long, data: ByteArray) {
                baseSink.writeChunk(offset, data)
            }

            override suspend fun force() = baseSink.force()

            override suspend fun hasDataThrough(length: Long): Boolean =
                baseSink.hasDataThrough(length)

            override suspend fun verifySha256(expectedSha256: ByteArray): Boolean {
                return baseSink.verifySha256(expectedSha256)
            }

            override suspend fun commit() {
                baseSink.commit()
                // Strict publish: will throw on error and will NEVER delete tempFile on failure
                publishToMediaStore(baseSink.committedFile, meta.name, meta.mime)
            }

            override suspend fun discard() {
                baseSink.discard()
            }

            override suspend fun close() {
                baseSink.close()
            }
        }
    }

    @Throws(IOException::class)
    private fun publishToMediaStore(sourceFile: File, displayName: String, mimeType: String) {
        if (!sourceFile.exists()) {
            throw IOException("Source file does not exist: ${sourceFile.absolutePath}")
        }

        var publishedSuccessfully = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YoDrop")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Failed to insert MediaStore download record for $displayName")

                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                            }
                            out.flush()
                        }
                    } ?: throw IOException("Failed to open output stream for URI: $uri")

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    val updatedRows = resolver.update(uri, values, null, null)
                    if (updatedRows <= 0) {
                        throw IOException("Failed to finalize pending status for URI: $uri")
                    }
                    publishedSuccessfully = true
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val targetDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "YoDrop"
                )
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    throw IOException("Failed to create target directory: ${targetDir.absolutePath}")
                }
                val destFile = File(targetDir, displayName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                        out.flush()
                    }
                }
                publishedSuccessfully = true
            }

            // ONLY delete the temporary file after verified, successful publish
            if (publishedSuccessfully) {
                sourceFile.delete()
            }
        } catch (t: Throwable) {
            // Keep sourceFile intact on disk for recovery
            throw if (t is IOException) t else IOException("Publishing failed for $displayName", t)
        }
    }
}
