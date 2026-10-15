package com.yodrop.core.protocol

/** Opt-in debug harness using existing typed control framing; never a session endpoint. */
object GattDiagnosticProbe {
    const val HOST = "gatt-probe.invalid"
    const val MODE = "_yodrop_probe"
    const val SIZE = 1024
    fun isProbe(message: LinkInfoMessage) = message.host == HOST && MODE in message.extra

    fun request(token: ByteArray): LinkInfoMessage {
        require(token.size == 16)
        val hex = "0123456789abcdef"
        val tokenText = buildString {
            for (byte in token) { append(hex[(byte.toInt() ushr 4) and 15]); append(hex[byte.toInt() and 15]) }
        }
        var request = LinkInfoMessage(LinkType.LAN, HOST, 1,
            mapOf(MODE to "Q", "token" to tokenText, "padding" to ""))
        repeat(8) {
            val size = BleMessageCodec.encode(request).size
            if (size != SIZE) {
                val length = request.extra.getValue("padding").length + SIZE - size
                check(length >= 0)
                request = request.copy(extra = request.extra + ("padding" to "x".repeat(length)))
            }
        }
        check(BleMessageCodec.encode(request).size == SIZE)
        return request
    }
    fun reply(request: LinkInfoMessage): LinkInfoMessage {
        require(isProbe(request) && request.extra[MODE] == "Q" && BleMessageCodec.encode(request).size == SIZE)
        return request.copy(extra = request.extra + (MODE to "R")).also {
            check(BleMessageCodec.encode(it).size == SIZE)
        }
    }
}
