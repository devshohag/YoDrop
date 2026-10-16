package com.yodrop.core.transport

import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** No credentials are fabricated if an API returns null or an unsupported/open configuration. */
data class HotspotConfiguration(val ssid: String, val passphrase: String) {
    fun validate() {
        require(ssid.encodeToByteArray().size in 1..32 && ssid.isNotBlank()) { "Missing or invalid hotspot SSID" }
        require(passphrase.length in 8..63 && passphrase.all { it.code in 32..126 }) { "Missing or invalid hotspot passphrase" }
    }
    override fun toString() = "HotspotConfiguration(<redacted>)"
}

data class HotspotInterfaceAddress(
    val interfaceName: String,
    val ipv4: String,
    val prefixLength: Int,
    val eligible: Boolean = true
)

/** Identify a newly assigned downstream address, never an upstream address or guessed subnet. */
object HotspotGatewaySelector {
    private fun valid(address: HotspotInterfaceAddress): Boolean {
        if (!address.eligible || address.interfaceName.isBlank() || address.prefixLength !in 1..30 ||
            !TransportSelector.isSameSubnet(address.ipv4, address.ipv4, 32)) return false
        val bytes = address.ipv4.split('.').map { it.toInt() }
        if (bytes[0] == 0 || bytes[0] == 127 || bytes[0] >= 224 || (bytes[0] == 169 && bytes[1] == 254)) return false
        val ip = bytes.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        val hostMask = (1L shl (32 - address.prefixLength)) - 1
        return (ip and hostMask) != 0L && (ip and hostMask) != hostMask
    }
    fun select(before: List<HotspotInterfaceAddress>, after: List<HotspotInterfaceAddress>): HotspotInterfaceAddress? {
        val old = before.map { it.interfaceName to it.ipv4 }.toSet()
        val candidates = after.filter { valid(it) && (it.interfaceName to it.ipv4) !in old }
            .distinctBy { it.interfaceName to it.ipv4 }
        check(candidates.size <= 1) { "Hotspot interface is ambiguous" }
        return candidates.singleOrNull()
    }
}

interface HotspotLease {
    val configuration: HotspotConfiguration?
    /** Must be idempotent; a late/duplicate platform callback must not retain ownership. */
    fun close()
}
interface HotspotCallbacks {
    fun started(lease: HotspotLease)
    fun failed(reason: Int)
    fun stopped()
}
interface HotspotPlatform {
    suspend fun interfaces(): List<HotspotInterfaceAddress>
    fun start(callbacks: HotspotCallbacks)
}

/** Portable callback/lease lifecycle; late callbacks close immediately, even after caller cancellation. */
class HotspotSession(private val platform: HotspotPlatform, private val timeoutMs: Long = 12_000) : HotspotCallbacks {
    private sealed interface Slot {
        data object Waiting : Slot
        class Active(val lease: HotspotLease) : Slot
        data object Closed : Slot
    }
    private val slot = MutableStateFlow<Slot>(Slot.Waiting)
    private val requested = MutableStateFlow(false)
    private val available = CompletableDeferred<HotspotLease>()
    private val ended = CompletableDeferred<Unit>()
    init { require(timeoutMs > 0) }

    override fun started(lease: HotspotLease) {
        if (slot.compareAndSet(Slot.Waiting, Slot.Active(lease))) available.complete(lease)
        else if ((slot.value as? Slot.Active)?.lease !== lease) lease.close()
    }
    override fun failed(reason: Int) = retire("Local-only hotspot failed ($reason)")
    override fun stopped() = retire("Local-only hotspot stopped")
    private fun retire(reason: String) {
        val previous = slot.getAndUpdate { Slot.Closed }
        try { if (previous is Slot.Active) previous.lease.close() }
        finally { available.completeExceptionally(IllegalStateException(reason)); ended.complete(Unit) }
    }

    suspend fun start(port: Int): HotspotCredentials {
        require(port in 1..65535) { "Invalid hotspot listener port" }
        check(requested.compareAndSet(false, true)) { "Hotspot already requested" }
        try {
            return withTimeoutOrNull(timeoutMs) {
                val before = platform.interfaces()
                check(slot.value === Slot.Waiting) { "Hotspot session is closed" }
                platform.start(this@HotspotSession)
                val lease = available.await()
                val config = checkNotNull(lease.configuration) { "Hotspot configuration is unavailable" }
                config.validate()
                var gateway: HotspotInterfaceAddress? = null
                while (gateway == null) {
                    check(slot.value is Slot.Active) { "Hotspot stopped during setup" }
                    gateway = HotspotGatewaySelector.select(before, platform.interfaces())
                    if (gateway == null) delay(100)
                }
                HotspotCredentials(config.ssid, config.passphrase, gateway.ipv4, port)
            } ?: error("Hotspot configuration/address timed out")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { retire("Hotspot setup cancelled") }
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable) { retire("Hotspot setup failed") }
            // Platform exceptions/config objects may contain secrets; keep the public error fixed.
            throw IllegalStateException("Hotspot setup failed. Check Wi-Fi, Location and permissions.")
        }
    }
    suspend fun awaitStopped() = ended.await()
    suspend fun close() = withContext(NonCancellable) { retire("Hotspot session closed") }
}
