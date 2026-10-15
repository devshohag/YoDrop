package com.yodrop.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.yodrop.android.ble.AndroidBleManager
import com.yodrop.android.ui.MainScreen
import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderStateMachine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: AndroidBleManager
    private val senderMachine by lazy { SenderStateMachine(lifecycleScope) }
    private val receiverMachine by lazy { ReceiverStateMachine(lifecycleScope) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (!granted) {
            Toast.makeText(this, "Permissions required for BLE Discovery", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = AndroidBleManager(this, lifecycleScope)
        requestRequiredPermissions()

        // Listen for discovered BLE control messages
        lifecycleScope.launch {
            bleManager.discoveredOffers.collect { offer ->
                receiverMachine.onOfferDiscovered(offer)
            }
        }

        setContent {
            val senderState by senderMachine.state.collectAsState()
            val receiverState by receiverMachine.state.collectAsState()

            MainScreen(
                senderState = senderState,
                receiverState = receiverState,
                onSendTapped = { startSendFlow() },
                onReceiveTapped = { startReceiveFlow() },
                onAcceptTapped = { acceptIncomingTransfer() },
                onDenyTapped = { denyIncomingTransfer() },
                onCancelTapped = {
                    senderMachine.cancel()
                    receiverMachine.cancel()
                }
            )
        }
    }

    private fun startSendFlow() {
        val sampleData = "YoDrop P3 Production Test File".encodeToByteArray()
        val meta = FileMeta(
            id = "f-1",
            name = "yodrop_test.txt",
            size = sampleData.size.toLong(),
            mime = "text/plain",
            sha256 = CryptoEngine.sha256(sampleData)
        )
        senderMachine.onGrabbed(listOf(meta), mapOf(meta.id to sampleData))
        val offer = (senderMachine.state.value as? com.yodrop.core.session.SenderState.Advertising)?.offer
        if (offer != null) {
            bleManager.startAdvertising(offer)
        }
    }

    private fun startReceiveFlow() {
        receiverMachine.startScanning()
        bleManager.startScanning()
    }

    private fun acceptIncomingTransfer() {
        val linkInfo = LinkInfoMessage(LinkType.WIFI_DIRECT, host = "192.168.49.1", port = 8888)
        val decision = receiverMachine.acceptTransfer(linkInfo)
        bleManager.sendControlMessage(null, decision)
    }

    private fun denyIncomingTransfer() {
        val decision = receiverMachine.denyTransfer("Declined by user")
        bleManager.sendControlMessage(null, decision)
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stopAdvertising()
        bleManager.stopScanning()
    }
}
