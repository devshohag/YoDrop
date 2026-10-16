package com.yodrop.android.transport.hotspot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.yodrop.core.transport.*
import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** One reservation/session. API 29 legacy configuration; API 30+ reservation SoftApConfiguration. */
class AndroidHotspotManager(context: Context) {
    private val session = HotspotSession(AndroidHotspotPlatform(context.applicationContext))
    suspend fun startLocalHotspot(port: Int): HotspotCredentials = withContext(Dispatchers.Main.immediate) { session.start(port) }
    suspend fun stopLocalHotspot() = withContext(Dispatchers.Main.immediate) { session.close() }
    suspend fun awaitStopped() = session.awaitStopped()
}

@SuppressLint("MissingPermission")
private class AndroidHotspotPlatform(private val app: Context) : HotspotPlatform {
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override suspend fun interfaces(): List<HotspotInterfaceAddress> = withContext(Dispatchers.IO) {
        val upstream = connectivity?.allNetworks.orEmpty().flatMap { network ->
            connectivity?.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { it.address.hostAddress }
        }.toSet()
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { iface ->
            val eligible = iface.isUp && !iface.isLoopback && !iface.isPointToPoint
            iface.interfaceAddresses.mapNotNull { address ->
                val ip = address.address as? Inet4Address ?: return@mapNotNull null
                val host = ip.hostAddress ?: return@mapNotNull null
                HotspotInterfaceAddress(iface.name, host, address.networkPrefixLength.toInt(),
                    eligible && !ip.isLinkLocalAddress && host !in upstream)
            }
        }
    }

    override fun start(callbacks: HotspotCallbacks) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        check(ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED) { "Hotspot permission denied" }
        val manager = checkNotNull(wifi) { "Wi-Fi is unavailable" }
        check(manager.isWifiEnabled) { "Turn on Wi-Fi" }
        check((app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled) { "Turn on Location" }
        manager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                if (reservation == null) { callbacks.failed(-1); return }
                callbacks.started(AndroidHotspotLease(reservation))
            }
            override fun onFailed(reason: Int) { callbacks.failed(reason) }
            override fun onStopped() { callbacks.stopped() }
        }, Handler(Looper.getMainLooper()))
    }
}

private class AndroidHotspotLease(private val reservation: WifiManager.LocalOnlyHotspotReservation) : HotspotLease {
    private val closed = MutableStateFlow(false)
    override val configuration: HotspotConfiguration?
        get() = if (Build.VERSION.SDK_INT >= 30) {
            val config = reservation.softApConfiguration
            val ssid = config.ssid
            val pass = config.passphrase
            if (ssid == null || pass == null) null else HotspotConfiguration(ssid, pass)
        } else {
            @Suppress("DEPRECATION") val config = reservation.wifiConfiguration
            if (config?.SSID == null || config.preSharedKey == null) null
            else HotspotConfiguration(config.SSID.removeSurrounding("\""), config.preSharedKey.removeSurrounding("\""))
        }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { reservation.close() }
                .onSuccess { Log.d("YoDropHotspot", "HOTSPOT_RESERVATION released=true") }
                .onFailure { Log.w("YoDropHotspot", "Hotspot release unconfirmed; check Wi-Fi settings") }
        }
    }
}
