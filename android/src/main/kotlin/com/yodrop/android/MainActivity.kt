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
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.ReceiverState
import kotlinx.coroutines.flow.collect
import com.yodrop.core.platform.DiskFileSource
import com.yodrop.core.platform.FileSource
import com.yodrop.core.protocol.FileMeta
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
class MainActivity : ComponentActivity() {
    private val pickSendFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            startUriSendFlow(uri)
        }
    }


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

            MainScreen(
                senderState = senderState,
                receiverState = receiverState,
                peerName = peerName,
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
                    launchBleAction { it.reset() }
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

    private fun startUriSendFlow(uri: Uri) {
        lifecycleScope.launch {
            var cachedFile: File? = null
            var source: FileSource? = null
            var handedOff = false

            try {
                val meta = withContext(Dispatchers.IO) {
                    var displayName = "received_file"

                    contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val index = cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        )
                        if (index >= 0 && cursor.moveToFirst()) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) {
                                displayName = name
                            }
                        }
                    }

                    // Keep a filename, never a provider-supplied path.
                    displayName = displayName
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                        .filter { it >= ' ' && it != '\u007f' }
                        .trim()

                    if (displayName.isBlank() ||
                        displayName.startsWith(".")) {
                        displayName = "received_file"
                    }

                    val localFile = File.createTempFile(
                        "yodrop_send_",
                        ".cache",
                        cacheDir
                    )
                    cachedFile = localFile

                    val digest = MessageDigest.getInstance("SHA-256")
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Cannot open selected file")

                    input.use { incoming ->
                        FileOutputStream(localFile).use { outgoing ->
                            val buffer = ByteArray(64 * 1024)

                            while (true) {
                                ensureActive()
                                val count = incoming.read(buffer)
                                if (count == -1) break

                                outgoing.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                            outgoing.flush()
                        }
                    }

                    FileMeta(
                        id = UUID.randomUUID().toString(),
                        name = displayName,
                        size = localFile.length(),
                        mime = contentResolver.getType(uri)
                            ?: "application/octet-stream",
                        sha256 = digest.digest()
                    )
                }

                val localFile = cachedFile
                    ?: throw IOException("Cached file is unavailable")
                val diskSource: FileSource = DiskFileSource(
                    meta.id,
                    localFile
                )
                source = diskSource

                // Finish the ownership handoff even if rotation cancels this preparation coroutine.
                withContext(kotlinx.coroutines.NonCancellable) {
                    handedOff = runtime.startSending(listOf(meta), mapOf(meta.id to diskSource))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Cannot prepare file: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                // Successful handoff keeps the source valid for the sender.
                if (!handedOff) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        try {
                            source?.close()
                        } finally {
                            cachedFile?.delete()
                        }
                    }
                }
            }
        }
    }
}
