package com.yodrop.core.platform

import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.ProtocolBounds

object SelectedFileName {
    fun fromDisplayName(raw: String?): String {
        var name = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .take(255).map { if (it.code < 32 || it.code == 127 || it in ":*?\"<>|") '_' else it }
            .joinToString("").trim().trimEnd('.')
        while (name.encodeToByteArray().size > 255) name = name.dropLast(1)
        return try {
            ProtocolBounds.validateFile(FileMeta("selected", name, 0, "application/octet-stream", ByteArray(32)))
            name
        } catch (_: IllegalArgumentException) { "received_file" }
    }
}
