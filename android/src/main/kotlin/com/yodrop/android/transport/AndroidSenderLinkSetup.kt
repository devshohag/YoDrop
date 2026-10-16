package com.yodrop.android.transport

import android.content.Context
import com.yodrop.android.transport.hotspot.AndroidHotspotManager
import com.yodrop.core.platform.PlatformServerSocket
import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.SenderLinkInfoFactory
import com.yodrop.core.transport.TcpConnection
import com.yodrop.core.transport.TransportSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Consent-gated sender listener plus actual LAN or autonomous Wi-Fi Direct group. */
class AndroidSenderLinkSetup(
    private val scope: CoroutineScope,
    private val sender: SenderStateMachine,
    private val network: AndroidSenderTransportSelector,
    private val context: Context
) {
    private val mutex = Mutex()
    private var hotspot: AndroidHotspotManager? = null
    private var hotspotWatchJob: Job? = null
    private var wifiDirect: WifiDirectTransport? = null
    private var recoveryJob: Job? = null
    private var server: PlatformServerSocket? = null
    private var acceptJob: Job? = null
    private var endpoint: LinkInfoMessage? = null

    suspend fun prepare(choice: TransportSelection): LinkInfoMessage? {
        if (choice == TransportSelection.DataLink(LinkType.HOTSPOT)) return prepareHotspot()
        if (choice !is TransportSelection.DataLink || choice.linkType !in setOf(LinkType.LAN, LinkType.WIFI_DIRECT)) return null
        val wfd = if (choice.linkType == LinkType.WIFI_DIRECT) mutex.withLock {
            wifiDirect ?: WifiDirectTransport(context, owner = true).also { wifiDirect = it }
        } else null
        val group = try { wfd?.group() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { sender.cancel("Wi-Fi Direct setup failed. Check Wi-Fi, Location and permissions."); throw failure }
        val address = group?.ownerIp ?: network.activeLanAddress()?.ip ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (sender.state.value !is SenderState.Linking) return@withLock null
                endpoint?.let { return@withLock it }
                val listener = createPlatformServerSocket(0)
                server = listener
                val info = if (wfd != null) wfd.endpoint(checkNotNull(group), listener.localPort)
                    else SenderLinkInfoFactory.lan(address, listener.localPort)
                endpoint = info
                acceptJob = acceptConnections(listener)
                if (choice.linkType == LinkType.LAN) android.util.Log.d("YoDropLink", "DATA_LINK_SELECTED LAN")
                info
            }
        }
    }

    private fun acceptConnections(listener: PlatformServerSocket) = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) sender.attachConnection(TcpConnection(listener.accept()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (sender.state.value is SenderState.Linking || sender.state.value is SenderState.Auth ||
                sender.state.value is SenderState.Transferring) sender.cancel("Data listener failed")
        }
    }

    private suspend fun prepareHotspot(): LinkInfoMessage? {
        val resources = withContext(Dispatchers.IO) {
            mutex.withLock {
                if (sender.state.value !is SenderState.Linking) return@withLock null
                check(server == null && hotspot == null) { "A data link is already active" }
                val listener = createPlatformServerSocket(0)
                val manager = AndroidHotspotManager(context)
                server = listener
                hotspot = manager
                listener to manager
            }
        } ?: return null
        val (listener, manager) = resources
        try {
            val credentials = manager.startLocalHotspot(listener.localPort)
            return mutex.withLock {
                if (sender.state.value !is SenderState.Linking || hotspot !== manager) return@withLock null
                val info = SenderLinkInfoFactory.hotspot(credentials)
                endpoint = info
                acceptJob = acceptConnections(listener)
                hotspotWatchJob = scope.launch {
                    manager.awaitStopped()
                    sender.cancel("Hotspot stopped")
                }
                info
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { sender.cancel("Hotspot setup failed. Check Wi-Fi, Location and permissions."); throw failure }
    }

    fun recover() {
        if (recoveryJob?.isActive == true || wifiDirect == null) return
        recoveryJob = scope.launch {
            while (isActive) {
                if ((sender.state.value as? SenderState.Failed)?.canResume == true) {
                    try { wifiDirect?.group() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The existing session reconnect deadline still applies. */ }
                }
                delay(500) // Continue observing after recovery so a second drop cannot miss a state event.
            }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        recoveryJob?.cancelAndJoin()
        recoveryJob = null
        hotspotWatchJob?.cancelAndJoin()
        hotspotWatchJob = null
        val localHotspot = mutex.withLock { hotspot.also { hotspot = null } }
        localHotspot?.stopLocalHotspot()
        val transport = mutex.withLock { wifiDirect.also { wifiDirect = null } }
        transport?.close()
        val job = mutex.withLock {
            server?.close() // Unblock accept before cancelling/joining its worker.
            server = null
            endpoint = null
            acceptJob.also { acceptJob = null }
        }
        job?.cancelAndJoin()
    }
}
