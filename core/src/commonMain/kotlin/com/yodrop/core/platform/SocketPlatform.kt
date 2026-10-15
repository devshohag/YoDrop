package com.yodrop.core.platform

import com.yodrop.core.protocol.RawFrame

expect class PlatformSocket {
    val isConnected: Boolean
    suspend fun connect(host: String, port: Int)
    suspend fun writeFrame(frame: RawFrame)
    suspend fun readFrame(): RawFrame
    suspend fun close()
}

expect class PlatformServerSocket {
    val localPort: Int
    suspend fun accept(): PlatformSocket
    suspend fun close()
}

expect fun createPlatformSocket(): PlatformSocket
expect fun createPlatformServerSocket(port: Int): PlatformServerSocket
