package com.yodrop.core.trust

import com.yodrop.core.crypto.CryptoEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

fun ByteArray.trustHex(): String = buildString(size * 2) {
    val digits = "0123456789abcdef"
    for (byte in this@trustHex) { val n = byte.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) }
}

object IdentityKeys {
    private val prefix = byteArrayOf(0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d,
        0x02, 0x01, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04)
    fun validate(publicKey: ByteArray) {
        require(publicKey.size == 91 && prefix.indices.all { publicKey[it] == prefix[it] }) { "Expected canonical P-256 public key" }
    }
    fun fingerprint(publicKey: ByteArray): String { validate(publicKey); return CryptoEngine.sha256(publicKey).trustHex() }
}

interface LocalDeviceIdentity {
    suspend fun publicKey(): ByteArray
    suspend fun sign(transcript: ByteArray): ByteArray
}

expect object IdentitySignatures {
    fun verify(publicKey: ByteArray, transcript: ByteArray, signature: ByteArray): Boolean
}

@Serializable
data class TrustedDevice(val fingerprint: String, val label: String, val publicKey: ByteArray) {
    fun validate() {
        require(fingerprint == IdentityKeys.fingerprint(publicKey)) { "Invalid pinned identity" }
        require(label.encodeToByteArray().size in 1..256) { "Invalid device label" }
    }
    fun snapshot() = copy(publicKey = publicKey.copyOf())
    override fun toString() = "TrustedDevice(fingerprint=$fingerprint, label=<redacted>, publicKey=<redacted>)"
}

interface TrustedDeviceStorage {
    suspend fun read(): List<TrustedDevice>
    suspend fun write(devices: List<TrustedDevice>)
    suspend fun reset()
}

@OptIn(ExperimentalSerializationApi::class)
object TrustedDeviceCodec {
    const val MAX_DEVICES = 32
    const val MAX_BYTES = 32 * 1024
    @Serializable private data class Record(val version: Int = 1, val devices: List<TrustedDevice>)
    private val cbor = Cbor { ignoreUnknownKeys = false }
    fun validate(devices: List<TrustedDevice>) {
        require(devices.size <= MAX_DEVICES && devices.map { it.fingerprint }.toSet().size == devices.size) { "Invalid trusted-device registry" }
        devices.forEach { it.validate() }
    }
    fun encode(devices: List<TrustedDevice>): ByteArray {
        validate(devices)
        return cbor.encodeToByteArray(Record.serializer(), Record(devices = devices)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): List<TrustedDevice> {
        require(bytes.size in 1..MAX_BYTES)
        val record = cbor.decodeFromByteArray(Record.serializer(), bytes)
        require(record.version == 1); validate(record.devices)
        return record.devices.map { it.snapshot() }
    }
}

enum class TrustStorageStatus { LOADING, READY, UNAVAILABLE }

/** Storage failures never turn unknown peers into trusted peers or overwrite unreadable records. */
class TrustedDeviceRegistry(private val storage: TrustedDeviceStorage) {
    private val mutex = Mutex()
    private val _devices = MutableStateFlow<List<TrustedDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _status = MutableStateFlow(TrustStorageStatus.LOADING)
    val status = _status.asStateFlow()
    suspend fun refresh(): Boolean = mutex.withLock { guarded { load() } }
    private suspend fun load() {
        val records = storage.read(); TrustedDeviceCodec.validate(records)
        _devices.value = records.map { it.snapshot() }; _status.value = TrustStorageStatus.READY
    }
    suspend fun find(fingerprint: String): TrustedDevice? = mutex.withLock {
        if (_status.value != TrustStorageStatus.READY) return@withLock null
        _devices.value.firstOrNull { it.fingerprint == fingerprint }?.snapshot()
    }
    suspend fun remember(publicKey: ByteArray, label: String): Boolean = mutex.withLock { guarded {
        load() // Re-read before mutation; never replace corrupt/inaccessible storage with an empty list.
        val device = TrustedDevice(IdentityKeys.fingerprint(publicKey), label, publicKey.copyOf()).also { it.validate() }
        val updated = _devices.value.filterNot { it.fingerprint == device.fingerprint } + device
        TrustedDeviceCodec.validate(updated); storage.write(updated)
        _devices.value = updated.map { it.snapshot() }
    } }
    suspend fun forget(fingerprint: String): Boolean = mutex.withLock { guarded {
        load(); val updated = _devices.value.filterNot { it.fingerprint == fingerprint }
        storage.write(updated); _devices.value = updated.map { it.snapshot() }
    } }
    suspend fun reset(): Boolean = mutex.withLock { guarded { storage.reset(); load() } }
    private suspend fun guarded(action: suspend () -> Unit): Boolean = try { action(); true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _devices.value = emptyList(); _status.value = TrustStorageStatus.UNAVAILABLE; false }
}
