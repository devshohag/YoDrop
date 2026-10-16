package com.yodrop.android.transport.hotspot

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yodrop.core.platform.PlatformServerSocket
import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Entered only from a debuggable APK + explicit intent extra. Never logs join credentials. */
class HotspotProbe(private val context: Context, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow("Stopped")
    val status = _status.asStateFlow()
    private val _credentials = MutableStateFlow<HotspotCredentials?>(null)
    val credentials = _credentials.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val manager = AndroidHotspotManager(context)
            var listener: PlatformServerSocket? = null
            var accept: Job? = null
            _status.value = "Starting..."
            try {
                withContext(Dispatchers.IO) { listener = createPlatformServerSocket(0) }
                val server = checkNotNull(listener)
                val info = manager.startLocalHotspot(server.localPort)
                _credentials.value = info
                _status.value = "Hotspot started"
                Log.d("YoDropHotspot", "HOTSPOT_PROBE READY gateway=${info.gatewayIp} port=${info.port} configValid=true")
                accept = launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            server.accept().close()
                            Log.d("YoDropHotspot", "HOTSPOT_PROBE TCP_REACHABLE")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Closing the listener unblocks the diagnostic accept. */ }
                }
                manager.awaitStopped()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _status.value = "Failed. Check Wi-Fi, Location and permissions." }
            finally {
                withContext(NonCancellable) {
                    manager.stopLocalHotspot()
                    listener?.close()
                    accept?.cancelAndJoin()
                    _credentials.value = null
                    if (!_status.value.startsWith("Failed")) _status.value = "Stopped"
                }
            }
        }
    }
    fun stop() { job?.cancel() }
}

@Composable
fun HotspotProbeScreen(probe: HotspotProbe, start: () -> Unit) {
    val status by probe.status.collectAsState()
    val credentials by probe.credentials.collectAsState()
    var showJoin by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Hotspot diagnostic", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Button(onClick = { showJoin = false; start() }, enabled = credentials == null) { Text("Start hotspot") }
        OutlinedButton(onClick = { showJoin = false; probe.stop() }) { Text("Stop / cancel") }
        credentials?.let { info ->
            Text("Gateway: ${info.gatewayIp}:${info.port}")
            OutlinedButton(onClick = { showJoin = !showJoin }) { Text(if (showJoin) "Hide join details" else "Show join details") }
            if (showJoin) { Text("SSID: ${info.ssid}"); Text("Passphrase: ${info.passphrase}") }
        }
    }
}
