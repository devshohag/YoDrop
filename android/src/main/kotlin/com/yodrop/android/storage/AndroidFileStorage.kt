package com.yodrop.android.storage

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.yodrop.core.platform.*
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.ProtocolBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/** Approved A5: verified files appear automatically in Downloads/YoDrop on API 29+. */
class AndroidFileStorage(context: Context) {
    private val app = context.applicationContext
    fun createScopedStorageFileSink(meta: FileMeta): FileSink {
        ProtocolBounds.validateFile(meta)
        // Remote file IDs/names never form private filesystem paths. Each receive gets unique staging.
        val directory = File(app.filesDir, "incoming").apply { check(isDirectory || mkdirs()) { "Cannot create receive staging directory" } }
        val target = File(directory, UUID.randomUUID().toString() + ".staged")
        val staging = DiskFileSink(meta.id, target)
        return PublishingFileSink(staging,
            publish = { publish(staging.committedFile, meta) },
            removeStaging = { withContext(Dispatchers.IO) {
                val owned = runCatching { staging.committedFile }.getOrNull()
                if (owned?.exists() == true && !owned.delete()) Log.w("YoDropStorage", "Receive staging cleanup unconfirmed")
            } })
    }

    private suspend fun publish(source: File, meta: FileMeta) = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, meta.name)
            put(MediaStore.Downloads.MIME_TYPE, meta.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YoDrop")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create received download")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                    }
                    if (copied != meta.size) throw IOException("Receive staging length changed")
                    out.flush()
                }
            } ?: throw IOException("Cannot open received download")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Cannot publish received download")
        } catch (failure: Exception) {
            // Never reveal a partially copied download; preserve the original error if cleanup fails.
            try { if (resolver.delete(uri, null, null) != 1) Log.w("YoDropStorage", "Pending download cleanup unconfirmed") }
            catch (_: Exception) { Log.w("YoDropStorage", "Pending download cleanup unconfirmed") }
            throw failure
        }
        Log.d("YoDropStorage", "DOWNLOAD_PUBLISHED") // No filename, content URI or private preview.
    }
}
