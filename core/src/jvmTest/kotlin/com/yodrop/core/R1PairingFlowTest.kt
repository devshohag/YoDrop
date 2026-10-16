package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.*
import com.yodrop.core.trust.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R1PairingFlowTest {
    private class Fixture(
        val scope: CoroutineScope, now: () -> Long,
        val si: R1TestIdentity = R1TestIdentity(), val ri: R1TestIdentity = R1TestIdentity(),
        val ss: R1TestStorage = R1TestStorage(), val rs: R1TestStorage = R1TestStorage(),
        capable: Boolean = true
    ) {
        val st = TrustedPairing(si, TrustedDeviceRegistry(ss), TrustedReceiveWindow(now))
        val rt = TrustedPairing(ri, TrustedDeviceRegistry(rs), TrustedReceiveWindow(now))
        val sender = SenderStateMachine(scope)
        val receiver = ReceiverStateMachine(scope)
        var setups = 0
        val caps = DeviceCaps.CAP_LAN or if (capable) DeviceCaps.CAP_TRUSTED_PAIRING else 0
        val helloS = HelloMessage("Phone", Platform.ANDROID, caps, ByteArray(16) { 1 }, "127.0.0.1")
        val helloR = helloS.copy(deviceId = ByteArray(16) { 2 })
        var onReconnect: suspend () -> Unit = {}
        val queue = ArrayDeque<Pair<Boolean, BleControlMessage>>()
        val sending = BleHandshakeController(sender, ReceiverStateMachine(scope), helloS,
            prepareLink = { _, _ -> setups++; null }, trustedPairing = st) { _, msg -> queue.addLast(true to msg) }
        val receiving = BleHandshakeController(SenderStateMachine(scope), receiver, helloR,
            trustedPairing = rt, connectForClaim = { _, _ -> onReconnect() }) { _, msg -> queue.addLast(false to msg) }
        suspend fun discover() {
            st.registry.refresh(); rt.registry.refresh()
            val data = ByteArray(65_536) { it.toByte() }
            sender.onGrabbed(listOf(FileMeta("f", "private.bin", data.size.toLong(), "x", CryptoEngine.sha256(data))), mapOf("f" to MemoryFileSource("f", data)))
            receiver.startScanning(); sending.onMessage(BlePeerMessage("R", helloR)); pump()
        }
        suspend fun pump() {
            while (queue.isNotEmpty()) {
                val (toReceiver, message) = queue.removeFirst()
                val event = BlePeerMessage(if (toReceiver) "S" else "R", BleMessageCodec.decode(BleMessageCodec.encode(message)))
                if (toReceiver) receiving.onMessage(event) else sending.onMessage(event)
            }
        }
        suspend fun handshake() { discover(); assertTrue(receiving.claim()); pump() }
    }
    @Test fun unknownProofsReachPromptButNeverLinkWithoutExplicitAccept() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.handshake()
        val prompt = assertIs<ReceiverState.AcceptPrompt>(f.receiver.state.value)
        assertEquals(assertIs<SenderState.AwaitAccept>(f.sender.state.value).verifyCode, prompt.verifyCode)
        assertFalse(assertNotNull(f.sending.peerIdentity.value).trusted)
        assertFalse(assertNotNull(f.receiving.peerIdentity.value).trusted)
        assertEquals(f.sending.peerIdentity.value!!.pairingCode, f.receiving.peerIdentity.value!!.pairingCode)
        assertEquals(0, f.setups)
        assertTrue(f.receiving.accept()); f.pump(); assertEquals(1, f.setups)
        assertIs<SenderState.Linking>(f.sender.state.value)
    }
    @Test fun bothOwnersMustPinSeparatelyAndPinsAuthenticateANewSession() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.handshake()
        assertTrue(f.sending.rememberPeer(assertNotNull(f.sending.peerIdentity.value).sessionToken))
        assertTrue(assertNotNull(f.sending.peerIdentity.value).trusted)
        assertTrue(f.rt.registry.devices.value.isEmpty())
        assertTrue(f.receiving.rememberPeer(assertNotNull(f.receiving.peerIdentity.value).sessionToken))
        val next = Fixture(backgroundScope, { testScheduler.currentTime }, f.si, f.ri, f.ss, f.rs)
        next.handshake()
        assertTrue(assertNotNull(next.sending.peerIdentity.value).trusted)
        assertTrue(assertNotNull(next.receiving.peerIdentity.value).trusted)
        assertNotEquals(f.receiving.peerIdentity.value!!.sessionToken, next.receiving.peerIdentity.value!!.sessionToken)
        assertEquals(0, next.setups)
    }
    @Test fun sameHelloNameAndIdWithAChangedSigningKeyNeverInheritsTrust() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.handshake()
        f.receiving.rememberPeer(f.receiving.peerIdentity.value!!.sessionToken)
        val changed = Fixture(backgroundScope, { testScheduler.currentTime }, R1TestIdentity(), f.ri, R1TestStorage(), f.rs)
        changed.handshake()
        assertFalse(assertNotNull(changed.receiving.peerIdentity.value).trusted)
        assertNotEquals(f.receiving.peerIdentity.value!!.fingerprint, changed.receiving.peerIdentity.value!!.fingerprint)
        assertEquals(1, changed.rt.registry.devices.value.size)
    }
    @Test fun plaintextAcceptCannotBypassNegotiatedAuthenticatedConsent() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.handshake()
        val offer = assertIs<ReceiverState.AcceptPrompt>(f.receiver.state.value).offer
        assertFalse(f.sending.onMessage(BlePeerMessage("R", DecisionMessage(offer.sessionId, true))))
        assertFalse(f.sending.onMessage(BlePeerMessage("R", DecisionMessage(offer.sessionId, false))))
        assertIs<SenderState.AwaitAccept>(f.sender.state.value); assertEquals(0, f.setups)
    }
    @Test fun secureDenyCreatesNoEndpointAndCannotBecomeAccept() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.handshake()
        assertTrue(f.receiving.deny()); assertIs<EncryptedPairingMessage>(f.queue.single().second)
        f.pump(); assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
        assertIs<SenderState.Failed>(f.sender.state.value); assertEquals(0, f.setups)
    }
    @Test fun validTrustedWindowStillRequiresAcceptInR1AndIsConsumedOnce() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime })
        f.rt.registry.remember(f.si.publicKey(), "Pinned sender")
        f.rt.armReceive(f.si.fingerprint()); f.handshake()
        assertTrue(f.receiving.peerIdentity.value!!.trusted)
        assertIs<ReceiverState.AcceptPrompt>(f.receiver.state.value); assertEquals(0, f.setups)
        assertTrue(f.receiving.accept()); assertNull(f.rt.receiveWindow.state.value)
        assertFalse(f.receiving.accept()); f.pump(); assertEquals(1, f.setups)
    }
    @Test fun wrongDeviceCannotUseSelectedTrustedReceiveWindowEvenWithSameName() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime })
        val selected = R1TestIdentity(); f.rt.registry.remember(selected.publicKey(), "Phone")
        f.rt.armReceive(selected.fingerprint()); f.handshake()
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
        assertNull(f.receiving.peerIdentity.value); assertFalse(f.receiving.accept()); assertEquals(0, f.setups)
    }
    @Test fun expiredWindowCannotAuthorizeAfterPeerAuthentication() = runTest {
        var now = 0L; val f = Fixture(backgroundScope, { now })
        f.rt.registry.remember(f.si.publicKey(), "Sender"); f.rt.armReceive(f.si.fingerprint()); f.handshake()
        now = 60_000; assertFalse(f.receiving.accept()); assertEquals(0, f.setups)
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
    }
    @Test fun closedWindowBeforeClaimNeverFallsBackToOrdinaryAccept() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime })
        f.rt.registry.remember(f.si.publicKey(), "Sender"); f.rt.armReceive(f.si.fingerprint()); f.discover()
        f.rt.receiveWindow.close(); assertFalse(f.receiving.claim())
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value); assertEquals(0, f.setups)
    }
    @Test fun expiryDuringGattReconnectCannotSendALateClaim() = runTest {
        var now = 0L; val f = Fixture(backgroundScope, { now })
        f.rt.registry.remember(f.si.publicKey(), "Sender"); f.rt.armReceive(f.si.fingerprint()); f.discover()
        f.onReconnect = { now = 60_000 }
        assertFalse(f.receiving.claim()); assertTrue(f.queue.isEmpty())
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value); assertEquals(0, f.setups)
    }
    @Test fun explicitlySavedTrustSurvivesDenyWithoutAcceptingAnyData() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.handshake()
        f.receiving.rememberPeer(f.receiving.peerIdentity.value!!.sessionToken)
        f.receiving.deny(); f.pump()
        assertNotNull(f.rt.registry.find(f.si.fingerprint()))
        assertEquals(0, f.setups); assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
    }
    @Test fun revokingAPinBeforeAcceptStopsTrustedWindowConsent() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime })
        f.rt.registry.remember(f.si.publicKey(), "Sender"); f.rt.armReceive(f.si.fingerprint()); f.handshake()
        f.rt.registry.forget(f.si.fingerprint()); assertFalse(f.receiving.accept())
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value); assertEquals(0, f.setups)
    }
    @Test fun staleTrustConfirmationAfterTimeoutOrForAnotherSessionDoesNotPersist() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.handshake()
        val token = f.receiving.peerIdentity.value!!.sessionToken
        assertFalse(f.receiving.rememberPeer("stale-token"))
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertFalse(f.receiving.rememberPeer(token)); assertTrue(f.rt.registry.devices.value.isEmpty())
    }
    @Test fun legacyPeerRemainsManualAndCannotBeTrustedOrUseTrustedWindow() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }, capable = false); f.handshake()
        assertNull(f.receiving.peerIdentity.value); assertFalse(f.receiving.rememberPeer("anything"))
        assertTrue(f.receiving.accept()); assertIs<DecisionMessage>(f.queue.single().second)
        f.pump(); assertEquals(1, f.setups)
        val second = Fixture(backgroundScope, { testScheduler.currentTime }, capable = false)
        second.rt.registry.remember(second.si.publicKey(), "Sender"); second.rt.armReceive(second.si.fingerprint())
        second.discover(); assertFalse(second.receiving.claim()); assertIs<ReceiverState.Cancelled>(second.receiver.state.value)
    }
    @Test fun foreignPeerCannotSupplyAnIdentityOrAConsentEnvelope() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.discover(); f.receiving.claim()
        val claim = f.queue.removeFirst(); f.sending.onMessage(BlePeerMessage("R", claim.second))
        val receiverProof = assertIs<EncryptedPairingMessage>(f.queue.first().second)
        assertFalse(f.sending.onMessage(BlePeerMessage("foreign", receiverProof)))
        f.pump(); assertNotNull(f.sending.peerIdentity.value)
    }
    @Test fun cryptographicallyInvalidIdentityCannotGainTrustOrReachDataLink() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.discover(); f.receiving.claim()
        f.sending.onMessage(BlePeerMessage("R", f.queue.removeFirst().second))
        f.queue.removeFirst() // Drop receiver's genuine identity proof, keep sender messages queued.
        val channel = assertNotNull(f.receiver.pairingChannel())
        val publicKey = f.ri.publicKey(); val impostor = R1TestIdentity()
        val bad = channel.seal(PairingControl.Identity(publicKey, impostor.sign(channel.identityTranscript(publicKey))))
        assertFalse(f.sending.onMessage(BlePeerMessage("R", bad)))
        assertIs<SenderState.Cancelled>(f.sender.state.value); assertNull(f.sending.peerIdentity.value)
        assertFalse(f.sending.rememberPeer(channel.token)); assertTrue(f.st.registry.devices.value.isEmpty()); assertEquals(0, f.setups)
    }

    @Test fun authenticatedNewHandshakeAndConsentDriveRealTcpFileTransfer() = runBlocking {
        withTimeout(15_000) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var listener: PlatformServerSocket? = null; var accepting: Job? = null
            val sender = SenderStateMachine(owner, onCleanup = { listener?.close(); accepting?.cancelAndJoin() })
            val sink = MemoryFileSink("f"); val receiver = ReceiverStateMachine(owner, sinkFactory = { sink })
            val caps = DeviceCaps.CAP_LAN or DeviceCaps.CAP_TRUSTED_PAIRING
            val hello = HelloMessage("Sender", Platform.ANDROID, caps, ByteArray(16), "127.0.0.1")
            val sc = TrustedPairing(R1TestIdentity(), TrustedDeviceRegistry(R1TestStorage()), TrustedReceiveWindow { 0 })
            val rc = TrustedPairing(R1TestIdentity(), TrustedDeviceRegistry(R1TestStorage()), TrustedReceiveWindow { 0 })
            val queue = ArrayDeque<Pair<Boolean, ByteArray>>()
            var endpoints = 0
            val sending = BleHandshakeController(sender, ReceiverStateMachine(owner), hello,
                selectTransport = { sender.selectTransport(Platform.ANDROID, caps, it, LanAddress("127.0.0.1", 8), it.lanIp) },
                prepareLink = { _, _ ->
                    endpoints++; val server = createPlatformServerSocket(0); listener = server
                    accepting = owner.launch { sender.attachConnection(TcpConnection(server.accept())) }
                    SenderLinkInfoFactory.lan("127.0.0.1", server.localPort)
                }, trustedPairing = sc) { _, message -> queue.addLast(true to BleMessageCodec.encode(message)) }
            val receiving = BleHandshakeController(SenderStateMachine(owner), receiver, hello.copy(deviceName = "Receiver"),
                onLinkReady = { receiver.attachConnection(LanTransport().connect(it)) }, trustedPairing = rc) { _, message -> queue.addLast(false to BleMessageCodec.encode(message)) }
            suspend fun pump() {
                while (queue.isNotEmpty()) {
                    val (toReceiver, bytes) = queue.removeFirst()
                    if (toReceiver) receiving.onMessage(BlePeerMessage("S", BleMessageCodec.decode(bytes)))
                    else sending.onMessage(BlePeerMessage("R", BleMessageCodec.decode(bytes)))
                }
            }
            try {
                sc.registry.refresh(); rc.registry.refresh()
                val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
                val meta = FileMeta("f", "real.bin", data.size.toLong(), "x", CryptoEngine.sha256(data))
                sender.onGrabbed(listOf(meta), mapOf("f" to MemoryFileSource("f", data)))
                receiver.startScanning(); sending.onMessage(BlePeerMessage("R", hello.copy(deviceName = "Receiver")))
                pump(); receiving.claim(); pump()
                assertIs<ReceiverState.AcceptPrompt>(receiver.state.value); assertEquals(0, endpoints)
                assertTrue(receiving.rememberPeer(receiving.peerIdentity.value!!.sessionToken))
                receiving.accept(); pump()
                assertEquals(SenderState.Done, sender.state.first { it is SenderState.Done || it is SenderState.Failed })
                assertEquals(ReceiverState.Done, receiver.state.first { it is ReceiverState.Done || it is ReceiverState.Failed })
                assertContentEquals(data, sink.toByteArray()); assertEquals(1, endpoints)
                println("R1 TCP: signed session identities + encrypted consent + LINK_INFO -> real TCP 1 MiB; SHA-256 matched; no endpoint before Accept")
            } finally { sender.cancel(); receiver.cancel(); listener?.close(); accepting?.cancelAndJoin(); owner.cancel() }
        }
    }
}
