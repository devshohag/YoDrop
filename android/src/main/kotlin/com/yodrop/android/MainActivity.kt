package com.yodrop.android

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.yodrop.android.transport.hotspot.HotspotProbe
import com.yodrop.android.transport.hotspot.HotspotProbeScreen
import com.yodrop.android.ui.MainScreen
import com.yodrop.android.trust.TrustedPairingPanel
import com.yodrop.core.trust.PeerIdentityStatus
import com.yodrop.core.trust.TrustedDevice
import com.yodrop.core.trust.TrustedCatchTicket
import com.yodrop.android.gesture.GestureCameraPanel
import com.yodrop.android.gesture.AndroidTransferFeedback
import com.yodrop.android.storage.PreparedDocuments
import com.yodrop.core.gesture.*
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.SenderState
import kotlinx.coroutines.flow.collect

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class MainActivity : ComponentActivity() {
    private val pickSendFile = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) startUriSendFlow(uris)
    }


    private val uiLease = java.util.UUID.randomUUID().toString()
    private data class GestureFiles(val token: String, val documents: PreparedDocuments, val previewUri: Uri?)
    private val staged = kotlinx.coroutines.flow.MutableStateFlow<GestureFiles?>(null)
    private val practicing = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val gesturesEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val cameraAllowed = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var pendingCameraAction: (() -> Unit)? = null
    private var pendingCameraFallback: (() -> Unit)? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed.value = granted
        val action = pendingCameraAction; pendingCameraAction = null
        val fallback = pendingCameraFallback; pendingCameraFallback = null
        if (granted) action?.invoke()
        else {
            gesturesEnabled.value = false
            Toast.makeText(this, "Camera is optional. Grab/Catch buttons are available.", Toast.LENGTH_LONG).show()
            fallback?.invoke()
        }
    }
    private val preparing = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var preparationJob: kotlinx.coroutines.Job? = null
    private var hotspotProbe: HotspotProbe? = null
    private lateinit var runtime: com.yodrop.android.service.TransferRuntime
    private val ble get() = runtime.ble
    private val handshake get() = runtime.handshake
    private val senderMachine get() = runtime.sender
    private val receiverMachine get() = runtime.receiver
    private var pendingBleAction: (() -> Unit)? = null
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var pendingWifiAction: (() -> Unit)? = null
    private val permissionPlan get() = com.yodrop.core.session.TransferPermissions.forApi(Build.VERSION.SDK_INT)
    private val requestBlePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingBleAction
        pendingBleAction = null
        if (permissionPlan.mayProceed(grantedPermissions())) action?.let { ensureBluetooth(it) }
        else android.app.AlertDialog.Builder(this).setMessage("Allow Nearby devices/location permission in Settings to transfer files.")
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.setNegativeButton("Cancel", null).show()
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (ble.isBluetoothEnabled()) action?.let { ensureWifi(it) }
        else Toast.makeText(this, "Bluetooth is still off. No transfer started.", Toast.LENGTH_LONG).show()
    }
    private val enableWifi = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = pendingWifiAction
        pendingWifiAction = null
        if (wifiEnabled()) action?.invoke()
        else Toast.makeText(this, "Wi-Fi is still off. No transfer started.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = com.yodrop.android.service.TransferRuntime.get(this)
        practicing.value = savedInstanceState?.getBoolean("p6_practice") ?: false
        // A receive-window gesture is never restored after Activity recreation.
        cameraAllowed.value = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debug) runtime.configureGattDebug(intent.getIntExtra("yodrop_ble_mtu", 247).takeIf { it in 23..517 } ?: 247,
            intent.getBooleanExtra("yodrop_gatt_probe", false))
        lifecycleScope.launch {
            runtime.alerts.collect { message ->
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        if (debug && intent.getBooleanExtra("yodrop_hotspot_probe", false)) {
            val probe = HotspotProbe(this, lifecycleScope)
            hotspotProbe = probe
            setContent { HotspotProbeScreen(probe) { withBlePermissions { probe.start() } } }
            return
        }

        setContent {
            val senderState by senderMachine.state.collectAsState()
            val receiverState by receiverMachine.state.collectAsState()
            val peerName by handshake.peerName.collectAsState()
            val preparingFiles by preparing.collectAsState()
            val gestureFiles by staged.collectAsState()
            val practice by practicing.collectAsState()
            val cameraEnabled by gesturesEnabled.collectAsState()
            val permission by cameraAllowed.collectAsState()
            val trustedDevices by runtime.trustedDevices.devices.collectAsState()
            val trustStatus by runtime.trustedDevices.status.collectAsState()
            val identity by handshake.peerIdentity.collectAsState()
            val receiveWindow by runtime.trustedPairing.receiveWindow.state.collectAsState()
            val catchTicket = handshake.catchTicket()
            val canShowCamera = permission && (cameraEnabled || practice) && !preparingFiles && (
                practice || (gestureFiles != null && senderState is SenderState.Idle && receiverState is ReceiverState.Idle) ||
                    catchTicket != null || receiverState is ReceiverState.Scanning || receiverState is ReceiverState.OfferSeen || receiverState is ReceiverState.Claimed)
            val context = when {
                practice -> GestureContext(GestureMode.PRACTICE, "practice")
                gestureFiles != null -> GestureContext(GestureMode.SEND, gestureFiles!!.token)
                catchTicket != null -> catchTicket.gestureContext
                receiverState is ReceiverState.OfferSeen -> GestureContext(GestureMode.CLAIM, offerToken(receiverState as ReceiverState.OfferSeen))
                else -> GestureContext(GestureMode.OBSERVE, "scan")
            }

            MainScreen(
                senderState = senderState,
                receiverState = receiverState,
                peerName = peerName,
                preparingFiles = preparingFiles,
                preparedFiles = gestureFiles?.documents?.files.orEmpty(),
                previewUri = gestureFiles?.previewUri,
                catchTicket = catchTicket,
                trustedWindow = receiveWindow,
                trustedSession = runtime.trustedPairing.requiredWindow != null,
                peerIdentity = identity,
                cameraEnabled = canShowCamera,
                practicingGestures = practice,
                gesturePanel = { if (canShowCamera) GestureCameraPanel(this@MainActivity, context, ::onGesture) },
                trustPanel = {
                    if (!practice && !preparingFiles && gestureFiles == null) TrustedPairingPanel(
                        trustedDevices, trustStatus, identity, receiveWindow,
                        idle = senderState is SenderState.Idle && receiverState is ReceiverState.Idle,
                        mayRemember = senderState is SenderState.AwaitAccept || receiverState is ReceiverState.AcceptPrompt,
                        onRemember = ::confirmTrust,
                        onReceive = ::startTrustedCatch,
                        onForget = ::confirmForget,
                        onCloseWindow = { runtime.stopTrustedReceiving(it) },
                        onReset = ::confirmTrustReset)
                },
                onSendClick = ::chooseFiles,
                onSendPreparedClick = { gestureFiles?.let { files -> withBlePermissions { whenResumed { sendStaged(files.token) } } } },
                onPracticeClick = { withCameraPermission { practicing.value = true } },
                onCameraToggle = {
                    if (cameraEnabled) gesturesEnabled.value = false
                    else withCameraPermission { gesturesEnabled.value = true }
                },
                onCatchClick = { catchTicket?.let(::catchFiles) },
                onReceiveClick = {
                    withBlePermissions {
                        launchBleAction {
                            it.startReceiving()
                        }
                    }
                },
                onClaimClick = {
                    launchBleAction {
                        if (it.handshake.claim()) it.ble.stopScanning()
                    }
                },
                onAcceptClick = {
                    launchBleAction { it.handshake.accept() }
                },
                onDenyClick = {
                    launchBleAction { it.handshake.deny() }
                },
                onCancelClick = {
                    when {
                        preparing.value -> preparationJob?.cancel()
                        staged.value != null -> discardStaged()
                        practicing.value -> practicing.value = false
                        else -> { gesturesEnabled.value = false; launchBleAction { it.reset() } }
                    }
                }
            )
        }
    }

    private fun grantedPermissions(): Set<String> = (permissionPlan.required + permissionPlan.optional)
        .filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()

    private fun withBlePermissions(action: () -> Unit) {
        val plan = permissionPlan
        val missingRequired = plan.required - grantedPermissions()
        val prefs = getSharedPreferences("yodrop_permissions", MODE_PRIVATE)
        val optional = if (!prefs.getBoolean("notifications_requested", false)) plan.optional - grantedPermissions() else emptySet()
        val missing = missingRequired + optional
        if (missing.isNotEmpty()) {
            pendingBleAction = action
            if (optional.isNotEmpty()) prefs.edit().putBoolean("notifications_requested", true).apply()
            requestBlePermissions.launch(missing.toTypedArray())
        } else ensureBluetooth(action)
    }

    private fun ensureBluetooth(action: () -> Unit) {
        if (ble.isBluetoothEnabled()) ensureWifi(action)
        else {
            pendingBluetoothAction = action
            try { enableBluetooth.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            catch (_: android.content.ActivityNotFoundException) {
                pendingBluetoothAction = null
                Toast.makeText(this, "Turn on Bluetooth in Settings and retry.", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun wifiEnabled() = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
    private fun ensureWifi(action: () -> Unit) {
        if (wifiEnabled()) action()
        else {
            pendingWifiAction = action
            try { enableWifi.launch(android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)) }
            catch (_: android.content.ActivityNotFoundException) {
                pendingWifiAction = null
                Toast.makeText(this, "Turn on Wi-Fi in Settings and retry.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchBleAction(action: suspend (com.yodrop.android.service.TransferRuntime) -> Unit) {
        val session = runtime
        val app = applicationContext
        session.scope.launch {
            try { action(session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                Toast.makeText(app, "Connection failed. Check Bluetooth, Wi-Fi, Location and permissions.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        discardStaged()
        hotspotProbe?.stop()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("p6_practice", practicing.value)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        cameraAllowed.value = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (::runtime.isInitialized) runtime.onUiResumed(uiLease)
    }

    override fun onPause() {
        if (::runtime.isInitialized) runtime.onUiPaused(uiLease)
        super.onPause()
    }

    private fun confirmTrust(peer: PeerIdentityStatus) {
        val code = (senderMachine.state.value as? SenderState.AwaitAccept)?.verifyCode
            ?: (receiverMachine.state.value as? ReceiverState.AcceptPrompt)?.verifyCode ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Trust this device?")
            .setMessage("Compare the FULL pairing code on BOTH phones:\n\n${peer.pairingCode}\n\nDevice: ${peer.label}\nTransfer code: $code\n\nEach phone must trust separately. Trust only when the full pairing codes match. Use Accept separately when ready to receive.")
            .setPositiveButton("Codes match - trust") { _, _ -> launchBleAction { it.rememberPeer(peer.sessionToken) } }
            .setNegativeButton("Cancel", null).show()
    }
    private fun confirmForget(device: TrustedDevice) {
        android.app.AlertDialog.Builder(this).setTitle("Forget device?")
            .setMessage("Remove trust for ${device.label}? Future trusted receiving requires pairing again.")
            .setPositiveButton("Forget") { _, _ -> launchBleAction { it.forgetTrusted(device.fingerprint) } }
            .setNegativeButton("Cancel", null).show()
    }
    private fun confirmTrustReset() {
        android.app.AlertDialog.Builder(this).setTitle("Reset trusted pairing?")
            .setMessage("Remove local trusted devices and regenerate this phone's identity. Pair again on both phones. Received downloads are kept.")
            .setPositiveButton("Reset pairing") { _, _ -> launchBleAction { it.resetTrustedPairing() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseFiles() = withBlePermissions {
        pickSendFile.launch(arrayOf("*/*"))
    }

    private fun whenResumed(action: () -> Unit) {
        lifecycleScope.launch { lifecycle.withResumed { action() } }
    }

    private fun startTrustedCatch(fingerprint: String) = withBlePermissions {
        // Resolve system prompts before arming; the live window closes on every ON_PAUSE.
        val start = { whenResumed { launchBleAction { it.startTrustedReceiving(fingerprint) } } }
        withCameraPermission(onFallback = start) { gesturesEnabled.value = true; start() }
    }

    private fun catchFiles(ticket: TrustedCatchTicket) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        launchBleAction { it.catchTransfer(ticket, uiLease) }
    }

    private fun withCameraPermission(onFallback: () -> Unit = {}, action: () -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraAllowed.value = true; action(); return
        }
        val prefs = getSharedPreferences("yodrop_permissions", MODE_PRIVATE)
        if (prefs.getBoolean("camera_requested", false) && !shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) {
            android.app.AlertDialog.Builder(this).setMessage("Enable camera in Settings to use gestures, or continue with the buttons.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .setNegativeButton("Use buttons") { _, _ -> onFallback() }
                .setOnCancelListener { onFallback() }.show()
            return
        }
        prefs.edit().putBoolean("camera_requested", true).apply()
        pendingCameraAction = action
        pendingCameraFallback = onFallback
        cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    private fun offerToken(state: ReceiverState.OfferSeen) = android.util.Base64.encodeToString(state.offer.sessionId, android.util.Base64.NO_WRAP)

    private fun onGesture(command: GestureCommand) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        when (command.context.mode) {
            GestureMode.PRACTICE -> if (practicing.value) AndroidTransferFeedback(applicationContext).grabbed()
            GestureMode.SEND -> if (command.action == GestureAction.GRAB && staged.value?.token == command.context.token)
                withBlePermissions { whenResumed { sendStaged(command.context.token) } }
            GestureMode.CATCH -> {
                val ticket = handshake.catchTicket() ?: return
                if (command.action == GestureAction.RELEASE && command.context == ticket.gestureContext) catchFiles(ticket)
            }
            GestureMode.CLAIM -> {
                val offer = receiverMachine.state.value as? ReceiverState.OfferSeen ?: return
                if (command.action != GestureAction.RELEASE || offerToken(offer) != command.context.token) return
                launchBleAction { session ->
                    val current = session.receiver.state.value as? ReceiverState.OfferSeen
                    if (current != null && offerToken(current) == command.context.token && session.handshake.claim()) session.ble.stopScanning()
                }
            }
            else -> Unit
        }
    }

    private fun sendStaged(token: String) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val batch = staged.value?.takeIf { it.token == token } ?: return
        staged.value = null // Transfer-job ownership begins before any suspension/destruction.
        val session = runtime
        val app = applicationContext
        session.scope.launch {
            var handedOff = false
            try { handedOff = session.startSending(batch.documents.files, batch.documents.sources) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(app, "Cannot start sending. Check permissions and retry.", Toast.LENGTH_LONG).show() }
            finally { if (!handedOff) batch.documents.close() }
        }
    }

    private fun discardStaged() {
        val batch = staged.value ?: return
        staged.value = null
        val session = runtime
        session.scope.launch { batch.documents.close() }
    }

    private fun startUriSendFlow(uris: List<Uri>) {
        if (preparing.value || runtime.active() ||
            senderMachine.state.value !is com.yodrop.core.session.SenderState.Idle ||
            receiverMachine.state.value !is com.yodrop.core.session.ReceiverState.Idle) return
        preparing.value = true
        preparationJob = lifecycleScope.launch {
            var documents: com.yodrop.android.storage.PreparedDocuments? = null
            var handedOff = false
            try {
                documents = com.yodrop.android.storage.AndroidSelectedDocuments(applicationContext).prepare(uris)
                val prepared = documents
                // Atomic ownership handoff: rotation must not close sources accepted by the runtime.
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                        throw CancellationException("Selection screen closed")
                    staged.value = GestureFiles(java.util.UUID.randomUUID().toString(), prepared, uris.firstOrNull())
                    handedOff = true
                    withCameraPermission { gesturesEnabled.value = true }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Cannot prepare selected files. Check file access and retry.", Toast.LENGTH_LONG).show()
            } finally {
                if (!handedOff) documents?.close()
                preparing.value = false
            }
        }
    }
}
