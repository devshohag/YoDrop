package com.yodrop.android.ui

import android.net.Uri
import android.os.CancellationSignal
import android.text.format.Formatter
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yodrop.core.protocol.FileMeta
import kotlinx.coroutines.*

@Composable
fun FilePreviewCard(files: List<FileMeta>, previewUri: Uri?) {
    val context = LocalContext.current
    val resolver = context.applicationContext.contentResolver
    val first = files.firstOrNull()
    val thumbnail by produceState<ImageBitmap?>(null, previewUri, first?.id) {
        value = null
        if (previewUri != null && (first?.mime?.startsWith("image/") == true || first?.mime?.startsWith("video/") == true)) {
            val signal = CancellationSignal()
            try { value = withContext(Dispatchers.IO) { resolver.loadThumbnail(previewUri, Size(360, 240), signal).asImageBitmap() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Providers without thumbnails keep the file metadata preview. */ }
            finally { signal.cancel() }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            thumbnail?.let { Image(it, "Preview of ${first?.name}", Modifier.fillMaxWidth().height(144.dp), contentScale = ContentScale.Crop) }
            Text("${files.size} selected · ${Formatter.formatShortFileSize(context, files.sumOf { it.size })}",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            files.take(5).forEach { file ->
                Column {
                    Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("${Formatter.formatShortFileSize(context, file.size)} · ${file.mime}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (files.size > 5) Text("+ ${files.size - 5} more files", style = MaterialTheme.typography.bodySmall)
        }
    }
}
