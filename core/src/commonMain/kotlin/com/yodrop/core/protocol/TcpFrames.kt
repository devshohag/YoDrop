package com.yodrop.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

object FrameType {
    const val AUTH: Byte = 0x01
    const val MANIFEST: Byte = 0x02
    const val CHUNK_REQ: Byte = 0x03
    const val CHUNK_DATA: Byte = 0x04
    const val ACK: Byte = 0x05
    const val NACK: Byte = 0x06
    const val FILE_DONE: Byte = 0x07
    const val CANCEL: Byte = 0x08
    const val DONE: Byte = 0x09
    const val PING_PONG: Byte = 0x0A
}

data class RawFrame(
    val type: Byte,
    val payload: ByteArray
) {
    val totalLength: Int get() = 1 + payload.size

    fun encode(): ByteArray {
        val totalLen = payload.size + 1
        val out = ByteArray(4 + totalLen)
        out[0] = ((totalLen shr 24) and 0xFF).toByte()
        out[1] = ((totalLen shr 16) and 0xFF).toByte()
        out[2] = ((totalLen shr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = type
        payload.copyInto(out, 5, 0, payload.size)
        return out
    }

    companion object {
        fun decode(bytes: ByteArray): RawFrame {
            require(bytes.size >= 5) { "Frame too short: ${bytes.size}" }
            val length = ((bytes[0].toInt() and 0xFF) shl 24) or
                    ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    (bytes[3].toInt() and 0xFF)
            require(bytes.size == 4 + length) { "Frame size mismatch. Expected ${4 + length}, got ${bytes.size}" }
            val type = bytes[4]
            val payload = bytes.copyOfRange(5, bytes.size)
            return RawFrame(type, payload)
        }
    }
}

@Serializable
data class AuthMessage(
    val sessionId: ByteArray,
    val authTag: ByteArray
)

@Serializable
data class FileMeta(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: ByteArray
)

@Serializable
data class ManifestMessage(
    val files: List<FileMeta>,
    val chunkSize: Int = 4 * 1024 * 1024
)

@Serializable
data class ChunkReqMessage(
    val fileId: String,
    val chunkIndices: List<Int>
)

@Serializable
data class ChunkHeader(
    val fileId: String,
    val chunkIndex: Int
)

@Serializable
data class AckMessage(
    val fileId: String,
    val chunkIndex: Int
)

@Serializable
data class NackMessage(
    val fileId: String,
    val chunkIndex: Int,
    val reason: String
)

@Serializable
data class FileDoneMessage(
    val fileId: String,
    val ok: Boolean
)

@Serializable
data class CancelMessage(
    val reason: String
)

@Serializable
data class PingPongMessage(
    val timestamp: Long,
    val isPong: Boolean
)

@OptIn(ExperimentalSerializationApi::class)
object ProtocolCodec {
    val cbor = Cbor { ignoreUnknownKeys = true }

    inline fun <reified T> encodeToFrame(type: Byte, value: T): RawFrame {
        val payload = cbor.encodeToByteArray(kotlinx.serialization.serializer(), value)
        return RawFrame(type, payload)
    }

    inline fun <reified T> decodePayload(frame: RawFrame): T {
        return cbor.decodeFromByteArray(kotlinx.serialization.serializer(), frame.payload)
    }
}
