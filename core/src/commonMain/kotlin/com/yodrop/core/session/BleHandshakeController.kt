package com.yodrop.core.session

import com.yodrop.core.protocol.*
import com.yodrop.core.trust.*
import com.yodrop.core.transport.TransportSelection
import kotlinx.coroutines.CancellationException
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
    private val onOfferRead: (suspend (String, OfferMessage) -> Unit)? = null,
    private val connectForClaim: (suspend (String, OfferMessage) -> Unit)? = null,
    private val trustedPairing: TrustedPairing? = null,
    private val autoClaimTrustedOffers: Boolean = false,
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
    private val _peerIdentity = MutableStateFlow<PeerIdentityStatus?>(null)
    val peerIdentity = _peerIdentity.asStateFlow()
    private class Pending(val peer: String, val channel: PairingChannel, val window: ReceiveWindow?) {
        var proof: PairingControl.Identity? = null
        var localPublicKey: ByteArray? = null
    }
    private var pending: Pending? = null

    private fun negotiatesTrust(peer: HelloMessage?) = trustedPairing != null && peer != null &&
        localHello.capabilities and DeviceCaps.CAP_TRUSTED_PAIRING != 0 &&
        peer.capabilities and DeviceCaps.CAP_TRUSTED_PAIRING != 0

    private suspend fun current(p: Pending): Boolean = pending === p &&
        (if (p.channel.role == PairingRole.SENDER) sender.pairingChannel() else receiver.pairingChannel()) === p.channel

    private suspend fun beginIdentity(peer: String, channel: PairingChannel, window: ReceiveWindow? = null): Boolean {
        val trust = trustedPairing ?: return false
        val p = Pending(peer, channel, window); pending = p; _peerIdentity.value = null
        return try {
            val publicKey = trust.identity.publicKey()
            p.localPublicKey = publicKey.copyOf()
            val proof = PairingControl.Identity(publicKey.copyOf(), trust.identity.sign(channel.identityTranscript(publicKey)))
            check(IdentitySignatures.verify(publicKey, channel.identityTranscript(publicKey), proof.signature))
            if (!current(p)) return false
            if (p.window != null && !trust.receiveWindow.isLive(p.window)) { rejectPairing(p); return false }
            send(peer, channel.seal(proof)); true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { rejectPairing(p); false }
    }
    private suspend fun rejectPairing(p: Pending) {
        _peerIdentity.value = null
        p.window?.let { trustedPairing?.receiveWindow?.close(it.token) }
        if (p.channel.role == PairingRole.SENDER) sender.cancel("Device identity authentication failed")
        else receiver.cancel("Device identity/receive-window authentication failed")
    }

    suspend fun onMessage(event: BlePeerMessage): Boolean = mutex.withLock {
        val (peer, message) = event
        when (message) {
            is HelloMessage -> {
                if (message.deviceId.size != 16 || peer.isBlank()) return@withLock false
                if (peer !in hellos && hellos.size >= 16) return@withLock false
                hellos[peer] = message.copy(deviceId = message.deviceId.copyOf())
                val advertising = sender.state.value as? SenderState.Grabbed
                if (advertising != null) {
                    send(peer, helloSnapshot?.invoke() ?: localHello)
                    send(peer, advertising.offer)
                }
                true
            }
            is OfferMessage -> {
                if (sender.state.value !is SenderState.Idle) return@withLock false
                if (!receiver.onOfferDiscovered(message)) return@withLock false
                offerPeer = peer
                _peerName.value = message.senderName
                onOfferRead?.invoke(peer, message)
                // Read/reconnect authenticates a selected device; it grants no file consent.
                if (autoClaimTrustedOffers && trustedPairing?.requiredWindow != null) claimLocked() else true
            }
            is ClaimMessage -> {
                val hello = hellos[peer] ?: return@withLock false
                if (!sender.onClaimReceived(message)) return@withLock false
                claimedPeer = peer
                _peerName.value = hello.deviceName
                _selection.value = selectTransport?.invoke(hello) ?: sender.selectTransport(
                    localHello.platform, localHello.capabilities, hello)
                if (negotiatesTrust(hello) && !beginIdentity(peer, checkNotNull(sender.pairingChannel()))) return@withLock false
                sender.createEncryptedOfferDetails()?.let { send(peer, it) }
                true
            }
            is EncryptedPairingMessage -> {
                val p = pending ?: return@withLock false
                if (peer != p.peer || !current(p)) return@withLock false
                try {
                    when (val body = p.channel.open(message)) {
                        is PairingControl.Identity -> {
                            check(p.proof == null && p.channel.verifies(body))
                            val fingerprint = IdentityKeys.fingerprint(body.publicKey)
                            val trust = checkNotNull(trustedPairing)
                            val saved = trust.registry.find(fingerprint)
                            if (!current(p)) return@withLock false
                            if (p.window != null) check(saved != null && trust.receiveWindow.permits(p.window, p.channel.token, fingerprint))
                            p.proof = body.copy(publicKey = body.publicKey.copyOf(), signature = body.signature.copyOf())
                            _peerIdentity.value = PeerIdentityStatus(p.channel.token, fingerprint, saved?.label ?: _peerName.value ?: "Nearby device", saved != null,
                                p.channel.trustCode(checkNotNull(p.localPublicKey), body.publicKey))
                            true
                        }
                        is PairingControl.Consent -> {
                            check(p.channel.role == PairingRole.SENDER && p.proof != null)
                            processDecision(peer, DecisionMessage(p.channel.sessionId.copyOf(), body.accepted, body.reason))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { rejectPairing(p); false }
            }
            is DecisionMessage -> {
                if (pending?.peer == peer) return@withLock false // Negotiated peers must authenticate consent.
                processDecision(peer, message)
            }
            is EncryptedOfferDetailsMessage -> {
                val p = pending
                if (peer != offerPeer) return@withLock false
                if (p != null && p.proof == null) { rejectPairing(p); return@withLock false }
                receiver.onEncryptedOfferDetailsReceived(message)
            }
            is EncryptedLinkInfoMessage -> {
                if (peer != offerPeer) return@withLock false
                val link = receiver.onEncryptedLinkInfoReceived(message) ?: return@withLock false
                onLinkReady?.invoke(link)
                true
            }
            is LinkInfoMessage -> false
        }
    }

    private suspend fun processDecision(peer: String, message: DecisionMessage): Boolean {
        if (peer != claimedPeer || !sender.onDecisionReceived(message)) return false
        if (message.accepted && prepareLink != null) {
            val choice = _selection.value ?: TransportSelection.Unavailable
            val link = prepareLink.invoke(checkNotNull(hellos[peer]), choice)
            if (link != null) {
                check(choice is TransportSelection.DataLink && choice.linkType == link.linkType)
                sender.createEncryptedLinkInfo(link)?.let { send(peer, it) }
            }
        }
        return true
    }

    suspend fun claim(): Boolean = mutex.withLock { claimLocked() }

    private suspend fun claimLocked(): Boolean {
        val peer = offerPeer ?: return false
        val offer = (receiver.state.value as? ReceiverState.OfferSeen)?.offer ?: return false
        val trustWindow = trustedPairing?.requiredWindow
        if (trustWindow != null && !negotiatesTrust(hellos[peer])) {
            trustedPairing?.receiveWindow?.close(trustWindow.token)
            receiver.cancel("Selected trusted device does not support authenticated pairing")
            return false
        }
        val claim = receiver.claimOffer()
        try {
            val channel = checkNotNull(receiver.pairingChannel())
            val bound = if (trustWindow != null) trustedPairing?.receiveWindow?.bind(channel.token) else null
            if (trustWindow != null && bound == null) {
                receiver.cancel("Trusted receive window expired"); return false
            }
            connectForClaim?.invoke(peer, offer)
            if (receiver.state.value !is ReceiverState.Claimed || (bound != null && trustedPairing?.receiveWindow?.isLive(bound) != true)) {
                receiver.cancel("Claim/receive window expired before reconnect completed"); return false
            }
            send(peer, claim)
            if (negotiatesTrust(hellos[peer]) && !beginIdentity(peer, channel, bound)) return false
        } catch (cancelled: CancellationException) {
            receiver.cancel("Claim cancelled"); throw cancelled
        } catch (failure: Exception) {
            receiver.cancel("Could not reconnect to sender"); throw failure
        }
        return true
    }

    /** The caller's explicit code-match confirmation is bound to this proof and live prompt. */
    suspend fun rememberPeer(sessionToken: String): Boolean = mutex.withLock {
        val p = pending ?: return@withLock false
        val status = _peerIdentity.value ?: return@withLock false
        if (sessionToken != p.channel.token || status.sessionToken != sessionToken || !current(p) ||
            (sender.state.value !is SenderState.AwaitAccept && receiver.state.value !is ReceiverState.AcceptPrompt)) return@withLock false
        val proof = p.proof ?: return@withLock false
        val saved = trustedPairing?.registry?.remember(proof.publicKey, status.label) == true
        if (saved) _peerIdentity.value = status.copy(trusted = true)
        saved
    }
    suspend fun refreshPeerTrust() = mutex.withLock {
        val status = _peerIdentity.value ?: return@withLock
        _peerIdentity.value = status.copy(trusted = trustedPairing?.registry?.find(status.fingerprint) != null)
    }

    /** Advisory UI snapshot. Calling this never consumes a window or accepts data. */
    fun catchTicket(): TrustedCatchTicket? {
        val trust = trustedPairing ?: return null
        val p = pending ?: return null
        val bound = p.window ?: return null
        val window = trust.receiveWindow.state.value ?: return null
        val identity = _peerIdentity.value ?: return null
        if (receiver.state.value !is ReceiverState.AcceptPrompt || p.channel.role != PairingRole.RECEIVER || p.proof == null ||
            identity.sessionToken != p.channel.token || !identity.trusted || identity.fingerprint != bound.target ||
            window != bound || trust.requiredWindow?.token != bound.token ||
            trust.registry.status.value != TrustStorageStatus.READY || trust.registry.devices.value.none { it.fingerprint == bound.target }) return null
        return TrustedCatchTicket(bound.token, p.channel.token, bound.target)
    }

    /** A fresh deliberate gesture or its tap fallback; never queued from OFFER_SEEN or restored. */
    suspend fun catchTransfer(ticket: TrustedCatchTicket): Boolean = mutex.withLock {
        if (catchTicket() != ticket) return@withLock false
        acceptLocked()
    }

    suspend fun accept(): Boolean = mutex.withLock { acceptLocked() }

    private suspend fun acceptLocked(): Boolean {
        val peer = offerPeer ?: return false
        if (receiver.state.value !is ReceiverState.AcceptPrompt) return false
        val p = pending
        if (p != null && (p.proof == null || !current(p))) return false
        val fingerprint = p?.proof?.let { IdentityKeys.fingerprint(it.publicKey) }
        if (p?.window != null && (fingerprint == null || trustedPairing?.registry?.find(fingerprint) == null ||
                trustedPairing.receiveWindow.permits(p.window, p.channel.token, fingerprint).not())) {
            rejectPairing(p); return false
        }
        val decision = receiver.acceptTransfer()
        if (p?.window != null && !checkNotNull(trustedPairing).receiveWindow.consume(p.window, p.channel.token, checkNotNull(fingerprint))) {
            rejectPairing(p); return false
        }
        send(peer, if (p != null) p.channel.seal(PairingControl.Consent(true)) else decision)
        return true
    }

    suspend fun deny(): Boolean = mutex.withLock {
        val peer = offerPeer ?: return@withLock false
        val prompt = receiver.state.value as? ReceiverState.AcceptPrompt ?: return@withLock false
        val p = pending
        send(peer, if (p != null) p.channel.seal(PairingControl.Consent(false, "User declined"))
            else DecisionMessage(prompt.offer.sessionId.copyOf(), false, "User declined"))
        p?.window?.let { trustedPairing?.receiveWindow?.close(it.token) }
        receiver.denyTransfer("User declined")
        true
    }

    suspend fun reset() = mutex.withLock {
        offerPeer = null; claimedPeer = null; pending = null
        hellos.clear(); _peerName.value = null; _selection.value = null; _peerIdentity.value = null
        trustedPairing?.clearReceiveSession()
    }
}
