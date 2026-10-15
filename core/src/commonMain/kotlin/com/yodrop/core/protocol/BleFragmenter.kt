package com.yodrop.core.protocol

/**
 * Handles fragmentation and reassembly for BLE packets over GATT.
 * Header format: [msgId: u16 (2B)][fragIndex: u8 (1B)][totalFrags: u8 (1B)]
 * Total header overhead = 4 bytes.
 */
object BleFragmenter {
    const val HEADER_SIZE = 4

    fun fragment(msgId: Int, data: ByteArray, mtu: Int): List<ByteArray> {
        val maxPayloadPerPacket = mtu - HEADER_SIZE
        require(maxPayloadPerPacket > 0) { "MTU $mtu too small for header" }

        val totalFrags = ((data.size + maxPayloadPerPacket - 1) / maxPayloadPerPacket).coerceAtLeast(1)
        require(totalFrags <= 255) { "Payload too large for 1-byte fragment counter" }

        val fragments = ArrayList<ByteArray>(totalFrags)
        for (i in 0 until totalFrags) {
            val start = i * maxPayloadPerPacket
            val end = (start + maxPayloadPerPacket).coerceAtMost(data.size)
            val chunkLen = end - start

            val packet = ByteArray(HEADER_SIZE + chunkLen)
            // msgId: u16 big-endian
            packet[0] = ((msgId shr 8) and 0xFF).toByte()
            packet[1] = (msgId and 0xFF).toByte()
            // fragIndex: u8
            packet[2] = i.toByte()
            // totalFrags: u8
            packet[3] = totalFrags.toByte()

            data.copyInto(packet, destinationOffset = HEADER_SIZE, startIndex = start, endIndex = end)
            fragments.add(packet)
        }
        return fragments
    }

    class Reassembler {
        private var currentMsgId: Int = -1
        private var totalFrags: Int = 0
        private val receivedChunks = HashMap<Int, ByteArray>()

        fun onPacketReceived(packet: ByteArray): ByteArray? {
            if (packet.size < HEADER_SIZE) return null

            val msgId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
            val fragIndex = packet[2].toInt() and 0xFF
            val total = packet[3].toInt() and 0xFF

            if (msgId != currentMsgId) {
                currentMsgId = msgId
                totalFrags = total
                receivedChunks.clear()
            }

            val payload = packet.copyOfRange(HEADER_SIZE, packet.size)
            receivedChunks[fragIndex] = payload

            if (receivedChunks.size == totalFrags) {
                var totalBytes = 0
                for (i in 0 until totalFrags) {
                    val c = receivedChunks[i] ?: return null
                    totalBytes += c.size
                }

                val fullPayload = ByteArray(totalBytes)
                var offset = 0
                for (i in 0 until totalFrags) {
                    val c = receivedChunks[i]!!
                    c.copyInto(fullPayload, destinationOffset = offset)
                    offset += c.size
                }
                receivedChunks.clear()
                return fullPayload
            }
            return null
        }
    }
}
