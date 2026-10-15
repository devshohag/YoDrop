package com.yodrop.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.yodrop.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*

@SuppressLint("MissingPermission")
class AndroidBleManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeGattClient: BluetoothGatt? = null

    private val serviceUuid = UUID.fromString(Uuids.SERVICE_UUID_STRING)
    private val writeUuid = UUID.fromString(Uuids.CHAR_WRITE_UUID_STRING)
    private val notifyUuid = UUID.fromString(Uuids.CHAR_NOTIFY_UUID_STRING)

    private val reassembler = BleFragmenter.Reassembler()
    private var txCounter = 0

    private val _incomingMessages = MutableSharedFlow<BleControlMessage>(replay = 0)
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _discoveredOffers = MutableSharedFlow<OfferMessage>(replay = 1)
    val discoveredOffers = _discoveredOffers.asSharedFlow()

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun startAdvertising(offer: OfferMessage) {
        advertiser = adapter?.bluetoothLeAdvertiser ?: return
        openGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val offerBytes = ProtocolCodec.cbor.encodeToByteArray(OfferMessage.serializer(), offer)
        // Service Data truncated if too large; full data served through GATT
        val serviceData = if (offerBytes.size <= 24) offerBytes else offer.sessionId

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), serviceData)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        closeGattServer()
    }

    fun startScanning() {
        scanner = adapter?.bluetoothLeScanner ?: return
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
        activeGattClient?.close()
        activeGattClient = null
    }

    fun sendControlMessage(device: BluetoothDevice?, message: BleControlMessage) {
        val bytes = when (message) {
            is HelloMessage -> ProtocolCodec.cbor.encodeToByteArray(HelloMessage.serializer(), message)
            is OfferMessage -> ProtocolCodec.cbor.encodeToByteArray(OfferMessage.serializer(), message)
            is ClaimMessage -> ProtocolCodec.cbor.encodeToByteArray(ClaimMessage.serializer(), message)
            is DecisionMessage -> ProtocolCodec.cbor.encodeToByteArray(DecisionMessage.serializer(), message)
            is LinkInfoMessage -> ProtocolCodec.cbor.encodeToByteArray(LinkInfoMessage.serializer(), message)
        }

        val packets = BleFragmenter.fragment(msgId = txCounter++, data = bytes, mtu = 240)
        scope.launch(Dispatchers.IO) {
            for (pkt in packets) {
                if (gattServer != null && device != null) {
                    val char = gattServer?.getService(serviceUuid)?.getCharacteristic(notifyUuid)
                    if (char != null) {
                        char.value = pkt
                        gattServer?.notifyCharacteristicChanged(device, char, false)
                    }
                } else if (activeGattClient != null) {
                    val service = activeGattClient?.getService(serviceUuid)
                    val char = service?.getCharacteristic(writeUuid)
                    if (char != null) {
                        char.value = pkt
                        activeGattClient?.writeCharacteristic(char)
                    }
                }
                delay(15) // throttle inter-packet BLE gap
            }
        }
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
        override fun onStartFailure(errorCode: Int) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val sData = record.getServiceData(ParcelUuid(serviceUuid)) ?: return
            if (sData.size >= 16) {
                // If device found, connect GATT to exchange OFFER/CLAIM
                if (activeGattClient == null) {
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
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (value != null) {
                val full = reassembler.onPacketReceived(value)
                if (full != null) {
                    parseAndDispatchMessage(full)
                }
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
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
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val value = characteristic?.value ?: return
            val full = reassembler.onPacketReceived(value)
            if (full != null) {
                parseAndDispatchMessage(full)
            }
        }
    }

    private fun parseAndDispatchMessage(bytes: ByteArray) {
        scope.launch {
            try {
                // Determine message by deserializing candidates
                val claim = runCatching { ProtocolCodec.cbor.decodeFromByteArray(ClaimMessage.serializer(), bytes) }.getOrNull()
                if (claim != null) {
                    _incomingMessages.emit(claim)
                    return@launch
                }
                val decision = runCatching { ProtocolCodec.cbor.decodeFromByteArray(DecisionMessage.serializer(), bytes) }.getOrNull()
                if (decision != null) {
                    _incomingMessages.emit(decision)
                    return@launch
                }
                val offer = runCatching { ProtocolCodec.cbor.decodeFromByteArray(OfferMessage.serializer(), bytes) }.getOrNull()
                if (offer != null) {
                    _discoveredOffers.emit(offer)
                    _incomingMessages.emit(offer)
                    return@launch
                }
            } catch (_: Exception) {}
        }
    }
}
