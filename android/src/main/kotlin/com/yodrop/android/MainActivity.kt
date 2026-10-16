package com.yodrop.android

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.yodrop.android.transport.hotspot.HotspotProbe
import com.yodrop.android.transport.hotspot.HotspotProbeScreen
import com.yodrop.android.ui.MainScreen
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
        if (uris.isNotEmpty()) {
            startUriSendFlow(uris)
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

            MainScreen(
                senderState = senderState,
                receiverState = receiverState,
                peerName = peerName,
                preparingFiles = preparingFiles,
                onSendClick = { withBlePermissions { pickSendFile.launch(arrayOf("*/*")) } },
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
                    if (preparing.value) preparationJob?.cancel() else launchBleAction { it.reset() }
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
        hotspotProbe?.stop()
        super.onDestroy()
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
                    handedOff = runtime.startSending(prepared.files, prepared.sources)
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
