package com.yodrop.android.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.yodrop.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** WifiP2pManager adapter. No supplied Intent address/credential is trusted; query the service. */
@SuppressLint("MissingPermission")
class AndroidWifiDirectPlatform(context: Context) : WifiDirectPlatform {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var retired = false
    private var registered = false
    private var pendingMutation: CompletableDeferred<Unit>? = null
    private var ownsRequest = false
    private var discovering = false
    private var configured: WifiDirectCredentials? = null
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { changed.trySend(Unit) }
    }

    private fun ready(): Pair<WifiP2pManager, WifiP2pManager.Channel> {
        check(!retired) { "Wi-Fi Direct adapter is closed" }
        check(app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) { "Wi-Fi Direct is unavailable" }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        check(ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED) { "Grant Nearby Wi-Fi/location permission" }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        check(wifi.isWifiEnabled) { "Turn on Wi-Fi and retry" }
        val location = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        check(location.isLocationEnabled) { "Turn on Location for Wi-Fi Direct discovery" }
        val service = checkNotNull(manager) { "Wi-Fi Direct is unavailable" }
        if (channel == null) {
            channel = checkNotNull(service.initialize(app, Looper.getMainLooper()) { channel = null; changed.trySend(Unit) }) { "Cannot initialize Wi-Fi Direct" }
            if (!registered) ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        return service to checkNotNull(channel)
    }

    private class ActionFailure(val reason: Int, operation: String) : IllegalStateException("Wi-Fi Direct $operation failed ($reason)")

    private suspend fun action(name: String, mutation: Boolean = false, block: (WifiP2pManager.ActionListener) -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val finished = if (mutation) CompletableDeferred<Unit>().also { pendingMutation = it } else null
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    finished?.complete(Unit)
                    changed.trySend(Unit)
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onFailure(reason: Int) {
                    finished?.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(ActionFailure(reason, name))
                }
            })
        }

    private suspend fun groupInfo(service: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { continuation -> service.requestGroupInfo(c) { if (continuation.isActive) continuation.resume(it) } }
    private suspend fun connectionInfo(service: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pInfo =
        suspendCancellableCoroutine { continuation -> service.requestConnectionInfo(c) { if (continuation.isActive) continuation.resume(it) } }

    override suspend fun currentGroup(): WifiDirectGroup? = withContext(Dispatchers.Main.immediate) {
        val (service, c) = ready()
        val group = groupInfo(service, c) ?: return@withContext null
        val info = connectionInfo(service, c)
        if (!info.groupFormed || info.groupOwnerAddress !is Inet4Address) {
            check(ownsRequest) { "Another Wi-Fi Direct group is starting" }
            return@withContext null
        }
        val ip = checkNotNull(info.groupOwnerAddress.hostAddress)
        val name = group.networkName.orEmpty()
        val pass = if (info.isGroupOwner) group.passphrase.orEmpty()
            else configured?.takeIf { it.networkName == name }?.passphrase.orEmpty()
        WifiDirectGroup(info.isGroupOwner, ip, WifiDirectCredentials(name, pass), group.`interface`.orEmpty())
    }

    override suspend fun discoverPeers() = withContext(Dispatchers.Main.immediate) {
        val (service, c) = ready()
        discovering = true
        action("discovery") { service.discoverPeers(c, it) }
        // Peer discovery result is queried rather than taking a MAC from a BLE advertisement.
        service.requestPeers(c) { changed.trySend(Unit) }
    }

    private fun config(credentials: WifiDirectCredentials, band: Int = WifiP2pConfig.GROUP_OWNER_BAND_AUTO) = WifiP2pConfig.Builder()
        .setNetworkName(credentials.networkName).setPassphrase(credentials.passphrase)
        .setGroupOperatingBand(band).enablePersistentMode(false).build()

    override suspend fun createGroup(credentials: WifiDirectCredentials): WifiDirectGroup = withContext(Dispatchers.Main.immediate) {
        credentials.validate()
        val (service, c) = ready()
        configured = credentials
        ownsRequest = true // Set before asynchronous mutation: cancellation still removes a late group.
        val prefer5Ghz = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).is5GHzBandSupported
        try {
            val band = if (prefer5Ghz) WifiP2pConfig.GROUP_OWNER_BAND_5GHZ else WifiP2pConfig.GROUP_OWNER_BAND_AUTO
            action("create group", mutation = true) { service.createGroup(c, config(credentials, band), it) }
        } catch (failure: ActionFailure) {
            if (!prefer5Ghz || failure.reason != WifiP2pManager.ERROR) throw failure
            action("create group", mutation = true) { service.createGroup(c, config(credentials), it) }
        }
        awaitGroup(owner = true, credentials)
    }

    override suspend fun joinGroup(credentials: WifiDirectCredentials): WifiDirectGroup = withContext(Dispatchers.Main.immediate) {
        credentials.validate()
        val (service, c) = ready()
        configured = credentials
        ownsRequest = true
        // API 29+ uses SSID/passphrase from encrypted BLE; no guessed or private local MAC required.
        action("join group", mutation = true) { service.connect(c, config(credentials), it) }
        awaitGroup(owner = false, credentials)
    }

    private suspend fun awaitGroup(owner: Boolean, credentials: WifiDirectCredentials): WifiDirectGroup {
        while (currentCoroutineContext().isActive) {
            val group = currentGroup()
            if (group != null) {
                check(group.isGroupOwner == owner && group.credentials == credentials) { "Unexpected Wi-Fi Direct group/role" }
                group.validate()
                val (service, c) = ready()
                val frequency = groupInfo(service, c)?.frequency ?: 0
                Log.d("YoDropWFD", "P2P_READY owner=$owner frequencyMHz=$frequency")
                return group
            }
            // Also requery if a vendor omits a broadcast. Setup deadline is owned by shared core.
            withTimeoutOrNull(500) { changed.receive() }
        }
        throw CancellationException("Wi-Fi Direct setup cancelled")
    }

    override suspend fun shutdown() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (retired) return@withContext
        retired = true
        val service = manager
        val c = channel
        if (service != null && c != null) {
            if (discovering) withTimeoutOrNull(1_000) { runCatching { action("stop discovery") { service.stopPeerDiscovery(c, it) } } }
            if (ownsRequest) {
                val mutationFinished = withTimeoutOrNull(1_000) { pendingMutation?.await(); true } == true
                withTimeoutOrNull(1_000) { runCatching { action("cancel connect") { service.cancelConnect(c, it) } } }
                // Inspect after removal too: an in-flight create/join can finish during cancellation.
                var removed = false
                repeat(3) {
                    withTimeoutOrNull(1_000) { runCatching { action("remove group") { service.removeGroup(c, it) } } }
                    removed = withTimeoutOrNull(500) { runCatching { groupInfo(service, c) == null }.getOrDefault(false) } == true
                    if (it < 2) delay(100)
                }
                if (!removed || !mutationFinished) Log.w("YoDropWFD", "Group removal not confirmed; check Wi-Fi Direct settings")
                else Log.d("YoDropWFD", "P2P_CLEANUP confirmed=true")
            }
        }
        configured = null
        if (registered) { runCatching { app.unregisterReceiver(receiver) }; registered = false }
        runCatching { channel?.close() }
        channel = null
        changed.close()
    }
}
