package com.yodrop.android.transport

import android.annotation.SuppressLint
import android.content.Context
import com.yodrop.core.platform.PlatformSocket
import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.RawFrame
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.ConnectionListener
import com.yodrop.core.transport.Transport

@SuppressLint("MissingPermission")
class WifiDirectTransport(private val context: Context) : Transport {

    override suspend fun connect(linkInfo: LinkInfoMessage): Connection {
        val platformSocket = createPlatformSocket()
        platformSocket.connect(linkInfo.host, linkInfo.port)
        return TcpConnectionWrapper(platformSocket)
    }

    override suspend fun listen(port: Int): ConnectionListener {
        val serverSocket = createPlatformServerSocket(port)
        return object : ConnectionListener {
            override suspend fun accept(): Connection {
                val clientPlatformSocket = serverSocket.accept()
                return TcpConnectionWrapper(clientPlatformSocket)
            }
            override suspend fun close() {
                serverSocket.close()
            }
        }
    }
}

class TcpConnectionWrapper(private val socket: PlatformSocket) : Connection {
    override val isClosed: Boolean get() = !socket.isConnected
    override suspend fun readFrame(): RawFrame = socket.readFrame()
    override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
    override suspend fun close() = socket.close()
}
