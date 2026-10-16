package com.yodrop.android.transport

import android.content.Context
import com.yodrop.core.platform.PlatformSocket
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** One role/session. TCP uses the shared adapter, explicitly bound to the actual P2P interface. */
class WifiDirectTransport(context: Context, private val owner: Boolean = false) : Transport {
    private val groupMutex = Mutex()
    private val session = WifiDirectSession(AndroidWifiDirectPlatform(context), owner)
    suspend fun group(link: LinkInfoMessage? = null): WifiDirectGroup =
        groupMutex.withLock { session.ensureGroup(link?.let { WifiDirectCredentials.fromLink(it) }) }
    fun endpoint(group: WifiDirectGroup, port: Int) = session.linkInfo(group, port)

    override suspend fun connect(linkInfo: LinkInfoMessage): Connection {
        val link = linkInfo
        check(!owner)
        val current = group(link)
        check(current.ownerIp == link.host) { "Wi-Fi Direct endpoint changed; start a new session" }
        val socket = Socket()
        return try {
            withContext(Dispatchers.IO) {
                val network = checkNotNull(NetworkInterface.getByName(current.interfaceName)) { "Wi-Fi Direct interface is unavailable" }
                val local = network.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
                    ?: error("Wi-Fi Direct local address is unavailable")
                socket.bind(InetSocketAddress(local, 0))
                socket.connect(InetSocketAddress(link.host, link.port), 5_000)
                TcpConnection(PlatformSocket(socket))
            }
        } catch (failure: Exception) {
            socket.close()
            throw failure
        }
    }
    override suspend fun listen(port: Int): ConnectionListener {
        check(owner)
        group()
        return LanTransport().listen(port)
    }
    suspend fun close() = session.close()
}
