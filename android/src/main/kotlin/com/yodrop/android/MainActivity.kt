package com.yodrop.android

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.yodrop.android.ui.MainScreen
import com.yodrop.core.platform.DiskFileSink
import com.yodrop.core.platform.DiskFileSource
import com.yodrop.core.platform.FileSink
import com.yodrop.core.platform.FileSource
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderStateMachine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
class MainActivity : ComponentActivity() {
    private val pickSendFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            startUriSendFlow(uri)
        }
    }


    private lateinit var senderMachine: SenderStateMachine
    private lateinit var receiverMachine: ReceiverStateMachine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        senderMachine = SenderStateMachine(lifecycleScope)

        receiverMachine = ReceiverStateMachine(
            scope = lifecycleScope,
            sinkFactory = { meta -> createScopedStorageFileSink(meta) }
        )

        setContent {
            val senderState by senderMachine.state.collectAsState()
            val receiverState by receiverMachine.state.collectAsState()

            MainScreen(
                senderState = senderState,
                receiverState = receiverState,
                onSendClick = { pickSendFile.launch(arrayOf("*/*")) },
                onReceiveClick = {
                    lifecycleScope.launch { receiverMachine.startScanning() }
                },
                onAcceptClick = {
                    val fallbackLink = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 8888)
                    lifecycleScope.launch {
                        try {
                            receiverMachine.acceptTransfer(fallbackLink)
                        } catch (_: IllegalStateException) {
                            // A stale/repeated UI action is rejected by the core.
                        }
                    }
                },
                onDenyClick = {
                    lifecycleScope.launch {
                        try {
                            receiverMachine.denyTransfer("User declined")
                        } catch (_: IllegalStateException) {
                            // Prompt may already have been dismissed.
                        }
                    }
                },
                onCancelClick = {
                    lifecycleScope.launch {
                        senderMachine.cancel("User cancelled")
                        receiverMachine.cancel("User cancelled")
                        senderMachine.reset()
                        receiverMachine.reset()
                    }
                }
            )
        }
    }

    private fun startUriSendFlow(uri: Uri) {
        lifecycleScope.launch {
            var cachedFile: File? = null
            var source: FileSource? = null
            var handedOff = false

            try {
                val meta = withContext(Dispatchers.IO) {
                    var displayName = "received_file"

                    contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val index = cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        )
                        if (index >= 0 && cursor.moveToFirst()) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) {
                                displayName = name
                            }
                        }
                    }

                    // Keep a filename, never a provider-supplied path.
                    displayName = displayName
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                        .filter { it >= ' ' && it != '\u007f' }
                        .trim()

                    if (displayName.isBlank() ||
                        displayName.startsWith(".")) {
                        displayName = "received_file"
                    }

                    val localFile = File.createTempFile(
                        "yodrop_send_",
                        ".cache",
                        cacheDir
                    )
                    cachedFile = localFile

                    val digest = MessageDigest.getInstance("SHA-256")
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Cannot open selected file")

                    input.use { incoming ->
                        FileOutputStream(localFile).use { outgoing ->
                            val buffer = ByteArray(64 * 1024)

                            while (true) {
                                ensureActive()
                                val count = incoming.read(buffer)
                                if (count == -1) break

                                outgoing.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                            outgoing.flush()
                        }
                    }

                    FileMeta(
                        id = UUID.randomUUID().toString(),
                        name = displayName,
                        size = localFile.length(),
                        mime = contentResolver.getType(uri)
                            ?: "application/octet-stream",
                        sha256 = digest.digest()
                    )
                }

                val localFile = cachedFile
                    ?: throw IOException("Cached file is unavailable")
                val diskSource: FileSource = DiskFileSource(
                    meta.id,
                    localFile
                )
                source = diskSource

                handedOff = senderMachine.onGrabbed(
                    listOf(meta),
                    mapOf(meta.id to diskSource)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Cannot prepare file: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                // Successful handoff keeps the source valid for the sender.
                if (!handedOff) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        try {
                            source?.close()
                        } finally {
                            cachedFile?.delete()
                        }
                    }
                }
            }
        }
    }
    private fun createScopedStorageFileSink(meta: FileMeta): FileSink {
        val tempFile = File(cacheDir, "yodrop_recv_${meta.id}.part")
        val baseSink = DiskFileSink(meta.id, tempFile)

        return object : FileSink {
            override val fileId: String get() = baseSink.fileId

            override suspend fun writeChunk(offset: Long, data: ByteArray) {
                baseSink.writeChunk(offset, data)
            }

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
                val resolver = contentResolver
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