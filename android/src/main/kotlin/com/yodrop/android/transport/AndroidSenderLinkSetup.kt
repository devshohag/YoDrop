package com.yodrop.android.transport

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

/** Builds a real LAN endpoint. Actual WFD groups/hotspot reservations arrive in Steps 16/17. */
class AndroidSenderLinkSetup(
    private val scope: CoroutineScope,
    private val sender: SenderStateMachine,
    private val network: AndroidSenderTransportSelector
) {
    private val mutex = Mutex()
    private var server: PlatformServerSocket? = null
    private var acceptJob: Job? = null
    private var endpoint: LinkInfoMessage? = null

    suspend fun prepare(choice: TransportSelection): LinkInfoMessage? {
        if (choice != TransportSelection.DataLink(LinkType.LAN)) return null
        val address = network.activeLanAddress() ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (sender.state.value !is SenderState.Linking) return@withLock null
                endpoint?.let { return@withLock it }
                val listener = createPlatformServerSocket(0)
                server = listener
                val info = SenderLinkInfoFactory.lan(address.ip, listener.localPort)
                endpoint = info
                acceptJob = scope.launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val connection = TcpConnection(listener.accept())
                            if (!sender.attachConnection(connection)) break
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (sender.state.value is SenderState.Linking || sender.state.value is SenderState.Auth ||
                            sender.state.value is SenderState.Transferring) sender.cancel("LAN listener failed")
                    }
                }
                info
            }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        val job = mutex.withLock {
            server?.close() // Unblock accept before cancelling/joining its worker.
            server = null
            endpoint = null
            acceptJob.also { acceptJob = null }
        }
        job?.cancelAndJoin()
    }
}
