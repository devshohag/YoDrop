package com.yodrop.core.protocol

/** The single service UUID is the entire application advertising payload (approved A4). */
object BleAdvertisingPacket {
    const val LEGACY_LIMIT = 31
    const val TIMEOUT_MS = 60_000
    val serviceUuid: String get() = Uuids.SERVICE_UUID_STRING

    /** Reference legacy AD encoding, including the stack's connectable Flags AD. */
    fun advertisement(): ByteArray {
        val uuid = serviceUuid.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return byteArrayOf(2, 0x01, 0x06, 17, 0x07) + uuid.reversedArray()
    }
    fun scanResponse(): ByteArray = byteArrayOf()
}
