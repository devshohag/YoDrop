package com.yodrop.android.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
    private val app = context.applicationContext

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

    fun activeLanAddress(): LanAddress? = AndroidLanNetwork.current(app)?.address
}

/** A real interface snapshot, including Wi-Fi without Internet when mobile data is the default. */
data class AndroidLanNetwork(val network: Network, val address: LanAddress) {
    companion object {
        fun current(context: Context): AndroidLanNetwork? {
            val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val active = manager.activeNetwork
            // Do not silently bypass an active VPN with a local socket.
            if (active != null && manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return null
            fun candidate(network: Network): AndroidLanNetwork? {
                val caps = manager.getNetworkCapabilities(network) ?: return null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
                val addresses = manager.getLinkProperties(network)?.linkAddresses.orEmpty().filter {
                    it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isAnyLocalAddress && !it.address.isLinkLocalAddress
                }
                val local = addresses.singleOrNull() ?: return null
                return AndroidLanNetwork(network, LanAddress(local.address.hostAddress ?: return null, local.prefixLength))
            }
            active?.let { candidate(it)?.let { result -> return result } }
            @Suppress("DEPRECATION")
            return manager.allNetworks.mapNotNull { candidate(it) }.singleOrNull()
        }
    }
}
