package com.yodrop.android.ble


import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.os.Build
import com.yodrop.core.protocol.*
import com.yodrop.core.session.BlePeerMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@SuppressLint("MissingPermission")
class AndroidBleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localHello: HelloMessage
) {
    private val txMutex = Mutex()
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val serverPeers = ConcurrentHashMap.newKeySet<String>()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeGattClient: BluetoothGatt? = null

    private val serviceUuid = UUID.fromString(Uuids.SERVICE_UUID_STRING)
    private val writeUuid = UUID.fromString(Uuids.CHAR_WRITE_UUID_STRING)
    private val notifyUuid = UUID.fromString(Uuids.CHAR_NOTIFY_UUID_STRING)

    private var reassembler = BleFragmenter.Reassembler()
    private var txCounter = 0

    private val _incomingMessages = MutableSharedFlow<BlePeerMessage>(replay = 0)
    val incomingMessages = _incomingMessages.asSharedFlow()
    private val decodedMessages = Channel<BlePeerMessage>(Channel.BUFFERED)

    init {
        scope.launch {
            for (message in decodedMessages) _incomingMessages.emit(message)
        }
    }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun startAdvertising(offer: OfferMessage) {
        advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            _errors.tryEmit("Bluetooth advertising is unavailable on this device.")
            return
        }
        openGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Discovery hint only. The complete typed OFFER is sent over GATT after HELLO.
        val serviceData = offer.sessionId.copyOf()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), serviceData)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        // Keep GATT alive for CLAIM/ACCEPT after advertising stops.
    }

    fun startScanning() {
        scanner = adapter?.bluetoothLeScanner ?: run {
            _errors.tryEmit("Bluetooth scanning is unavailable on this device.")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScanning() {
        scanner?.stopScan(scanCallback)
        // Stopping discovery must not disconnect the selected offer peer.
    }

    suspend fun sendControlMessage(peerId: String, message: BleControlMessage) = withContext(Dispatchers.IO) {
        txMutex.withLock {
            val device = checkNotNull(devices[peerId]) { "Unknown BLE peer" }
            val bytes = BleMessageCodec.encode(message)
            val packets = BleFragmenter.fragment(msgId = txCounter++, data = bytes, mtu = 240)
            for (packet in packets) {
                if (peerId in serverPeers) {
                    val server = checkNotNull(gattServer) { "GATT server unavailable" }
                    val char = checkNotNull(server.getService(serviceUuid)?.getCharacteristic(notifyUuid))
                    char.value = packet
                    check(server.notifyCharacteristicChanged(device, char, false)) { "BLE notify could not start" }
                } else {
                    val client = checkNotNull(activeGattClient?.takeIf { it.device.address == peerId }) {
                        "BLE peer is not connected"
                    }
                    val char = checkNotNull(client.getService(serviceUuid)?.getCharacteristic(writeUuid))
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    char.value = packet
                    check(client.writeCharacteristic(char)) { "BLE write could not start" }
                }
                delay(15) // Step 13 replaces throttling with a callback-driven operation queue.
            }
        }
    }

    private fun launchBle(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _errors.emit("Bluetooth operation failed. Please try again.") }
        }
    }

    fun close() {
        runCatching { stopScanning() }
        runCatching { stopAdvertising() }
        runCatching { activeGattClient?.disconnect() }
        runCatching { activeGattClient?.close() }
        activeGattClient = null
        closeGattServer()
        devices.clear()
        serverPeers.clear()
        reassembler = BleFragmenter.Reassembler()
    }

    private fun openGattServer() {
        if (gattServer != null) return
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writeChar = BluetoothGattCharacteristic(
            writeUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val notifyChar = BluetoothGattCharacteristic(
            notifyUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)?.apply {
            addService(service)
        }
    }

    private fun closeGattServer() {
        gattServer?.close()
        gattServer = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
        override fun onStartFailure(errorCode: Int) {
            _errors.tryEmit("Bluetooth advertising failed ($errorCode).")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val sData = record.getServiceData(ParcelUuid(serviceUuid)) ?: return
            if (sData.size >= 16) {
                // If device found, connect GATT to exchange OFFER/CLAIM
                if (activeGattClient == null) {
                    devices[result.device.address] = result.device
                    activeGattClient = result.device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val valid = device != null && characteristic?.uuid == writeUuid &&
                !preparedWrite && offset == 0 && value != null
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset, null)
            }
            if (valid) {
                val peer = checkNotNull(device)
                devices[peer.address] = peer
                serverPeers.add(peer.address)
                val full = reassembler.onPacketReceived(checkNotNull(value))
                if (full != null) parseAndDispatchMessage(peer.address, full)
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                gatt?.device?.let { devices[it.address] = it }
                gatt?.requestMtu(247)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            gatt?.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val service = gatt?.getService(serviceUuid) ?: return
            val notifyChar = service.getCharacteristic(notifyUuid) ?: return
            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                sendHello(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) sendHello(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (Build.VERSION.SDK_INT < 33) {
                characteristic?.value?.let { onNotify(gatt, characteristic, it) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) = onNotify(gatt, characteristic, value)
    }

    private fun sendHello(gatt: BluetoothGatt?) {
        val peer = gatt?.device ?: return
        devices[peer.address] = peer
        launchBle { sendControlMessage(peer.address, localHello) }
    }

    private fun onNotify(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, value: ByteArray) {
        if (characteristic?.uuid != notifyUuid) return
        val peer = gatt?.device ?: return
        devices[peer.address] = peer
        val full = reassembler.onPacketReceived(value)
        if (full != null) parseAndDispatchMessage(peer.address, full)
    }

    private fun parseAndDispatchMessage(peerId: String, bytes: ByteArray) {
        // One discriminator, one decoder; malformed/unknown messages never enter the flow.
        val message = runCatching { BleMessageCodec.decode(bytes) }.getOrNull() ?: return
        if (decodedMessages.trySend(BlePeerMessage(peerId, message)).isFailure) {
            _errors.tryEmit("Bluetooth receive queue is full. Please try again.")
        }
    }
}
