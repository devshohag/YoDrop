package com.yodrop.core.protocol

import kotlin.time.TimeSource

/** Header: msgId u16, fragIndex u8, totalFrags u8. Packet size is GATT value bytes. */
object BleFragmenter {
    const val HEADER_SIZE = 4
    const val MAX_VALUE_BYTES = 512

    fun packetSizeForMtu(attMtu: Int): Int {
        require(attMtu in 23..517) { "Invalid ATT MTU" }
        return minOf(attMtu - 3, MAX_VALUE_BYTES)
    }

    fun fragment(msgId: Int, data: ByteArray, mtu: Int): List<ByteArray> {
        // Keep the existing argument name for callers: mtu here is the packet/value capacity.
        require(msgId in 0..65535) { "Message ID must fit u16" }
        require(mtu in (HEADER_SIZE + 1)..MAX_VALUE_BYTES) { "Invalid GATT value capacity" }
        require(data.size <= BleMessageCodec.MAX_MESSAGE_BYTES) { "BLE message too large" }
        val payloadSize = mtu - HEADER_SIZE
        val total = (data.size / payloadSize + if (data.size % payloadSize == 0) 0 else 1).coerceAtLeast(1)
        require(total <= 255) { "Message exceeds fragment limit at this MTU" }
        return List(total) { index ->
            val start = index * payloadSize
            val length = minOf(payloadSize, data.size - start)
            ByteArray(HEADER_SIZE + length).also { packet ->
                packet[0] = (msgId ushr 8).toByte()
                packet[1] = msgId.toByte()
                packet[2] = index.toByte()
                packet[3] = total.toByte()
                data.copyInto(packet, HEADER_SIZE, start, start + length)
            }
        }
    }

    class Reassembler(
        private val timeoutMs: Long = 10_000,
        private val maxMessageBytes: Int = BleMessageCodec.MAX_MESSAGE_BYTES,
        private val nowMs: (() -> Long)? = null
    ) {
        private val origin = TimeSource.Monotonic.markNow()
        private fun now() = nowMs?.invoke() ?: origin.elapsedNow().inWholeMilliseconds
        private var currentMsgId = -1
        private var totalFrags = 0
        private var startedAt = 0L
        private var bufferedBytes = 0
        private val received = mutableMapOf<Int, ByteArray>()
        init { require(timeoutMs > 0 && maxMessageBytes > 0) }

        private fun clear() {
            currentMsgId = -1
            totalFrags = 0
            bufferedBytes = 0
            received.clear()
        }

        fun expireStale(): Boolean {
            if (currentMsgId >= 0 && now() - startedAt >= timeoutMs) {
                clear()
                return true
            }
            return false
        }

        fun onPacketReceived(packet: ByteArray): ByteArray? {
            expireStale()
            if (packet.size !in HEADER_SIZE..MAX_VALUE_BYTES) { clear(); return null }
            val msgId = ((packet[0].toInt() and 255) shl 8) or (packet[1].toInt() and 255)
            val index = packet[2].toInt() and 255
            val total = packet[3].toInt() and 255
            if (total == 0 || index >= total) { clear(); return null }
            if (msgId != currentMsgId) {
                clear()
                currentMsgId = msgId
                totalFrags = total
                startedAt = now()
            } else if (total != totalFrags) { clear(); return null }
            val previous = received[index]
            if (previous != null) {
                if (previous.size != packet.size - HEADER_SIZE ||
                    previous.indices.any { previous[it] != packet[it + HEADER_SIZE] }) clear()
                return null
            }
            val length = packet.size - HEADER_SIZE
            if (length > maxMessageBytes - bufferedBytes) { clear(); return null }
            received[index] = packet.copyOfRange(HEADER_SIZE, packet.size)
            bufferedBytes += length
            if (received.size != totalFrags) return null
            val full = ByteArray(bufferedBytes)
            var offset = 0
            for (i in 0 until totalFrags) {
                val chunk = received[i] ?: return null
                chunk.copyInto(full, offset)
                offset += chunk.size
            }
            clear()
            return full
        }
    }
}
