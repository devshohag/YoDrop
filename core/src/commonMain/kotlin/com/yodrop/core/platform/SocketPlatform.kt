package com.yodrop.core.platform

import com.yodrop.core.protocol.RawFrame

expect class PlatformSocket {
    suspend fun connect(host: String, port: Int)
    suspend fun writeFrame(frame: RawFrame)
    suspend fun readFrame(): RawFrame
    suspend fun close()
    val isConnected: Boolean
}

expect class PlatformServerSocket(port: Int) {
    suspend fun accept(): PlatformSocket
    suspend fun close()
    val localPort: Int
}
