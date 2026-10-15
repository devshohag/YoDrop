package com.yodrop.core.session

import com.yodrop.core.protocol.*
import com.yodrop.core.transport.TransportSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opaque platform peer IDs keep messages bound to the BLE connection carrying the offer/claim. */
data class BlePeerMessage(val peerId: String, val message: BleControlMessage)

class BleHandshakeController(
    private val sender: SenderStateMachine,
    private val receiver: ReceiverStateMachine,
    private val localHello: HelloMessage,
    private val selectTransport: (suspend (HelloMessage) -> TransportSelection)? = null,
    private val prepareLink: (suspend (HelloMessage, TransportSelection) -> LinkInfoMessage?)? = null,
    private val onLinkReady: (suspend (LinkInfoMessage) -> Unit)? = null,
    private val helloSnapshot: (() -> HelloMessage)? = null,
    private val send: suspend (String, BleControlMessage) -> Unit
) {
    private val mutex = Mutex()
    private val hellos = mutableMapOf<String, HelloMessage>()
    private var offerPeer: String? = null
    private var claimedPeer: String? = null
    private val _peerName = MutableStateFlow<String?>(null)
    val peerName = _peerName.asStateFlow()
    private val _selection = MutableStateFlow<TransportSelection?>(null)
    val selection = _selection.asStateFlow()

    suspend fun onMessage(event: BlePeerMessage): Boolean = mutex.withLock {
        val (peer, message) = event
        when (message) {
            is HelloMessage -> {
                if (message.deviceId.size != 16 || peer.isBlank()) return@withLock false
                // Bound unclaimed discovery metadata; this is a single-receiver flow.
                if (peer !in hellos && hellos.size >= 16) return@withLock false
                val firstHello = peer !in hellos
                hellos[peer] = message.copy(deviceId = message.deviceId.copyOf())
                if (firstHello) send(peer, helloSnapshot?.invoke() ?: localHello)
                val advertising = sender.state.value as? SenderState.Advertising
                if (advertising != null) send(peer, advertising.offer)
                true
            }
            is OfferMessage -> {
                val hello = hellos[peer] ?: return@withLock false
                if (sender.state.value !is SenderState.Idle) return@withLock false
                if (!receiver.onOfferDiscovered(message)) return@withLock false
                offerPeer = peer
                _peerName.value = hello.deviceName
                true
            }
            is ClaimMessage -> {
                val hello = hellos[peer] ?: return@withLock false
                if (!sender.onClaimReceived(message)) return@withLock false
                claimedPeer = peer
                _peerName.value = hello.deviceName
                _selection.value = selectTransport?.invoke(hello) ?: sender.selectTransport(
                    localHello.platform, localHello.capabilities, hello)
                true
            }
            is DecisionMessage -> {
                if (peer != claimedPeer) return@withLock false
                // Sender-owned endpoint setup follows explicit consent; no receiver-fabricated IP.
                if (!sender.onDecisionReceived(message)) return@withLock false
                if (message.accepted && prepareLink != null) {
                    val choice = _selection.value ?: TransportSelection.Unavailable
                    val link = prepareLink.invoke(checkNotNull(hellos[peer]), choice)
                    if (link != null) {
                        check(choice is TransportSelection.DataLink && choice.linkType == link.linkType)
                        sender.createEncryptedLinkInfo(link)?.let { send(peer, it) }
                    }
                }
                true
            }
            is EncryptedLinkInfoMessage -> {
                if (peer != offerPeer) return@withLock false
                val link = receiver.onEncryptedLinkInfoReceived(message) ?: return@withLock false
                onLinkReady?.invoke(link)
                true
            }
            is LinkInfoMessage -> false // Plaintext is diagnostic-only and never a session endpoint.
        }
    }

    suspend fun claim(): Boolean = mutex.withLock {
        val peer = offerPeer ?: return@withLock false
        if (receiver.state.value !is ReceiverState.OfferSeen) return@withLock false
        send(peer, receiver.claimOffer())
        true
    }

    suspend fun accept(): Boolean = mutex.withLock {
        val peer = offerPeer ?: return@withLock false
        if (receiver.state.value !is ReceiverState.AcceptPrompt) return@withLock false
        send(peer, receiver.acceptTransfer())
        true
    }

    suspend fun deny(): Boolean = mutex.withLock {
        val peer = offerPeer ?: return@withLock false
        if (receiver.state.value !is ReceiverState.AcceptPrompt) return@withLock false
        send(peer, receiver.denyTransfer("User declined"))
        true
    }

    suspend fun reset() = mutex.withLock {
        offerPeer = null
        claimedPeer = null
        hellos.clear()
        _peerName.value = null
        _selection.value = null
    }
}
