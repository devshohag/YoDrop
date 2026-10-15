package com.yodrop.core.transport

import com.yodrop.core.platform.PlatformSocket
import com.yodrop.core.protocol.RawFrame

/** Shared adapter for LAN, Wi-Fi Direct and hotspot TCP sockets. */
class TcpConnection(private val socket: PlatformSocket) : Connection {
    override val isClosed: Boolean get() = !socket.isConnected
    override suspend fun readFrame(): RawFrame = socket.readFrame()
    override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
    override suspend fun close() = socket.close()
}
