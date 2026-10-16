package com.yodrop.android.service

import android.bluetooth.BluetoothAdapter
import android.content.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import com.yodrop.android.ble.AndroidBleManager
import com.yodrop.android.storage.AndroidFileStorage
import com.yodrop.android.gesture.AndroidTransferFeedback
import com.yodrop.android.trust.AndroidTrustStorage
import com.yodrop.core.trust.*
import android.os.SystemClock
import com.yodrop.android.transport.*
import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.FileSource
import com.yodrop.core.platform.DiskResumeStore
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/** Process/session ownership, independent of Activity lifecycle. No Activity is retained. */
class TransferRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val feedback = AndroidTransferFeedback(app)
    private val trustStorage = AndroidTrustStorage(app)
    val trustedDevices = TrustedDeviceRegistry(trustStorage)
    val trustedPairing = TrustedPairing(trustStorage, trustedDevices, TrustedReceiveWindow(SystemClock::elapsedRealtime))
    private var visibleUi: String? = null
    private var trustedWindowToken: String? = null
    private var trustedWindowTimer: Job? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _alerts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val alerts = _alerts.asSharedFlow()
    private lateinit var senderLink: AndroidSenderLinkSetup
    private lateinit var receiverLink: AndroidReceiverLinkSetup
    private var serviceGeneration = 0
    private var serviceRequested = false
    private val foreground = ForegroundSession(object : ForegroundServicePort {
        override fun start() {
            serviceGeneration++
            serviceRequested = true
            try {
                ContextCompat.startForegroundService(app, Intent(app, TransferForegroundService::class.java)
                    .putExtra(SERVICE_GENERATION, serviceGeneration))
            } catch (failure: RuntimeException) { serviceRequested = false; throw failure }
        }
        override fun stop() {
            serviceRequested = false
            app.stopService(Intent(app, TransferForegroundService::class.java))
        }
    })
    val sender = SenderStateMachine(scope, onCleanup = { senderLink.close() })
    val receiver = ReceiverStateMachine(scope, resumeStore = DiskResumeStore(File(app.filesDir, "resume")),
        sinkFactory = AndroidFileStorage(app)::createScopedStorageFileSink,
        onCleanup = { receiverLink.close() })
    val ble: AndroidBleManager
    val handshake: BleHandshakeController
    private var radioRegistered = false
    private val radioEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active()) return
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION &&
                intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1) == WifiManager.WIFI_STATE_DISABLED)
                _alerts.tryEmit("Wi-Fi is off. Turn it on to continue or resume the transfer.")
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                _alerts.tryEmit("Bluetooth is off. Turn it on before pairing again.")
                if (sender.state.value is SenderState.Grabbed || sender.state.value is SenderState.Claimed || sender.state.value is SenderState.AwaitAccept ||
                    receiver.state.value is ReceiverState.Scanning || receiver.state.value is ReceiverState.OfferSeen ||
                    receiver.state.value is ReceiverState.Claimed || receiver.state.value is ReceiverState.AcceptPrompt)
                    scope.launch { cancel("Bluetooth disabled during pairing") }
            }
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        scope.launch { trustedDevices.refresh() }
        val selector = AndroidSenderTransportSelector(app, sender)
        senderLink = AndroidSenderLinkSetup(scope, sender, selector, app)
        receiverLink = AndroidReceiverLinkSetup(app, scope, receiver)
        val hello = HelloMessage(Build.MODEL.ifBlank { "Android" }.take(64), Platform.ANDROID,
            DeviceCaps.CAP_TRUSTED_PAIRING or DeviceCaps.CAP_BLE or DeviceCaps.CAP_LAN or DeviceCaps.CAP_HOTSPOT_HOST or DeviceCaps.CAP_HOTSPOT_CLIENT or
                (if (app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)) DeviceCaps.CAP_WIFI_DIRECT else 0),
            persistentDeviceId(), selector.activeLanAddress()?.ip)
        val snapshot = { hello.copy(lanIp = selector.activeLanAddress()?.ip) }
        ble = AndroidBleManager(app, scope, hello, helloSnapshot = snapshot, senderState = { sender.state.value })
        handshake = BleHandshakeController(sender, receiver, hello,
            selectTransport = { peer -> selector.selectTransport(hello.capabilities, peer, peer.lanIp) },
            prepareLink = { _, choice -> senderLink.prepare(choice) }, helloSnapshot = snapshot,
            onOfferRead = { peer, offer -> ble.offerRead(peer, offer) },
            connectForClaim = { peer, offer -> ble.connectForClaim(peer, offer) },
            onLinkReady = { receiverLink.prepare(it) }, trustedPairing = trustedPairing) { peer, message -> ble.sendControlMessage(peer, message) }
        scope.launch {
            ble.incomingMessages.collect {
                try { handshake.onMessage(it) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _alerts.tryEmit("Connection failed. Check Bluetooth, Wi-Fi, Location and permissions.") }
            }
        }
        scope.launch { ble.errors.collect { _alerts.emit(it) } }
        scope.launch {
            sender.state.collect { state ->
                try {
                    if (state is SenderState.Grabbed) feedback.grabbed()
                    if (state is SenderState.Done) feedback.completed()
                    if (state is SenderState.Grabbed) ble.startAdvertising(state) else ble.stopAdvertising()
                    if (state is SenderState.Failed && state.canResume) senderLink.recover()
                    if (state is SenderState.Done || state is SenderState.Cancelled || (state is SenderState.Failed && !state.canResume)) ble.close()
                } catch (_: SecurityException) { cancel("Bluetooth permission was revoked") }
            }
        }
        scope.launch {
            receiver.state.collect { state ->
                try {
                    if (state is ReceiverState.Claimed) feedback.grabbed()
                    if (state is ReceiverState.Done) feedback.completed()
                    if (state !is ReceiverState.Scanning) ble.stopScanning()
                    if (state is ReceiverState.Failed && state.canResume) receiverLink.recover()
                    if (state is ReceiverState.Done || state is ReceiverState.Cancelled || (state is ReceiverState.Failed && !state.canResume)) {
                        trustedPairing.receiveWindow.close(); trustedWindowTimer?.cancel(); trustedWindowToken = null
                        ble.close()
                    }
                } catch (_: SecurityException) { cancel("Bluetooth permission was revoked") }
            }
        }
        scope.launch {
            combine(sender.state, receiver.state) { a, b -> ForegroundSessionPolicy.required(a, b) }.distinctUntilChanged().collect { running ->
                foreground.update(sender.state.value, receiver.state.value)
                if (running && !radioRegistered) {
                    ContextCompat.registerReceiver(app, radioEvents, IntentFilter().apply {
                        addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                    }, ContextCompat.RECEIVER_EXPORTED)
                    radioRegistered = true
                } else if (!running && radioRegistered) {
                    app.unregisterReceiver(radioEvents); radioRegistered = false
                }
            }
        }
    }

    fun configureGattDebug(mtu: Int, probe: Boolean) {
        if (!active()) ble.configureDiagnostics(mtu, probe)
    }
    fun active() = ForegroundSessionPolicy.required(sender.state.value, receiver.state.value)
    fun onServiceDestroyed(generation: Int) {
        // A delayed destroy callback from an old session must not cancel a newly started session.
        scope.launch {
            if (serviceRequested && generation == serviceGeneration && active()) cancel("Foreground service stopped")
        }
    }
    suspend fun startSending(files: List<FileMeta>, sources: Map<String, FileSource>): Boolean {
        if (sender.state.value !is SenderState.Idle || receiver.state.value !is ReceiverState.Idle) return false
        foreground.begin() // Visible user action, before discovery/background work.
        return try { sender.onGrabbed(files, sources, Build.MODEL.ifBlank { "Android" }.take(64)) }
        finally { foreground.update(sender.state.value, receiver.state.value) }
    }
    suspend fun startReceiving() {
        if (sender.state.value !is SenderState.Idle || receiver.state.value !is ReceiverState.Idle) return
        foreground.begin()
        try { if (receiver.startScanning()) ble.startScanning() }
        catch (failure: Exception) { receiver.cancel("Discovery could not start"); throw failure }
        finally { foreground.update(sender.state.value, receiver.state.value) }
    }
    suspend fun cancel(reason: String = "User cancelled") {
        trustedPairing.receiveWindow.close(); trustedWindowTimer?.cancel(); trustedWindowToken = null
        sender.cancel(reason); receiver.cancel(reason); ble.close()
    }
    fun onUiResumed(lease: String) { visibleUi = lease }
    fun onUiPaused(lease: String) {
        if (visibleUi != lease) return
        visibleUi = null
        trustedPairing.receiveWindow.state.value?.let { stopTrustedReceiving(it.token, "Trusted receive closed when app left foreground") }
    }
    fun stopTrustedReceiving(token: String, reason: String = "Trusted receive closed") {
        trustedPairing.receiveWindow.close(token) // Revoke immediately, even while a GATT write owns the controller mutex.
        scope.launch {
            if (trustedWindowToken == token && (receiver.state.value is ReceiverState.Scanning || receiver.state.value is ReceiverState.OfferSeen ||
                    receiver.state.value is ReceiverState.Claimed || receiver.state.value is ReceiverState.AcceptPrompt)) cancel(reason)
        }
    }
    suspend fun startTrustedReceiving(fingerprint: String) {
        if (visibleUi == null || sender.state.value !is SenderState.Idle || receiver.state.value !is ReceiverState.Idle || trustedDevices.find(fingerprint) == null) return
        val window = trustedPairing.armReceive(fingerprint)
        trustedWindowToken = window.token
        try {
            startReceiving()
            trustedWindowTimer?.cancel()
            trustedWindowTimer = scope.launch {
                while (trustedPairing.receiveWindow.state.value?.token == window.token) {
                    if (!trustedPairing.receiveWindow.isLive(window)) { stopTrustedReceiving(window.token, "Trusted receive window expired"); break }
                    delay(250)
                }
            }
        } catch (failure: Exception) { cancel("Trusted discovery could not start"); throw failure }
    }
    suspend fun rememberPeer(token: String) {
        if (!handshake.rememberPeer(token)) _alerts.emit("Could not trust this device. Check the active codes and pairing storage.")
    }
    suspend fun forgetTrusted(fingerprint: String) {
        trustedPairing.receiveWindow.state.value?.takeIf { it.target == fingerprint }?.let { stopTrustedReceiving(it.token, "Trusted device removed") }
        if (!trustedDevices.forget(fingerprint)) _alerts.emit("Could not update trusted devices. No new trust was granted.")
        handshake.refreshPeerTrust()
    }
    suspend fun resetTrustedPairing() {
        if (sender.state.value !is SenderState.Idle || receiver.state.value !is ReceiverState.Idle) return
        trustedPairing.clearReceiveSession()
        if (!trustedDevices.reset()) _alerts.emit("Trusted pairing storage is unavailable. Retry after checking device security/storage.")
        handshake.refreshPeerTrust()
    }
    suspend fun reset() { cancel(); handshake.reset(); sender.reset(); receiver.reset() }
    private fun persistentDeviceId(): ByteArray {
        val prefs = app.getSharedPreferences("yodrop_identity", Context.MODE_PRIVATE)
        val saved = runCatching { prefs.getString("device_id", null)?.let { Base64.decode(it, Base64.NO_WRAP) } }.getOrNull()
        return if (saved?.size == 16) saved else CryptoEngine.randomBytes(16).also {
            prefs.edit().putString("device_id", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
        }
    }
    companion object {
        internal const val SERVICE_GENERATION = "service_generation"
        private var instance: TransferRuntime? = null
        fun get(context: Context): TransferRuntime {
            check(Looper.myLooper() == Looper.getMainLooper())
            return instance ?: TransferRuntime(context).also { instance = it }
        }
    }
}
