package com.yodrop.core

import com.yodrop.core.crypto.*
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step17HotspotTest {
    private val config = HotspotConfiguration("private-LOHS-ssid", "private-passphrase-62c9")
    private val upstream = HotspotInterfaceAddress("wlan0", "10.4.0.7", 24, eligible = false)
    private val downstream = HotspotInterfaceAddress("ap_vendor_name", "172.21.8.1", 24)
    private class Lease(override val configuration: HotspotConfiguration?) : HotspotLease {
        private val closed = MutableStateFlow(false)
        var closes = 0
        override fun close() { if (closed.compareAndSet(false, true)) closes++ }
    }
    private class Port(val before: List<HotspotInterfaceAddress>, var after: List<HotspotInterfaceAddress>, val lease: Lease?) : HotspotPlatform {
        var reads = 0; var starts = 0
        lateinit var callback: HotspotCallbacks
        override suspend fun interfaces(): List<HotspotInterfaceAddress> = if (reads++ == 0) before else after
        override fun start(callbacks: HotspotCallbacks) { starts++; callback = callbacks; lease?.let { callbacks.started(it) } }
    }
    private fun port(lease: Lease? = Lease(config)) = Port(listOf(upstream), listOf(upstream, downstream), lease)

    @Test fun usesNewActualInterfaceAndListenerPortWithoutAssumingItsNameOrSubnet() = runTest {
        val p = port(); val session = HotspotSession(p)
        val actual = session.start(45678)
        assertEquals(downstream.ipv4, actual.gatewayIp); assertEquals(45678, actual.port)
        assertEquals(config.ssid, actual.ssid); assertEquals(config.passphrase, actual.passphrase)
        assertEquals(1, p.starts); session.close(); assertEquals(1, p.lease!!.closes)
    }

    @Test fun existingUpstreamAddressCannotBeMistakenForHotspotGateway() {
        assertNull(HotspotGatewaySelector.select(listOf(upstream), listOf(upstream)))
        assertNull(HotspotGatewaySelector.select(emptyList(), listOf(upstream)))
        assertEquals(downstream, HotspotGatewaySelector.select(listOf(upstream), listOf(upstream, downstream)))
        val old = downstream.copy(ipv4 = "10.4.0.7")
        assertEquals(downstream, HotspotGatewaySelector.select(listOf(old), listOf(downstream)))
    }

    @Test fun ambiguousInterfacesFailInsteadOfChoosingTheFirstAddress() = runTest {
        val p = port().apply { after = after + downstream.copy(interfaceName = "vpn-new", ipv4 = "10.21.3.1") }
        val session = HotspotSession(p)
        assertFails { session.start(1234) }; assertEquals(1, p.lease!!.closes)
    }

    @Test fun loopbackLinkLocalMulticastNetworkBroadcastAndPointToPointAreRejected() {
        for (ip in listOf("127.0.0.1", "0.0.0.0", "169.254.3.1", "224.1.2.1", "255.255.255.255", "172.21.8.0", "172.21.8.255", "bogus"))
            assertNull(HotspotGatewaySelector.select(emptyList(), listOf(downstream.copy(ipv4 = ip))), ip)
        assertNull(HotspotGatewaySelector.select(emptyList(), listOf(downstream.copy(prefixLength = 32))))
        assertNull(HotspotGatewaySelector.select(emptyList(), listOf(downstream.copy(eligible = false))))
        assertNull(HotspotGatewaySelector.select(emptyList(), listOf(downstream.copy(interfaceName = ""))))
    }

    @Test fun nullLegacyOrUnsupportedModernConfigurationReleasesReservation() = runTest {
        val lease = Lease(null); val session = HotspotSession(port(lease))
        assertFails { session.start(1234) }; assertEquals(1, lease.closes)
        session.close(); assertEquals(1, lease.closes)
    }

    @Test fun invalidOrMissingSsidOrPassphraseNeverProducesFabricatedCredentials() = runTest {
        for (bad in listOf(config.copy(ssid = ""), config.copy(passphrase = ""), config.copy(ssid = "x".repeat(33)), config.copy(passphrase = "short"))) {
            val lease = Lease(bad); val session = HotspotSession(port(lease))
            assertFails { session.start(1234) }; assertEquals(1, lease.closes)
        }
    }

    @Test fun cancelBeforeOnStartedClosesLateReservationImmediately() = runTest {
        val p = port(null); val session = HotspotSession(p)
        val startup = async { runCatching { session.start(1234) } }
        runCurrent(); assertEquals(1, p.starts)
        startup.cancelAndJoin()
        val late = Lease(config); p.callback.started(late)
        assertEquals(1, late.closes); session.close(); assertEquals(1, late.closes)
    }

    @Test fun closeBeforeOnStartedUnblocksCallerAndRejectsEveryLaterLease() = runTest {
        val p = port(null); val session = HotspotSession(p)
        val startup = async { runCatching { session.start(1234) } }
        runCurrent(); session.close(); assertTrue(startup.await().isFailure)
        repeat(2) { val late = Lease(config); p.callback.started(late); assertEquals(1, late.closes) }
    }

    @Test fun timeoutBeforeCallbackStillDisposesLateReservation() = runTest {
        val p = port(null); val session = HotspotSession(p, timeoutMs = 100)
        val startup = async { runCatching { session.start(1234) } }
        runCurrent(); advanceTimeBy(99); runCurrent(); assertFalse(startup.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue(startup.await().isFailure)
        val late = Lease(config); p.callback.started(late); assertEquals(1, late.closes)
    }

    @Test fun noNewInterfaceTimesOutAndReleasesAnAlreadyStartedReservation() = runTest {
        val p = port().apply { after = before }; val session = HotspotSession(p, timeoutMs = 200)
        val startup = async { runCatching { session.start(1234) } }
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertTrue(startup.await().isFailure); assertEquals(1, p.lease!!.closes)
    }

    @Test fun waitsForAddressAssignmentAfterHotspotCallback() = runTest {
        val p = port().apply { after = before }; val session = HotspotSession(p)
        val startup = async { session.start(1234) }
        runCurrent(); assertFalse(startup.isCompleted)
        p.after = listOf(upstream, downstream); advanceTimeBy(100); runCurrent()
        assertEquals(downstream.ipv4, startup.await().gatewayIp); session.close()
    }

    @Test fun stoppedAndFailedCallbacksReleaseLeaseAndNotifyWatcher() = runTest {
        val p = port(); val session = HotspotSession(p); session.start(1234)
        val ended = async { session.awaitStopped() }; runCurrent(); assertFalse(ended.isCompleted)
        p.callback.stopped(); ended.await(); assertEquals(1, p.lease!!.closes)
        p.callback.failed(2); session.close(); assertEquals(1, p.lease.closes)
    }

    @Test fun platformFailureBeforeOnStartedDoesNotWaitForTheTimeout() = runTest {
        val p = port(null); val session = HotspotSession(p)
        val startup = async { runCatching { session.start(1234) } }
        runCurrent(); p.callback.failed(4); runCurrent()
        assertTrue(startup.isCompleted); assertTrue(startup.await().isFailure)
    }

    @Test fun duplicateCallbacksAndConcurrentStartsDoNotLeakOrReplaceActiveLease() = runTest {
        val p = port(); val session = HotspotSession(p); session.start(1234)
        p.callback.started(p.lease!!); assertEquals(0, p.lease.closes)
        val duplicate = Lease(config); p.callback.started(duplicate); assertEquals(1, duplicate.closes)
        assertFails { session.start(5678) }; assertEquals(1, p.starts)
        session.close(); session.close(); assertEquals(1, p.lease.closes)
    }

    @Test fun invalidPortNeverRequestsAHotspot() = runTest {
        for (number in listOf(0, -1, 65536)) {
            val p = port(); assertFails { HotspotSession(p).start(number) }; assertEquals(0, p.starts)
        }
    }

    @Test fun encryptedEndpointAndDebugStringsDoNotExposeCredentials() = runTest {
        val p = port(); val session = HotspotSession(p); val credentials = session.start(40000)
        val link = SenderLinkInfoFactory.hotspot(credentials)
        val sid = ByteArray(16) { 7 }; val keys = CryptoEngine.deriveSessionKeys(ByteArray(32) { 8 }, sid)
        val wire = BleMessageCodec.encode(LinkInfoCipher.seal(keys, sid, 0, link))
        for (secret in listOf(config.ssid, config.passphrase)) {
            val bytes = secret.encodeToByteArray()
            assertFalse((0..wire.size - bytes.size).any { i -> bytes.indices.all { wire[i + it] == bytes[it] } })
            assertFalse(config.toString().contains(secret)); assertFalse(credentials.toString().contains(secret)); assertFalse(link.toString().contains(secret))
        }
        assertEquals(link, LinkInfoCipher.open(keys, sid, BleMessageCodec.decode(wire) as EncryptedLinkInfoMessage))
        session.close()
    }

    private suspend fun link(sender: SenderStateMachine, receiver: ReceiverStateMachine) {
        val data = ByteArray(128 * 1024)
        val file = FileMeta("f", "f.bin", data.size.toLong(), "application/octet-stream", CryptoEngine.sha256(data))
        sender.onGrabbed(listOf(file), mapOf("f" to MemoryFileSource("f", data)))
        receiver.startScanning(); receiver.onOfferDiscovered(assertIs<SenderState.Grabbed>(sender.state.value).offer)
        sender.onClaimReceived(receiver.claimOffer())
        receiver.onEncryptedOfferDetailsReceived(assertNotNull(sender.createEncryptedOfferDetails()))
        sender.onDecisionReceived(receiver.acceptTransfer())
    }

    @Test fun realSenderCleanupStopsHotspotOnCancelAndWaitsBeforeReset() = runTest {
        val p = port(); val session = HotspotSession(p)
        val sender = SenderStateMachine(backgroundScope, onCleanup = { session.close() })
        link(sender, ReceiverStateMachine(backgroundScope)); session.start(1234)
        sender.cancel(); sender.reset(); assertEquals(1, p.lease!!.closes)
        session.close(); assertEquals(1, p.lease.closes)
    }

    @Test fun realLinkingDeadlineStopsHotspotThroughTheTerminalHook() = runTest {
        val p = port(); val session = HotspotSession(p)
        val sender = SenderStateMachine(backgroundScope, onCleanup = { session.close() })
        link(sender, ReceiverStateMachine(backgroundScope)); session.start(1234)
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertIs<SenderState.Failed>(sender.state.value); assertEquals(1, p.lease!!.closes)
    }
}
