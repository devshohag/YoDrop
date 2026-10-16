package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.MemoryFileSink
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.LoopbackConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step18ForegroundTest {
    private val offer = OfferMessage(ByteArray(16), ByteArray(32), 1, 3, "Sender")
    private val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234)
    private class Platform : ForegroundServicePort {
        var starts = 0
        var stops = 0
        var failStart = false
        override fun start() { starts++; if (failStart) error("Foreground start denied") }
        override fun stop() { stops++ }
    }

    @Test fun everySenderStateHasExplicitServiceAndPowerPolicy() {
        val rows = listOf(
            Triple(SenderState.Idle, false, false), Triple(SenderState.Grabbed(offer), true, false),
            Triple(SenderState.Claimed, true, false), Triple(SenderState.AwaitAccept("123456"), true, false),
            Triple(SenderState.Linking(link), true, true), Triple(SenderState.Auth, true, true),
            Triple(SenderState.Transferring(.5f, "file"), true, true), Triple(SenderState.Done, false, false),
            Triple(SenderState.Cancelled("cancel"), false, false), Triple(SenderState.Failed("error"), false, false),
            Triple(SenderState.Failed("lost", true), true, true))
        for ((state, service, power) in rows) {
            assertEquals(service, ForegroundSessionPolicy.required(state, ReceiverState.Idle), "$state")
            assertEquals(power, ForegroundSessionPolicy.powerRequired(state, ReceiverState.Idle), "$state")
        }
    }

    @Test fun everyReceiverStateHasExplicitServiceAndPowerPolicy() {
        val rows = listOf(
            Triple(ReceiverState.Idle, false, false), Triple(ReceiverState.Scanning, true, false),
            Triple(ReceiverState.OfferSeen(offer), true, false), Triple(ReceiverState.Claimed, true, false),
            Triple(ReceiverState.AcceptPrompt(offer, "123456", "private.txt"), true, false),
            Triple(ReceiverState.Linking(link), true, true), Triple(ReceiverState.Auth, true, true),
            Triple(ReceiverState.Receiving(.5f, "file"), true, true), Triple(ReceiverState.Verifying("file"), true, true),
            Triple(ReceiverState.Done, false, false), Triple(ReceiverState.Cancelled("cancel"), false, false),
            Triple(ReceiverState.Failed("error"), false, false), Triple(ReceiverState.Failed("lost", true), true, true))
        for ((state, service, power) in rows) {
            assertEquals(service, ForegroundSessionPolicy.required(SenderState.Idle, state), "$state")
            assertEquals(power, ForegroundSessionPolicy.powerRequired(SenderState.Idle, state), "$state")
        }
    }

    @Test fun terminalPeerCannotStopAnActiveOtherRole() {
        val p = Platform(); val controller = ForegroundSession(p)
        controller.begin()
        controller.update(SenderState.Done, ReceiverState.Receiving(0f, "file"))
        controller.update(SenderState.Failed("lost", true), ReceiverState.Cancelled("cancel"))
        assertEquals(0, p.stops)
        controller.update(SenderState.Failed("expired"), ReceiverState.Cancelled("cancel"))
        assertEquals(1, p.stops)
    }

    @Test fun sdkCutoversRequestOnlyApplicablePermissions() {
        fun names(vararg s: String) = s.map { "android.permission.$it" }.toSet()
        for (api in 29..35) {
            val expected = mutableSetOf<String>()
            if (api <= 32) expected += names("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION")
            if (api >= 31) expected += names("BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT")
            if (api >= 33) expected += names("NEARBY_WIFI_DEVICES")
            val plan = TransferPermissions.forApi(api)
            assertEquals(expected, plan.required, "API $api")
            assertEquals(if (api >= 33) names("POST_NOTIFICATIONS") else emptySet(), plan.optional)
        }
    }

    @Test fun notificationDenialDoesNotBlockTransfer() {
        for (api in 33..35) {
            val plan = TransferPermissions.forApi(api)
            assertTrue(plan.mayProceed(plan.required))
            assertTrue(plan.mayProceed(plan.required + plan.optional))
        }
    }

    @Test fun everyMissingRequiredPermissionBlocksTransfer() {
        for (api in 29..35) {
            val plan = TransferPermissions.forApi(api)
            for (denied in plan.required) assertFalse(plan.mayProceed(plan.required + plan.optional - denied), "$api $denied")
        }
        assertFailsWith<IllegalArgumentException> { TransferPermissions.forApi(28) }
    }

    @Test fun startStopAreIdempotentAndObserverNeverLaunchesFromBackground() {
        val p = Platform(); val c = ForegroundSession(p)
        c.update(SenderState.Grabbed(offer), ReceiverState.Idle)
        assertEquals(0, p.starts)
        repeat(3) { c.begin() }
        assertEquals(1, p.starts)
        repeat(3) { c.update(SenderState.Done, ReceiverState.Idle) }
        assertEquals(1, p.stops)
        c.update(SenderState.Grabbed(offer), ReceiverState.Idle)
        assertEquals(1, p.starts)
        c.begin()
        assertEquals(2, p.starts)
    }

    @Test fun deniedForegroundStartCanBeRetriedFromAnotherUserAction() {
        val p = Platform(); val c = ForegroundSession(p)
        p.failStart = true
        assertFailsWith<IllegalStateException> { c.begin() }
        c.update(SenderState.Idle, ReceiverState.Idle)
        assertEquals(0, p.stops)
        p.failStart = false
        c.begin()
        c.update(SenderState.Cancelled("cancel"), ReceiverState.Idle)
        assertEquals(2, p.starts); assertEquals(1, p.stops)
    }

    private inner class Fixture(scope: CoroutineScope, verify: Boolean = true) {
        val platform = Platform()
        val foreground = ForegroundSession(platform)
        val sender = SenderStateMachine(scope, timeouts = Timeouts(offerMs = 100, linkingMs = 200, socketIdleMs = 100, pingIntervalMs = 1000))
        val sink = MemoryFileSink("file")
        val receiver = ReceiverStateMachine(scope, sinkFactory = {
            object : com.yodrop.core.platform.FileSink by sink {
                override suspend fun verifySha256(expectedSha256: ByteArray) = verify && sink.verifySha256(expectedSha256)
            }
        }, timeouts = Timeouts(claimWindowMs = 100, linkingMs = 200))
        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                combine(sender.state, receiver.state) { a, b -> a to b }.collect { (a, b) -> foreground.update(a, b) }
            }
        }
        suspend fun grab() {
            foreground.begin()
            val bytes = byteArrayOf(1, 2, 3)
            val meta = FileMeta("file", "file.dat", 3, "application/octet-stream", CryptoEngine.sha256(bytes))
            assertTrue(sender.onGrabbed(listOf(meta), mapOf("file" to MemoryFileSource("file", bytes))))
        }
        suspend fun link() {
            grab()
            receiver.startScanning()
            receiver.onOfferDiscovered(assertIs<SenderState.Grabbed>(sender.state.value).offer)
            sender.onClaimReceived(receiver.claimOffer())
            receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))
            sender.onDecisionReceived(receiver.acceptTransfer(link), link)
        }
    }

    @Test fun realSenderOfferExpiryStopsService() = runTest {
        val f = Fixture(backgroundScope); f.grab(); runCurrent()
        assertEquals(0, f.platform.stops)
        advanceTimeBy(100); runCurrent()
        assertFalse(assertIs<SenderState.Failed>(f.sender.state.value).canResume)
        assertEquals(1, f.platform.stops)
    }

    @Test fun realReceiverClaimWindowExpiryStopsService() = runTest {
        val f = Fixture(backgroundScope)
        f.foreground.begin(); f.receiver.startScanning(); f.receiver.onOfferDiscovered(offer); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertIs<ReceiverState.Failed>(f.receiver.state.value)
        assertEquals(1, f.platform.stops)
    }

    @Test fun realCancelResetAndNextUserSessionStartExactlyOnceEach() = runTest {
        val f = Fixture(backgroundScope); f.grab(); runCurrent()
        f.sender.cancel(); runCurrent(); f.sender.reset(); runCurrent()
        assertEquals(SenderState.Idle, f.sender.state.value)
        assertEquals(1, f.platform.stops)
        f.grab(); runCurrent()
        assertEquals(2, f.platform.starts)
        f.sender.cancel(); runCurrent()
        assertEquals(2, f.platform.stops)
    }

    @Test fun realTransferCompletionStopsServiceAfterBothRolesFinish() = runTest {
        val f = Fixture(backgroundScope); f.link()
        val (a, b) = LoopbackConnection.createPair()
        f.sender.attachConnection(a); f.receiver.attachConnection(b); runCurrent()
        assertEquals(SenderState.Done, f.sender.state.value)
        assertEquals(ReceiverState.Done, f.receiver.state.value)
        assertContentEquals(byteArrayOf(1, 2, 3), f.sink.toByteArray())
        assertEquals(1, f.platform.stops)
    }

    @Test fun realChecksumFailureStopsService() = runTest {
        val f = Fixture(backgroundScope, verify = false); f.link()
        val (a, b) = LoopbackConnection.createPair()
        f.sender.attachConnection(a); f.receiver.attachConnection(b); runCurrent()
        assertFalse(assertIs<SenderState.Failed>(f.sender.state.value).canResume)
        assertFalse(assertIs<ReceiverState.Failed>(f.receiver.state.value).canResume)
        assertEquals(1, f.platform.stops)
    }

    @Test fun realResumableLossKeepsServiceUntilReconnectBudgetExpires() = runTest {
        val f = Fixture(backgroundScope); f.link()
        val (a, _) = LoopbackConnection.createPair()
        f.sender.attachConnection(a); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertTrue(assertIs<SenderState.Failed>(f.sender.state.value).canResume)
        assertEquals(0, f.platform.stops)
        advanceTimeBy(200); runCurrent()
        assertFalse(assertIs<SenderState.Failed>(f.sender.state.value).canResume)
        assertEquals(1, f.platform.stops)
    }

    @Test fun cancellingUiObserverDoesNotCancelSessionOwner() = runTest {
        val f = Fixture(backgroundScope); f.grab(); runCurrent()
        val ui = launch { f.sender.state.collect {} }
        runCurrent(); ui.cancelAndJoin()
        assertIs<SenderState.Grabbed>(f.sender.state.value)
        assertEquals(0, f.platform.stops)
        advanceTimeBy(100); runCurrent()
        assertEquals(1, f.platform.stops)
    }
}
