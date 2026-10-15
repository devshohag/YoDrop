package com.yodrop.android.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yodrop.core.protocol.HelloMessage
import com.yodrop.core.protocol.Platform
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.LanAddress
import com.yodrop.core.transport.TransportSelection
import java.net.Inet4Address

/** Platform metadata only; the sender machine owns payload size and selection policy. */
class AndroidSenderTransportSelector(
    context: Context,
    private val sender: SenderStateMachine
) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    suspend fun selectTransport(
        senderCapabilities: Int,
        peer: HelloMessage,
        peerIp: String? = null
    ): TransportSelection = sender.selectTransport(
        senderPlatform = Platform.ANDROID,
        senderCapabilities = senderCapabilities,
        peer = peer,
        senderLan = activeLanAddress(),
        peerIp = peerIp
    )

    fun activeLanAddress(): LanAddress? {
        val manager = connectivity ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        val properties = manager.getLinkProperties(network) ?: return null
        val local = properties.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress &&
                !it.address.isAnyLocalAddress && !it.address.isLinkLocalAddress
        } ?: return null
        val ip = local.address.hostAddress ?: return null
        return LanAddress(ip, local.prefixLength)
    }
}
