package com.yodrop.core.transport

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only carried inside authenticated, encrypted LINK_INFO. Never include in logs. */
data class WifiDirectCredentials(val networkName: String, val passphrase: String) {
    fun validate() {
        require(networkName.encodeToByteArray().size in 9..32 &&
            Regex("DIRECT-[A-Za-z0-9]{2}.*").matches(networkName)) { "Invalid Wi-Fi Direct network name" }
        require(passphrase.length in 8..63 && passphrase.all { it.code in 32..126 }) { "Invalid Wi-Fi Direct credential" }
    }
    override fun toString() = "WifiDirectCredentials(<redacted>)"
    companion object {
        fun generate(): WifiDirectCredentials {
            val random = CryptoEngine.randomBytes(24).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            return WifiDirectCredentials("DIRECT-${random.take(2)}-YoDrop-${random.substring(2, 10)}", random.substring(10))
        }
        fun fromLink(link: LinkInfoMessage): WifiDirectCredentials {
            require(link.linkType == LinkType.WIFI_DIRECT) { "Not a Wi-Fi Direct endpoint" }
            link.validate()
            return WifiDirectCredentials(checkNotNull(link.extra["ssid"]), checkNotNull(link.extra["passphrase"])).also { it.validate() }
        }
    }
}

data class WifiDirectGroup(
    val isGroupOwner: Boolean,
    val ownerIp: String,
    val credentials: WifiDirectCredentials,
    val interfaceName: String
) {
    fun validate() {
        credentials.validate()
        require(TransportSelector.isSameSubnet(ownerIp, ownerIp, 32) && ownerIp != "0.0.0.0" && interfaceName.isNotBlank()) {
            "Missing Wi-Fi Direct group address/interface"
        }
    }
    override fun toString() = "WifiDirectGroup(owner=$isGroupOwner, endpoint=<redacted>)"
}

/** Platform callbacks only. Shared code owns role validation, deadlines and resource lifetime. */
interface WifiDirectPlatform {
    suspend fun currentGroup(): WifiDirectGroup?
    suspend fun discoverPeers()
    suspend fun createGroup(credentials: WifiDirectCredentials): WifiDirectGroup
    suspend fun joinGroup(credentials: WifiDirectCredentials): WifiDirectGroup
    suspend fun shutdown()
}

/** One session/role, serialized setup/recovery, cancellable startup and idempotent terminal close. */
class WifiDirectSession(
    private val platform: WifiDirectPlatform,
    private val owner: Boolean,
    private val timeoutMs: Long = 12_000
) {
    private val gate = Mutex()
    private val release = Mutex()
    private var operation: Job? = null
    private var closed = false
    private var released = false
    private var credentials: WifiDirectCredentials? = null
    private var ready: WifiDirectGroup? = null
    init { require(timeoutMs > 0) }

    suspend fun ensureGroup(request: WifiDirectCredentials? = null): WifiDirectGroup {
        try {
            return coroutineScope {
                val job = currentCoroutineContext().job
                val expected = gate.withLock {
                    check(!closed && operation == null) { "Wi-Fi Direct session is closed or busy" }
                    if (credentials == null) credentials = request ?: if (owner) WifiDirectCredentials.generate() else error("Missing group credentials")
                    check(request == null || credentials == request) { "Cannot replace a session's group" }
                    checkNotNull(credentials).also { it.validate(); operation = job }
                }
                try {
                    val group = withTimeoutOrNull(timeoutMs) {
                        val existing = platform.currentGroup()
                        if (existing != null) {
                            check(existing.credentials == expected && existing.isGroupOwner == owner) { "Another Wi-Fi Direct group is active" }
                            existing
                        } else {
                            platform.discoverPeers()
                            if (owner) platform.createGroup(expected) else platform.joinGroup(expected)
                        }
                    } ?: error("Wi-Fi Direct setup timed out")
                    group.validate()
                    check(group.isGroupOwner == owner && group.credentials == expected) { "Unexpected Wi-Fi Direct group/role" }
                    gate.withLock {
                        check(!closed)
                        ready?.let { check(it.ownerIp == group.ownerIp) { "Wi-Fi Direct owner address changed; start a new session" } }
                        ready = group
                    }
                    group
                } finally {
                    // Radio-off recovery is retryable until the state machine's existing 20-second deadline.
                    withContext(NonCancellable) { gate.withLock { if (operation === job) operation = null } }
                }
            }
        } catch (cancelled: CancellationException) {
            // Closing this resource cancels only its operation, not the owning BLE collector.
            currentCoroutineContext().ensureActive()
            if (!gate.withLock { closed }) throw cancelled
            throw IllegalStateException("Wi-Fi Direct session was closed")
        }
    }

    fun linkInfo(group: WifiDirectGroup, port: Int): LinkInfoMessage {
        check(owner && group.isGroupOwner)
        group.validate()
        return LinkInfoMessage(LinkType.WIFI_DIRECT, group.ownerIp, port,
            mapOf("ssid" to group.credentials.networkName, "passphrase" to group.credentials.passphrase)).also { it.validate() }
    }

    suspend fun close() = withContext(NonCancellable) {
        val mine = currentCoroutineContext().job
        val pending = gate.withLock { closed = true; operation }
        if (pending != null && pending !== mine) pending.cancelAndJoin()
        release.withLock {
            if (!released) {
                try { platform.shutdown() }
                finally { released = true; gate.withLock { ready = null; credentials = null } }
            }
        }
    }
}
