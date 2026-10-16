package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.gesture.*
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
class R2CatchFlowTest {
    private class Fixture(val scope: CoroutineScope, now: () -> Long, capable: Boolean = true) {
        val si = R1TestIdentity(); val ri = R1TestIdentity()
        val st = TrustedPairing(si, TrustedDeviceRegistry(R1TestStorage()), TrustedReceiveWindow(now))
        val receiverStorage = R1TestStorage()
        val rt = TrustedPairing(ri, TrustedDeviceRegistry(receiverStorage), TrustedReceiveWindow(now))
        val sender = SenderStateMachine(scope); val receiver = ReceiverStateMachine(scope)
        val queue = ArrayDeque<Pair<Boolean, BleControlMessage>>()
        var endpoints = 0
        var reads = 0
        var reconnects = 0
        var onReconnect: () -> Unit = {}
        val caps = DeviceCaps.CAP_LAN or if (capable) DeviceCaps.CAP_TRUSTED_PAIRING else 0
        val hello = HelloMessage("Same phone name", Platform.ANDROID, caps, ByteArray(16), "127.0.0.1")
        val sending = BleHandshakeController(sender, ReceiverStateMachine(scope), hello,
            prepareLink = { _, _ -> endpoints++; null }, trustedPairing = st) { _, message -> queue.addLast(true to message) }
        val receiving = BleHandshakeController(SenderStateMachine(scope), receiver, hello,
            onOfferRead = { _, _ -> reads++ }, connectForClaim = { _, _ -> reconnects++; onReconnect() },
            trustedPairing = rt, autoClaimTrustedOffers = true) { _, message -> queue.addLast(false to message) }
        suspend fun selectSender() {
            rt.registry.remember(si.publicKey(), "My sender")
            rt.armReceive(si.fingerprint())
        }
        suspend fun discover() {
            st.registry.refresh(); rt.registry.refresh()
            val bytes = ByteArray(65_536) { it.toByte() }
            sender.onGrabbed(listOf(FileMeta("f", "private.bin", bytes.size.toLong(), "x", CryptoEngine.sha256(bytes))),
                mapOf("f" to MemoryFileSource("f", bytes)))
            receiver.startScanning(); sending.onMessage(BlePeerMessage("R", hello)); pump()
        }
        suspend fun pump() {
            while (queue.isNotEmpty()) {
                val (toReceiver, message) = queue.removeFirst()
                val event = BlePeerMessage(if (toReceiver) "S" else "R", BleMessageCodec.decode(BleMessageCodec.encode(message)))
                if (toReceiver) receiving.onMessage(event) else sending.onMessage(event)
            }
        }
        suspend fun ready(): TrustedCatchTicket { selectSender(); discover(); return assertNotNull(receiving.catchTicket()) }
    }

    @Test fun selectedSenderIsClaimedAndAuthenticatedAutomaticallyButNeverAutoAccepted() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
        assertEquals(1, f.reads); assertEquals(1, f.reconnects)
        assertIs<ReceiverState.AcceptPrompt>(f.receiver.state.value)
        assertEquals(f.si.fingerprint(), ticket.fingerprint); assertEquals(0, f.endpoints)
        assertTrue(f.queue.isEmpty()); assertNotNull(f.rt.receiveWindow.state.value)
    }
    @Test fun catchSendsOneEncryptedConsentAndConsumesTheWindow() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
        assertTrue(f.receiving.catchTransfer(ticket))
        assertIs<EncryptedPairingMessage>(f.queue.single().second)
        assertNull(f.rt.receiveWindow.state.value); assertNull(f.receiving.catchTicket())
        assertFalse(f.receiving.catchTransfer(ticket)); f.pump()
        assertIs<SenderState.Linking>(f.sender.state.value); assertEquals(1, f.endpoints)
    }
    @Test fun freshCatchGestureIsBoundToTheAuthenticatedPrompt() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
        val gate = GestureGate().apply { updateContext(ticket.gestureContext) }
        var time = 0L
        fun hold(pose: HandPose) = (1..4).mapNotNull { gate.observe(pose, time).also { time += 80 } }
        assertTrue(hold(HandPose.PALM).isEmpty()); assertEquals(0, f.endpoints)
        assertTrue(hold(HandPose.FIST).isEmpty())
        val catch = hold(HandPose.PALM).single()
        assertEquals(GestureAction.RELEASE, catch.action); assertEquals(ticket.gestureContext, catch.context)
        assertTrue(f.receiving.catchTransfer(ticket)); assertTrue(hold(HandPose.FIST).isEmpty()); assertTrue(hold(HandPose.PALM).isEmpty())
    }
    @Test fun aFistHeldBeforeAuthenticationCannotJoinAPalmAfterAuthentication() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime })
        val gate = GestureGate().apply { updateContext(GestureContext(GestureMode.OBSERVE, "scan")) }
        repeat(4) { assertNull(gate.observe(HandPose.FIST, it * 80L)) }
        val ticket = f.ready(); gate.updateContext(ticket.gestureContext)
        repeat(4) { assertNull(gate.observe(HandPose.PALM, 320 + it * 80L)) }
        assertEquals(0, f.endpoints); assertTrue(f.queue.isEmpty())
    }
    @Test fun unknownDeviceNeverGetsCatchAndKeepsManualAccept() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.discover()
        assertIs<ReceiverState.OfferSeen>(f.receiver.state.value); assertEquals(0, f.reconnects)
        assertTrue(f.receiving.claim()); f.pump(); assertNull(f.receiving.catchTicket())
        val fake = TrustedCatchTicket("fake", f.receiving.peerIdentity.value!!.sessionToken, f.si.fingerprint())
        assertFalse(f.receiving.catchTransfer(fake)); assertEquals(0, f.endpoints)
        assertTrue(f.receiving.accept()); f.pump(); assertEquals(1, f.endpoints)
    }
    @Test fun pinnedIdentityWithoutAnExplicitSelectedWindowStillNeedsAccept() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime })
        f.rt.registry.remember(f.si.publicKey(), "Sender"); f.discover()
        assertIs<ReceiverState.OfferSeen>(f.receiver.state.value)
        f.receiving.claim(); f.pump(); assertTrue(f.receiving.peerIdentity.value!!.trusted)
        assertNull(f.receiving.catchTicket()); assertEquals(0, f.endpoints)
    }
    @Test fun trustingDuringFirstPairingDoesNotTurnTheCurrentPromptIntoCatch() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); f.discover(); f.receiving.claim(); f.pump()
        assertTrue(f.receiving.rememberPeer(f.receiving.peerIdentity.value!!.sessionToken))
        assertNull(f.receiving.catchTicket()); assertEquals(0, f.endpoints)
    }
    @Test fun identicalDeviceNameWithDifferentSigningKeyCannotCatch() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val other = R1TestIdentity()
        f.rt.registry.remember(other.publicKey(), f.hello.deviceName); f.rt.armReceive(other.fingerprint()); f.discover()
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value); assertNull(f.receiving.catchTicket()); assertEquals(0, f.endpoints)
    }
    @Test fun legacyDeviceCannotUseSelectedCatch() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }, capable = false)
        f.selectSender(); f.discover(); assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
        assertNull(f.receiving.catchTicket()); assertEquals(0, f.reconnects); assertEquals(0, f.endpoints)
    }
    @Test fun catchAtSixtySecondDeadlineIsRejectedWithoutConsentOrEndpoint() = runTest {
        var now = 0L; val f = Fixture(backgroundScope, { now }); val ticket = f.ready()
        now = 60_000
        assertFalse(f.receiving.catchTransfer(ticket)); assertTrue(f.queue.isEmpty()); assertEquals(0, f.endpoints)
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
    }
    @Test fun expiryDuringAutomaticReconnectSendsNoClaim() = runTest {
        var now = 0L; val f = Fixture(backgroundScope, { now }); f.selectSender()
        f.onReconnect = { now = 60_000 }; f.discover()
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value); assertNull(f.receiving.catchTicket())
        assertIs<SenderState.Grabbed>(f.sender.state.value); assertTrue(f.queue.isEmpty()); assertEquals(0, f.endpoints)
    }
    @Test fun backgroundClosureRejectsAnAlreadyQueuedCatch() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
        f.rt.receiveWindow.close(ticket.windowToken)
        assertNull(f.receiving.catchTicket()); assertFalse(f.receiving.catchTransfer(ticket)); assertTrue(f.queue.isEmpty()); assertEquals(0, f.endpoints)
    }
    @Test fun revokedOrUnreadableTrustRejectsAnAlreadyQueuedCatch() = runTest {
        for (unavailable in listOf(false, true)) {
            val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
            if (unavailable) {
                f.receiverStorage.failRead = true
                assertFalse(f.rt.registry.refresh())
            } else f.rt.registry.forget(ticket.fingerprint)
            assertNull(f.receiving.catchTicket()); assertFalse(f.receiving.catchTransfer(ticket)); assertEquals(0, f.endpoints)
        }
    }
    @Test fun forgedWindowSessionOrFingerprintNeverAuthorizesCatch() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
        for (bad in listOf(ticket.copy(windowToken = "other"), ticket.copy(sessionToken = "other"), ticket.copy(fingerprint = "other")))
            assertFalse(f.receiving.catchTransfer(bad))
        assertEquals(0, f.endpoints); assertTrue(f.queue.isEmpty())
        assertTrue(f.receiving.catchTransfer(ticket))
    }
    @Test fun cancelledPromptCannotBeAcceptedByALateCatch() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
        f.receiver.cancel(); assertNull(f.receiving.catchTicket()); assertFalse(f.receiving.catchTransfer(ticket)); assertEquals(0, f.endpoints)
    }
    @Test fun oldTicketCannotAuthorizeAnotherReceiveWindow() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
        f.rt.receiveWindow.close(); f.rt.armReceive(ticket.fingerprint)
        assertNull(f.receiving.catchTicket()); assertFalse(f.receiving.catchTransfer(ticket)); assertEquals(0, f.endpoints)
    }
    @Test fun replayedEncryptedCatchCannotCreateASecondEndpoint() = runTest {
        val f = Fixture(backgroundScope, { testScheduler.currentTime }); val ticket = f.ready()
        f.receiving.catchTransfer(ticket); val consent = f.queue.single().second; f.pump()
        assertFalse(f.sending.onMessage(BlePeerMessage("R", consent))); assertEquals(1, f.endpoints)
    }

    @Test fun authenticatedCatchDrivesRealTcpFileTransferWithoutASeparateAccept() = runBlocking {
        withTimeout(15_000) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var listener: PlatformServerSocket? = null; var accepting: Job? = null
            val sender = SenderStateMachine(owner, onCleanup = { listener?.close(); accepting?.cancelAndJoin() })
            val sink = MemoryFileSink("f"); val receiver = ReceiverStateMachine(owner, sinkFactory = { sink })
            val caps = DeviceCaps.CAP_LAN or DeviceCaps.CAP_TRUSTED_PAIRING
            val hello = HelloMessage("Sender", Platform.ANDROID, caps, ByteArray(16), "127.0.0.1")
            val identity = R1TestIdentity()
            val sc = TrustedPairing(identity, TrustedDeviceRegistry(R1TestStorage()), TrustedReceiveWindow { 0 })
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
                onLinkReady = { receiver.attachConnection(LanTransport().connect(it)) }, trustedPairing = rc,
                autoClaimTrustedOffers = true) { _, message -> queue.addLast(false to BleMessageCodec.encode(message)) }
            suspend fun pump() {
                while (queue.isNotEmpty()) {
                    val (toReceiver, bytes) = queue.removeFirst()
                    if (toReceiver) receiving.onMessage(BlePeerMessage("S", BleMessageCodec.decode(bytes)))
                    else sending.onMessage(BlePeerMessage("R", BleMessageCodec.decode(bytes)))
                }
            }
            try {
                sc.registry.refresh(); rc.registry.remember(identity.publicKey(), "Sender"); rc.armReceive(identity.fingerprint())
                val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
                sender.onGrabbed(listOf(FileMeta("f", "caught.bin", data.size.toLong(), "x", CryptoEngine.sha256(data))), mapOf("f" to MemoryFileSource("f", data)))
                receiver.startScanning(); sending.onMessage(BlePeerMessage("R", hello.copy(deviceName = "Receiver"))); pump()
                assertEquals(0, endpoints); assertTrue(receiving.catchTransfer(assertNotNull(receiving.catchTicket()))); pump()
                assertEquals(SenderState.Done, sender.state.first { it is SenderState.Done || it is SenderState.Failed })
                assertEquals(ReceiverState.Done, receiver.state.first { it is ReceiverState.Done || it is ReceiverState.Failed })
                assertContentEquals(data, sink.toByteArray()); assertEquals(1, endpoints)
                println("R2 TCP: authenticated selected-window Catch -> encrypted consent -> real TCP 1 MiB; SHA-256 matched; no endpoint before Catch")
            } finally { sender.cancel(); receiver.cancel(); listener?.close(); accepting?.cancelAndJoin(); owner.cancel() }
        }
    }
}
