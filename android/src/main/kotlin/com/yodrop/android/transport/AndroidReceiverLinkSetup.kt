package com.yodrop.android.transport

import android.content.Context
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.transport.LanTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Called only with decrypted LINK_INFO after explicit ACCEPT. */
class AndroidReceiverLinkSetup(
    private val context: Context,
    private val scope: CoroutineScope,
    private val receiver: ReceiverStateMachine
) {
    private val mutex = Mutex()
    private var wifiDirect: WifiDirectTransport? = null
    private var endpoint: LinkInfoMessage? = null
    private var recoveryJob: Job? = null

    suspend fun prepare(link: LinkInfoMessage) {
        if (link.linkType == LinkType.LAN) {
            receiver.attachConnection(LanTransport().connect(link))
            return
        }
        if (link.linkType != LinkType.WIFI_DIRECT) return // Hotspot is Step 17.
        mutex.withLock {
            check(receiver.state.value is ReceiverState.Linking)
            endpoint = link.copy(extra = link.extra.toMap())
            wifiDirect = WifiDirectTransport(context)
        }
        try { connectWorkers() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            receiver.cancel("Wi-Fi Direct connection failed. Check Wi-Fi, Location and permissions.")
            throw failure
        }
    }

    private suspend fun connectWorkers() {
        val (transport, link) = mutex.withLock { checkNotNull(wifiDirect) to checkNotNull(endpoint) }
        transport.group(link)
        // Shared receiver assigns missing chunks across authenticated sockets, including after reconnect.
        coroutineScope {
            repeat(4) {
                launch {
                    val connection = transport.connect(link)
                    var handedOff = false
                    try { receiver.attachConnection(connection); handedOff = true }
                    finally { if (!handedOff) withContext(NonCancellable) { connection.close() } }
                }
            }
        }
    }

    fun recover() {
        if (recoveryJob?.isActive == true || wifiDirect == null) return
        recoveryJob = scope.launch {
            while (isActive) {
                if ((receiver.state.value as? ReceiverState.Failed)?.canResume == true) {
                    try { connectWorkers() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keys/part/bitmap survive until the state-machine deadline. */ }
                }
                delay(500) // Terminal cleanup cancels this observer; repeated radio drops remain observable.
            }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        recoveryJob?.cancelAndJoin()
        recoveryJob = null
        val transport = mutex.withLock { endpoint = null; wifiDirect.also { wifiDirect = null } }
        transport?.close()
    }
}
