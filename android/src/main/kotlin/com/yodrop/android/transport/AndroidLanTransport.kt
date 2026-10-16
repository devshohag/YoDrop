package com.yodrop.android.transport

import android.content.Context
import com.yodrop.core.platform.PlatformSocket
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.transport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Bind this socket only, never the process: offline Wi-Fi can coexist with default mobile data. */
class AndroidLanTransport(context: Context) : Transport {
    private val app = context.applicationContext
    override suspend fun connect(linkInfo: LinkInfoMessage): Connection {
        linkInfo.validate()
        require(linkInfo.linkType == LinkType.LAN)
        val lan = checkNotNull(AndroidLanNetwork.current(app)) { "LAN interface is unavailable" }
        check(TransportSelector.isSameSubnet(lan.address.ip, linkInfo.host, lan.address.prefixLength)) { "LAN endpoint is no longer on this network" }
        val socket = Socket()
        try {
            return withContext(Dispatchers.IO) {
                lan.network.bindSocket(socket)
                socket.bind(InetSocketAddress(lan.address.ip, 0))
                socket.connect(InetSocketAddress(linkInfo.host, linkInfo.port), 5_000)
                TcpConnection(PlatformSocket(socket))
            }
        } catch (failure: Exception) { socket.close(); throw failure }
    }
    override suspend fun listen(port: Int): ConnectionListener = LanTransport().listen(port)
}
