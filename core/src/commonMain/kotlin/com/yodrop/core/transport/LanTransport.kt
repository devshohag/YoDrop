package com.yodrop.core.transport

import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.LinkInfoMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class LanTransport : Transport {

    override suspend fun connect(linkInfo: LinkInfoMessage): Connection {
        val socket = createPlatformSocket()
        try {
            socket.connect(linkInfo.host, linkInfo.port)
            return TcpConnection(socket)
        } catch (failure: Exception) {
            withContext(NonCancellable) { runCatching { socket.close() } }
            throw failure
        }
    }

    override suspend fun listen(port: Int): ConnectionListener {
        val server = createPlatformServerSocket(port)
        return object : ConnectionListener {
            override suspend fun accept(): Connection {
                val client = server.accept()
                return TcpConnection(client)
            }
            override suspend fun close() = server.close()
        }
    }
}
