package com.yodrop.android.trust

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yodrop.core.trust.*
import kotlinx.coroutines.delay

/** Identity controls and explicit, selected-target receive windows. */
@Composable
fun TrustedPairingPanel(
    devices: List<TrustedDevice>, status: TrustStorageStatus, peer: PeerIdentityStatus?,
    window: ReceiveWindow?, idle: Boolean, mayRemember: Boolean,
    onRemember: (PeerIdentityStatus) -> Unit, onReceive: (String) -> Unit,
    onForget: (TrustedDevice) -> Unit, onCloseWindow: (String) -> Unit, onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var seconds by remember(window?.token) { mutableLongStateOf(60) }
    LaunchedEffect(window?.token) {
        if (window != null) while (true) {
            seconds = ((window.expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
            if (seconds == 0L) break
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (peer != null && mayRemember) {
            Text(if (peer.trusted) "Identity verified: ${peer.label} (trusted here)" else "New device identity: ${peer.label}")
            Text("Key: ${peer.fingerprint.takeLast(16)}", style = MaterialTheme.typography.bodySmall)
            Text("Pairing code: ${peer.pairingCode}", style = MaterialTheme.typography.bodySmall)
            if (!peer.trusted) OutlinedButton(onClick = { onRemember(peer) }) { Text("Compare codes and trust device") }
        }
        if (window != null) {
            val name = devices.firstOrNull { it.fingerprint == window.target }?.label ?: "Selected device"
            Text("Catch from $name · ${seconds}s", color = MaterialTheme.colorScheme.primary)
            Text("Catch appears after identity verification. Leaving the app closes this window.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onCloseWindow(window.token) }) { Text("Close receive window") }
        }
        if (idle) {
            TextButton(onClick = { expanded = !expanded }) { Text("Catch from a trusted device (${devices.size})") }
            if (expanded) {
                when (status) {
                    TrustStorageStatus.LOADING -> Text("Loading device identity...")
                    TrustStorageStatus.UNAVAILABLE -> {
                        Text("Pairing storage unavailable. No device is treated as trusted.")
                        OutlinedButton(onClick = onReset) { Text("Reset trusted pairing") }
                    }
                    TrustStorageStatus.READY -> {
                        if (devices.isEmpty()) Text("Connect using Send/Receive, compare the full pairing code, then trust on each phone.")
                        for (device in devices) {
                            Text(device.label)
                            Text("Key: ${device.fingerprint.takeLast(16)}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onReceive(device.fingerprint) }) { Text("Get ready to Catch") }
                                TextButton(onClick = { onForget(device) }) { Text("Forget") }
                            }
                        }
                        TextButton(onClick = onReset) { Text("Reset trusted pairing") }
                    }
                }
            }
        }
    }
}
