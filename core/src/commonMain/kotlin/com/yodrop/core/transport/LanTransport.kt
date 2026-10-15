package com.yodrop.core.transport

import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.RawFrame

class LanTransport : Transport {

    override suspend fun connect(linkInfo: LinkInfoMessage): Connection {
        val socket = createPlatformSocket()
        socket.connect(linkInfo.host, linkInfo.port)
        return object : Connection {
            override val isClosed: Boolean get() = !socket.isConnected
            override suspend fun readFrame(): RawFrame = socket.readFrame()
            override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
            override suspend fun close() = socket.close()
        }
    }

    override suspend fun listen(port: Int): ConnectionListener {
        val server = createPlatformServerSocket(port)
        return object : ConnectionListener {
            override suspend fun accept(): Connection {
                val client = server.accept()
                return object : Connection {
                    override val isClosed: Boolean get() = !client.isConnected
                    override suspend fun readFrame(): RawFrame = client.readFrame()
                    override suspend fun writeFrame(frame: RawFrame) = client.writeFrame(frame)
                    override suspend fun close() = client.close()
                }
            }
            override suspend fun close() = server.close()
        }
    }
}
