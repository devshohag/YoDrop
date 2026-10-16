package com.yodrop.android

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.yodrop.android.transport.hotspot.HotspotProbe
import com.yodrop.android.transport.hotspot.HotspotProbeScreen
import com.yodrop.android.ui.MainScreen
import com.yodrop.android.ble.AndroidBleManager
import com.yodrop.android.transport.AndroidSenderTransportSelector
import com.yodrop.android.transport.AndroidSenderLinkSetup
import com.yodrop.android.transport.AndroidReceiverLinkSetup
import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.DeviceCaps
import com.yodrop.core.protocol.HelloMessage
import com.yodrop.core.protocol.Platform
import com.yodrop.core.session.BleHandshakeController
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.ReceiverState
import kotlinx.coroutines.flow.collect
import com.yodrop.core.platform.DiskFileSink
import com.yodrop.core.platform.DiskFileSource
import com.yodrop.core.platform.FileSink
import com.yodrop.core.platform.FileSource
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderStateMachine
import java.io.File
import java.io.FileInputStream
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
    private lateinit var ble: AndroidBleManager
    private lateinit var handshake: BleHandshakeController
    private lateinit var receiverLinkSetup: AndroidReceiverLinkSetup
    private lateinit var senderLinkSetup: AndroidSenderLinkSetup
    private var pendingBleAction: (() -> Unit)? = null
    private val requestBlePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingBleAction
        pendingBleAction = null
        if (grants.isNotEmpty() && grants.values.all { it }) action?.let { withBlePermissions(it) }
        else Toast.makeText(this, "Nearby device permissions are required", Toast.LENGTH_LONG).show()
    }

    private lateinit var senderMachine: SenderStateMachine
    private lateinit var receiverMachine: ReceiverStateMachine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        senderMachine = SenderStateMachine(lifecycleScope, onCleanup = {
            if (::senderLinkSetup.isInitialized) senderLinkSetup.close()
        })

        receiverMachine = ReceiverStateMachine(
            scope = lifecycleScope,
            sinkFactory = { meta -> createScopedStorageFileSink(meta) },
            onCleanup = { if (::receiverLinkSetup.isInitialized) receiverLinkSetup.close() }
        )

        val selector = AndroidSenderTransportSelector(this, senderMachine)
        senderLinkSetup = AndroidSenderLinkSetup(lifecycleScope, senderMachine, selector, this)
        receiverLinkSetup = AndroidReceiverLinkSetup(this, lifecycleScope, receiverMachine)
        val hello = HelloMessage(
            deviceName = Build.MODEL.ifBlank { "Android" }.take(64),
            platform = Platform.ANDROID,
            capabilities = DeviceCaps.CAP_BLE or DeviceCaps.CAP_LAN or
                DeviceCaps.CAP_HOTSPOT_HOST or DeviceCaps.CAP_HOTSPOT_CLIENT or
                (if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
                    DeviceCaps.CAP_WIFI_DIRECT else 0),
            deviceId = persistentDeviceId(),
            lanIp = selector.activeLanAddress()?.ip
        )
        val helloSnapshot = { hello.copy(lanIp = selector.activeLanAddress()?.ip) }
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val bleMtu = if (debug) intent.getIntExtra("yodrop_ble_mtu", 247)
            .takeIf { it in 23..517 } ?: 247 else 247
        ble = AndroidBleManager(this, lifecycleScope, hello, requestedMtu = bleMtu,
            diagnosticsEnabled = debug && intent.getBooleanExtra("yodrop_gatt_probe", false),
            helloSnapshot = helloSnapshot, senderState = { senderMachine.state.value })
        handshake = BleHandshakeController(senderMachine, receiverMachine, hello,
            selectTransport = { peer -> selector.selectTransport(hello.capabilities, peer, peer.lanIp) },
            prepareLink = { _, choice -> senderLinkSetup.prepare(choice) },
            helloSnapshot = helloSnapshot,
            onOfferRead = { peer, offer -> ble.offerRead(peer, offer) },
            connectForClaim = { peer, offer -> ble.connectForClaim(peer, offer) },
            onLinkReady = { link -> receiverLinkSetup.prepare(link) }
        ) { peer, message ->
            ble.sendControlMessage(peer, message)
        }
        lifecycleScope.launch {
            ble.incomingMessages.collect { event ->
                try { handshake.onMessage(event) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { showBleError() }
            }
        }
        lifecycleScope.launch {
            ble.errors.collect { error ->
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            }
        }
        lifecycleScope.launch {
            senderMachine.state.collect { state ->
                try {
                    if (state is SenderState.Grabbed) ble.startAdvertising(state)
                    else ble.stopAdvertising()
                    if (state is SenderState.Failed && state.canResume) senderLinkSetup.recover()
                    if (state is SenderState.Done || state is SenderState.Cancelled ||
                        (state is SenderState.Failed && !state.canResume)) ble.close()
                } catch (_: SecurityException) { showBleError() }
            }
        }
        lifecycleScope.launch {
            receiverMachine.state.collect { state ->
                try {
                    if (state !is ReceiverState.Scanning) ble.stopScanning()
                    if (state is ReceiverState.Failed && state.canResume) receiverLinkSetup.recover()
                    if (state is ReceiverState.Done || state is ReceiverState.Cancelled ||
                        (state is ReceiverState.Failed && !state.canResume)) ble.close()
                } catch (_: SecurityException) { showBleError() }
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
                            if (receiverMachine.startScanning()) ble.startScanning()
                        }
                    }
                },
                onClaimClick = {
                    launchBleAction {
                        if (handshake.claim()) ble.stopScanning()
                    }
                },
                onAcceptClick = {
                    launchBleAction { handshake.accept() }
                },
                onDenyClick = {
                    launchBleAction { handshake.deny() }
                },
                onCancelClick = {
                    lifecycleScope.launch {
                        senderMachine.cancel("User cancelled")
                        receiverMachine.cancel("User cancelled")
                        ble.close()
                        handshake.reset()
                        senderMachine.reset()
                        receiverMachine.reset()
                    }
                }
            )
        }
    }

    private fun persistentDeviceId(): ByteArray {
        val prefs = getSharedPreferences("yodrop_identity", MODE_PRIVATE)
        val saved = runCatching {
            prefs.getString("device_id", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        }.getOrNull()
        if (saved?.size == 16) return saved
        return CryptoEngine.randomBytes(16).also {
            prefs.edit().putString("device_id", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
        }
    }

    private fun withBlePermissions(action: () -> Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT))
            if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingBleAction = action
            requestBlePermissions.launch(missing.toTypedArray())
        } else if (!ble.isBluetoothEnabled()) {
            Toast.makeText(this, "Turn on Bluetooth and try again", Toast.LENGTH_LONG).show()
        } else action()
    }

    private fun launchBleAction(action: suspend () -> Unit) {
        lifecycleScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showBleError() }
        }
    }

    private fun showBleError() {
        Toast.makeText(this, "Connection failed. Check Bluetooth, Wi-Fi, Location and permissions.", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        hotspotProbe?.stop()
        if (::ble.isInitialized) ble.close()
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

                handedOff = senderMachine.onGrabbed(
                    listOf(meta),
                    mapOf(meta.id to diskSource),
                    senderName = Build.MODEL.ifBlank { "Android" }.take(64)
                )
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
    private fun createScopedStorageFileSink(meta: FileMeta): FileSink {
        val tempFile = File(cacheDir, "yodrop_recv_${meta.id}.part")
        val baseSink = DiskFileSink(meta.id, tempFile)

        return object : FileSink {
            override val fileId: String get() = baseSink.fileId

            override suspend fun writeChunk(offset: Long, data: ByteArray) {
                baseSink.writeChunk(offset, data)
            }

            override suspend fun force() = baseSink.force()

            override suspend fun hasDataThrough(length: Long): Boolean =
                baseSink.hasDataThrough(length)

            override suspend fun verifySha256(expectedSha256: ByteArray): Boolean {
                return baseSink.verifySha256(expectedSha256)
            }

            override suspend fun commit() {
                baseSink.commit()
                // Strict publish: will throw on error and will NEVER delete tempFile on failure
                publishToMediaStore(baseSink.committedFile, meta.name, meta.mime)
            }

            override suspend fun discard() {
                baseSink.discard()
            }

            override suspend fun close() {
                baseSink.close()
            }
        }
    }

    @Throws(IOException::class)
    private fun publishToMediaStore(sourceFile: File, displayName: String, mimeType: String) {
        if (!sourceFile.exists()) {
            throw IOException("Source file does not exist: ${sourceFile.absolutePath}")
        }

        var publishedSuccessfully = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YoDrop")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Failed to insert MediaStore download record for $displayName")

                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                            }
                            out.flush()
                        }
                    } ?: throw IOException("Failed to open output stream for URI: $uri")

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    val updatedRows = resolver.update(uri, values, null, null)
                    if (updatedRows <= 0) {
                        throw IOException("Failed to finalize pending status for URI: $uri")
                    }
                    publishedSuccessfully = true
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val targetDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "YoDrop"
                )
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    throw IOException("Failed to create target directory: ${targetDir.absolutePath}")
                }
                val destFile = File(targetDir, displayName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                        out.flush()
                    }
                }
                publishedSuccessfully = true
            }

            // ONLY delete the temporary file after verified, successful publish
            if (publishedSuccessfully) {
                sourceFile.delete()
            }
        } catch (t: Throwable) {
            // Keep sourceFile intact on disk for recovery
            throw if (t is IOException) t else IOException("Publishing failed for $displayName", t)
        }
    }
}
