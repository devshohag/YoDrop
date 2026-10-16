package com.yodrop.android.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import kotlinx.coroutines.*
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class PreparedDocuments(val files: List<FileMeta>, val sources: Map<String, FileSource>) {
    suspend fun close() = withContext(NonCancellable) {
        sources.values.forEach { runCatching { it.close() } }
    }
}

/** SAF URI grants and descriptor IO only. Chunk positioning/bounds live in shared RangeFileSource. */
class AndroidSelectedDocuments(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    suspend fun prepare(selected: List<Uri>): PreparedDocuments {
        var prepared: PreparedDocuments? = null
        try { return withContext(Dispatchers.IO) {
            val uris = selected.distinct()
            require(uris.size in 1..1000) { "Select between 1 and 1000 files" }
            val files = mutableListOf<FileMeta>()
            val sources = mutableMapOf<String, FileSource>()
            try {
                for (uri in uris) {
                    ensureActive()
                    require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose files from the system document picker" }
                    var ownedGrant = false
                    var source: FileSource? = null
                    try {
                        if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                            try {
                                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                ownedGrant = true
                            } catch (_: SecurityException) { /* Some providers allow only the current temporary read grant. */ }
                        }
                        val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && it.moveToFirst() && !it.isNull(index)) it.getString(index) else null
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        val handle = open(uri)
                        try {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = handle.read(buffer, 0, buffer.size)
                                if (n == -1) break
                                if (n <= 0) throw IOException("Document provider made no read progress")
                                size += n
                                require(size <= ProtocolBounds.MAX_FILE_SIZE) { "Selected file exceeds transfer limit" }
                                digest.update(buffer, 0, n)
                            }
                        } finally { handle.close() }
                        val meta = FileMeta(UUID.randomUUID().toString(), SelectedFileName.fromDisplayName(rawName), size,
                            resolver.getType(uri) ?: "application/octet-stream", digest.digest())
                        ProtocolBounds.validateFile(meta)
                        val releaseGrant = ownedGrant
                        source = RangeFileSource(meta.id, size, open = { openSafely(uri) }, release = {
                            if (releaseGrant) withContext(Dispatchers.IO) {
                                runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            }
                        })
                        sources[meta.id] = source
                        files += meta
                    } catch (failure: Exception) {
                        withContext(NonCancellable) {
                            if (source != null) source.close()
                            else if (ownedGrant) runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        }
                        throw failure
                    }
                }
                ManifestMessage(files).validate()
                PreparedDocuments(files.toList(), sources.toMap()).also { prepared = it }
            } catch (failure: Exception) {
                withContext(NonCancellable) { sources.values.forEach { runCatching { it.close() } } }
                throw failure
            }
        } } catch (failure: Exception) {
            prepared?.close() // Includes cancellation during IO -> caller dispatch, before delivery.
            throw failure
        }
    }

    private suspend fun openSafely(uri: Uri): FileReadHandle {
        var created: FileReadHandle? = null
        try { return withContext(Dispatchers.IO) { open(uri).also { created = it } } }
        catch (failure: Exception) {
            withContext(NonCancellable) { created?.close() }
            throw failure
        }
    }

    private fun open(uri: Uri): FileReadHandle {
        val asset = resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("Selected document is unavailable")
        val input = try { asset.createInputStream() } catch (failure: Exception) { asset.close(); throw failure }
        return object : FileReadHandle {
            // A subsection stream has an internal remaining-byte count; never bypass it with lseek.
            private var seekable = asset.startOffset == 0L && asset.declaredLength == AssetFileDescriptor.UNKNOWN_LENGTH
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                withContext(Dispatchers.IO) { input.read(buffer, offset, length) }
            override suspend fun seek(offset: Long): Boolean = withContext(Dispatchers.IO) {
                if (!seekable) return@withContext false
                try { Os.lseek(asset.fileDescriptor, offset, OsConstants.SEEK_SET); true }
                catch (failure: ErrnoException) {
                    if (failure.errno != OsConstants.ESPIPE) throw failure
                    seekable = false
                    false
                }
            }
            override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
                try { input.close() } finally { asset.close() }
            }
        }
    }
}
