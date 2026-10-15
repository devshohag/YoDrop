package com.yodrop.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.*
import com.yodrop.core.protocol.GattDiagnosticProbe as Probe
import com.yodrop.core.session.BlePeerMessage
import com.yodrop.core.transport.GattOperationKey
import com.yodrop.core.transport.GattOperationQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Mutable link state is confined to Main; platform callbacks are marshalled onto it. */
@SuppressLint("MissingPermission")
class AndroidBleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localHello: HelloMessage,
    private val requestedMtu: Int = 247,
    private val diagnosticsEnabled: Boolean = false
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val serviceUuid = UUID.fromString(Uuids.SERVICE_UUID_STRING)
    private val writeUuid = UUID.fromString(Uuids.CHAR_WRITE_UUID_STRING)
    private val notifyUuid = UUID.fromString(Uuids.CHAR_NOTIFY_UUID_STRING)
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val txMutex = Mutex()
    private var operations = GattOperationQueue()
    private var generation = 0L
    private val links = BlePeerLinks()
    private val target = BleDiscoveryTarget()
    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val serverPeers = mutableSetOf<String>()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var scanSerial = 0L
    private var activeScanCallback: ScanCallback? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverReady = false
    private var activeGattClient: BluetoothGatt? = null
    private var advertisingSession: ByteArray? = null
    private var advertisingJob: Job? = null
    private var clientSetupJob: Job? = null
    private var probeJob: Job? = null
    private var txCounter = 0
    private var probeReply: CompletableDeferred<ByteArray>? = null
    private var expectedProbe: ByteArray? = null

    private val _incomingMessages = MutableSharedFlow<BlePeerMessage>()
    val incomingMessages = _incomingMessages.asSharedFlow()
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()
    private data class Decoded(val generation: Long, val event: BlePeerMessage)
    private val decodedMessages = Channel<Decoded>(Channel.BUFFERED)

    init {
        require(requestedMtu in 23..517)
        scope.launch(Dispatchers.Main.immediate) {
            for (message in decodedMessages) {
                if (message.generation == generation) _incomingMessages.emit(message.event)
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            while (isActive) { delay(1_000); links.expireStale() }
        }
    }

    private fun clientKey(peer: String) = "client:$peer"
    private fun serverKey(peer: String) = "server:$peer"
    private fun key(peer: String, operation: String) = GattOperationKey(peer, operation)
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    private fun launchWork(epoch: Long = generation, block: suspend () -> Unit): Job =
        scope.launch(Dispatchers.Main.immediate) {
            if (epoch != generation) return@launch
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failLink(epoch) }
        }

    private fun failLink(epoch: Long) {
        if (epoch != generation) return
        _errors.tryEmit("Bluetooth connection failed. Reset and try again.")
        close()
    }

    fun startAdvertising(offer: OfferMessage) {
        if (advertisingSession?.contentEquals(offer.sessionId) == true) return
        stopAdvertising()
        val adv = adapter?.bluetoothLeAdvertiser ?: run {
            _errors.tryEmit("Bluetooth advertising is unavailable on this device.")
            return
        }
        advertiser = adv
        val sid = offer.sessionId.copyOf()
        advertisingSession = sid
        val epoch = generation
        val queue = operations
        advertisingJob = launchWork(epoch) {
            ensureServer(epoch, queue)
            if (epoch != generation || advertisingSession?.contentEquals(sid) != true) return@launchWork
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
            // UUID/legacy packet redesign is Step 15. Only the session hint is advertised.
            val data = AdvertiseData.Builder().setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(serviceUuid))
                .addServiceData(ParcelUuid(serviceUuid), sid).build()
            adv.startAdvertising(settings, data, advertiseCallback)
        }
    }

    fun stopAdvertising() {
        advertisingSession = null
        advertiser?.stopAdvertising(advertiseCallback)
        // A service-add callback may still finish; its job checks advertisingSession before starting.
        // Established GATT connections remain alive for CLAIM/ACCEPT.
    }

    fun startScanning() {
        if (scanning) return
        val scan = adapter?.bluetoothLeScanner ?: run {
            _errors.tryEmit("Bluetooth scanning is unavailable on this device.")
            return
        }
        scanner = scan
        target.clear()
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = scanCallback(generation, ++scanSerial)
        activeScanCallback = callback
        scan.startScan(listOf(filter), settings, callback)
    }

    fun stopScanning() {
        scanSerial++
        activeScanCallback?.let { if (scanning) scanner?.stopScan(it) }
        activeScanCallback = null
        scanning = false
        // Keep the pinned peer/GATT link after discovery stops.
    }

    suspend fun sendControlMessage(peerId: String, message: BleControlMessage) =
        withContext(Dispatchers.Main.immediate) {
            val epoch = generation
            val queue = operations
            try {
                txMutex.withLock {
                    check(epoch == generation) { "BLE connection replaced" }
                    val device = checkNotNull(devices[peerId]) { "Unknown BLE peer" }
                    val serverSide = peerId in serverPeers
                    val link = if (serverSide) serverKey(peerId) else clientKey(peerId)
                    check(links.subscribed(link)) { "BLE notifications are not enabled" }
                    val bytes = BleMessageCodec.encode(message)
                    val packets = links.fragments(link, txCounter, bytes)
                    txCounter = (txCounter + 1) and 0xffff
                    for (packet in packets) {
                        check(epoch == generation && links.contains(link)) { "BLE peer disconnected" }
                        val status = if (serverSide) {
                            val server = checkNotNull(gattServer)
                            val char = checkNotNull(server.getService(serviceUuid)?.getCharacteristic(notifyUuid))
                            queue.execute(key(link, "notify")) {
                                check(links.subscribed(link))
                                if (Build.VERSION.SDK_INT >= 33) {
                                    server.notifyCharacteristicChanged(device, char, false, packet) == BluetoothStatusCodes.SUCCESS
                                } else {
                                    char.value = packet
                                    server.notifyCharacteristicChanged(device, char, false)
                                }
                            }
                        } else {
                            val client = checkNotNull(activeGattClient?.takeIf { it.device.address == peerId })
                            val char = checkNotNull(client.getService(serviceUuid)?.getCharacteristic(writeUuid))
                            queue.execute(key(link, "write")) {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    client.writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                                        BluetoothStatusCodes.SUCCESS
                                } else {
                                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    char.value = packet
                                    client.writeCharacteristic(char)
                                }
                            }
                        }
                        check(status == BluetoothGatt.GATT_SUCCESS) { "GATT packet failed: $status" }
                    }
                }
            } catch (cancelled: CancellationException) {
                // The queue poisons abandoned operations; retire the matching GATT generation too.
                if (epoch == generation) close()
                throw cancelled
            } catch (failure: Exception) {
                failLink(epoch)
                throw failure
            }
        }

    fun close() {
        generation++ // Old callback objects cannot acknowledge operations on the replacement queue.
        operations.close()
        operations = GattOperationQueue()
        advertisingJob?.cancel()
        clientSetupJob?.cancel()
        probeJob?.cancel()
        probeReply?.cancel()
        probeReply = null
        expectedProbe = null
        runCatching { stopScanning() }
        runCatching { stopAdvertising() }
        val client = activeGattClient
        activeGattClient = null
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
        runCatching { gattServer?.close() }
        gattServer = null
        serverReady = false
        links.clear()
        target.clear()
        serverPeers.clear()
        devices.clear()
        while (decodedMessages.tryReceive().isSuccess) { /* discard retired generation */ }
    }

    private suspend fun ensureServer(epoch: Long, queue: GattOperationQueue) {
        if (serverReady) return
        check(gattServer == null)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(writeUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        val notify = BluetoothGattCharacteristic(notifyUuid, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        notify.addDescriptor(BluetoothGattDescriptor(cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(notify)
        val server = checkNotNull(bluetoothManager.openGattServer(context, serverCallback(epoch, queue)))
        gattServer = server
        check(queue.execute(key("server", "add-service")) { server.addService(service) } == BluetoothGatt.GATT_SUCCESS)
        check(epoch == generation)
        serverReady = true
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            _errors.tryEmit("Bluetooth advertising failed ($errorCode).")
        }
    }

    private fun scanCallback(scanEpoch: Long, serial: Long) = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            launchWork(scanEpoch) { if (serial == scanSerial) failLink(scanEpoch) }
        }
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val found = result ?: return
            val hint = found.scanRecord?.getServiceData(ParcelUuid(serviceUuid))?.copyOf() ?: return
            val epoch = scanEpoch
            launchWork(epoch) {
                if (serial != scanSerial || !scanning || hint.size != 16 || activeGattClient != null) return@launchWork
                val peer = found.device.address
                if (!target.select(peer, hint)) return@launchWork
                val queue = operations
                activeGattClient = checkNotNull(found.device.connectGatt(context, false,
                    clientCallback(epoch, queue), BluetoothDevice.TRANSPORT_LE))
            }
        }
    }

    private fun serverCallback(epoch: Long, queue: GattOperationQueue) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (service?.uuid == serviceUuid) launchWork(epoch) {
                queue.complete(key("server", "add-service"), status)
            }
        }
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val peer = device ?: return
            launchWork(epoch) {
                val id = peer.address
                val link = serverKey(id)
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (links.contains(link)) return@launchWork
                    if (advertisingSession == null || !links.connect(link)) {
                        gattServer?.cancelConnection(peer)
                    } else {
                        devices[id] = peer
                        serverPeers.add(id)
                    }
                } else if (links.contains(link)) {
                    // Single-send control flow retries explicitly after a disconnect, never on a different peer.
                    failLink(epoch)
                }
            }
        }
        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val peer = device ?: return
            launchWork(epoch) {
                if (links.setMtu(serverKey(peer.address), mtu) && diagnosticsEnabled) {
                    Log.d("YoDropBLE", "server ATT MTU=$mtu")
                }
            }
        }
        override fun onDescriptorReadRequest(
            device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?
        ) {
            val peer = device ?: return
            launchWork(epoch) {
                val link = serverKey(peer.address)
                val valid = links.contains(link) && descriptor?.uuid == cccdUuid &&
                    descriptor.characteristic.uuid == notifyUuid && offset in 0..2
                val value = if (links.subscribed(link)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                gattServer?.sendResponse(peer, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET,
                    offset, if (valid) value.copyOfRange(offset, value.size) else null)
            }
        }
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val peer = device ?: return
            val bytes = value?.copyOf()
            launchWork(epoch) {
                val link = serverKey(peer.address)
                val enable = bytes?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                val disable = bytes?.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == true
                val valid = descriptor?.uuid == cccdUuid && descriptor.characteristic.uuid == notifyUuid &&
                    !preparedWrite && offset == 0 && (enable || disable) && links.subscribe(link, enable)
                if (valid && diagnosticsEnabled) {
                    Log.d("YoDropBLE", "server ATT MTU=${links.mtu(link)}; CCCD enabled=$enable")
                }
                if (responseNeeded) gattServer?.sendResponse(peer, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
        }
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val peer = device ?: return
            val bytes = value?.copyOf()
            launchWork(epoch) {
                val link = serverKey(peer.address)
                val valid = characteristic?.uuid == writeUuid && !preparedWrite && offset == 0 &&
                    links.subscribed(link) && bytes != null &&
                    bytes.size <= BleFragmenter.packetSizeForMtu(links.mtu(link))
                if (responseNeeded) gattServer?.sendResponse(peer, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                if (valid) links.receive(link, checkNotNull(bytes))?.let { dispatch(peer.address, it, epoch) }
            }
        }
        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            val peer = device ?: return
            launchWork(epoch) { queue.complete(key(serverKey(peer.address), "notify"), status) }
        }
    }

    private fun clientCallback(epoch: Long, queue: GattOperationQueue) = object : BluetoothGattCallback() {
        private fun handle(gatt: BluetoothGatt?, block: suspend (BluetoothGatt, String) -> Unit) {
            val client = gatt ?: return
            launchWork(epoch) {
                if (client !== activeGattClient || client.device.address != target.peerId) return@launchWork
                block(client, clientKey(client.device.address))
            }
        }
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) = handle(gatt) { client, link ->
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (links.contains(link)) return@handle
                check(links.connect(link))
                devices[client.device.address] = client.device
                clientSetupJob = launchWork(epoch) {
                    if (requestedMtu > 23) {
                        // Immediate rejection/failure retains MTU 23; a missing callback times out and retires GATT.
                        queue.execute(key(link, "mtu")) { client.requestMtu(requestedMtu) }
                    }
                    check(queue.execute(key(link, "discover")) { client.discoverServices() } == BluetoothGatt.GATT_SUCCESS)
                    val service = checkNotNull(client.getService(serviceUuid))
                    val notify = checkNotNull(service.getCharacteristic(notifyUuid))
                    val cccd = checkNotNull(notify.getDescriptor(cccdUuid)) { "Peer has no notification CCCD" }
                    check(client.setCharacteristicNotification(notify, true))
                    check(queue.execute(key(link, "descriptor")) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            client.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                        } else {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            client.writeDescriptor(cccd)
                        }
                    } == BluetoothGatt.GATT_SUCCESS)
                    links.subscribe(link, true)
                    if (diagnosticsEnabled) Log.d("YoDropBLE", "client ATT MTU=${links.mtu(link)}; CCCD enabled")
                    sendControlMessage(client.device.address, localHello)
                    if (diagnosticsEnabled) probeJob = launchWork(epoch) { runProbe(client.device.address, link) }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                failLink(epoch)
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) = handle(gatt) { _, link ->
            if (status == BluetoothGatt.GATT_SUCCESS) links.setMtu(link, mtu)
            queue.complete(key(link, "mtu"), status)
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) = handle(gatt) { _, link ->
            queue.complete(key(link, "discover"), status)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (descriptor?.uuid == cccdUuid && descriptor.characteristic.uuid == notifyUuid) handle(gatt) { _, link ->
                queue.complete(key(link, "descriptor"), status)
            }
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (characteristic?.uuid == writeUuid) handle(gatt) { _, link -> queue.complete(key(link, "write"), status) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (Build.VERSION.SDK_INT < 33) characteristic?.value?.let { notify(gatt, characteristic, it) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            notify(gatt, characteristic, value)
        private fun notify(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, value: ByteArray) {
            if (characteristic?.uuid != notifyUuid) return
            val copy = value.copyOf()
            handle(gatt) { client, link ->
                links.receive(link, copy)?.let { dispatch(client.device.address, it, epoch) }
            }
        }
    }

    private fun dispatch(peer: String, bytes: ByteArray, epoch: Long) {
        val message = runCatching { BleMessageCodec.decode(bytes) }.getOrNull() ?: return
        if (message is OfferMessage && peer !in serverPeers && !target.matches(peer, message.sessionId)) return
        if (message is LinkInfoMessage && Probe.isProbe(message)) {
            if (diagnosticsEnabled && bytes.size == Probe.SIZE) {
                if (message.extra[Probe.MODE] == "Q") {
                    launchWork(epoch) { sendControlMessage(peer, Probe.reply(message)) }
                } else if (message.extra[Probe.MODE] == "R" && peer == target.peerId && bytes.contentEquals(expectedProbe)) {
                    probeReply?.complete(bytes)
                }
            }
            return // Debug probes never become session LINK_INFO, including in ordinary runs.
        }
        if (decodedMessages.trySend(Decoded(epoch, BlePeerMessage(peer, message))).isFailure) failLink(epoch)
    }

    /** Debug-intent-only diagnostic: one exact 1024-byte typed message in each direction. */
    private suspend fun runProbe(peer: String, link: String) {
        val request = Probe.request(CryptoEngine.randomBytes(16))
        val expected = BleMessageCodec.encode(Probe.reply(request))
        val reply = CompletableDeferred<ByteArray>()
        expectedProbe = expected
        probeReply = reply
        try {
            sendControlMessage(peer, request)
            val received = withTimeoutOrNull(15_000) { reply.await() }
                ?: error("GATT probe reply timed out")
            check(received.contentEquals(expected))
            Log.d("YoDropBLE", "GATT_PROBE PASS requestBytes=1024 replyBytes=1024 actualMtu=${links.mtu(link)}")
        } finally {
            probeReply = null
            expectedProbe = null
        }
    }

}
