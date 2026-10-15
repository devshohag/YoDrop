package com.yodrop.android.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.yodrop.core.platform.PlatformServerSocket
import com.yodrop.core.platform.PlatformSocket
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.RawFrame
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.ConnectionListener
import com.yodrop.core.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class WifiDirectTransport(private val context: Context) : Transport {
    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    override suspend fun connect(linkInfo: LinkInfoMessage): Connection {
        val platformSocket = PlatformSocket()
        platformSocket.connect(linkInfo.host, linkInfo.port)
        return TcpConnectionWrapper(platformSocket)
    }

    override suspend fun listen(port: Int): ConnectionListener {
        val serverSocket = PlatformServerSocket(port)
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

    override suspend fun readFrame(): RawFrame {
        return socket.readFrame()
    }

    override suspend fun writeFrame(frame: RawFrame) {
        socket.writeFrame(frame)
    }

    override suspend fun close() {
        socket.close()
    }
}
