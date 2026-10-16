package com.yodrop.core

import com.yodrop.core.platform.MemoryFileSink
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.LoopbackConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class T8TimeoutsAndCleanupTest {
    private val slow = Timeouts(
        offerMs = 100_000,
        claimWindowMs = 100_000,
        acceptPromptMs = 100_000,
        linkingMs = 100_000,
        socketIdleMs = 100_000,
        pingIntervalMs = 100_000
    )

    private class Counts {
        var sender = 0
        var receiver = 0
    }

    private class Fixture(
        val sender: SenderStateMachine,
        val receiver: ReceiverStateMachine,
        val counts: Counts
    ) {
        val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234)

        suspend fun claim() {
            sender.onClaimReceived(receiver.claimOffer())
            receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))
            receiver.state.first { it is ReceiverState.AcceptPrompt }
        }

        suspend fun link() {
            claim()
            sender.onDecisionReceived(receiver.acceptTransfer(link), link)
        }
    }

    private suspend fun TestScope.fixture(
        senderTimeouts: Timeouts = slow,
        receiverTimeouts: Timeouts = slow
    ): Fixture {
        val counts = Counts()
        val bytes = byteArrayOf(1, 2, 3)
        val file = FileMeta(
            "file", "file.txt", bytes.size.toLong(), "text/plain",
            com.yodrop.core.crypto.CryptoEngine.sha256(bytes)
        )

        val sender = SenderStateMachine(
            backgroundScope,
            onCleanup = { counts.sender++ },
            timeouts = senderTimeouts
        )
        val receiver = ReceiverStateMachine(
            backgroundScope,
            onCleanup = { counts.receiver++ },
            sinkFactory = { MemoryFileSink(it.id) },
            timeouts = receiverTimeouts
        )
        sender.onGrabbed(
            listOf(file), mapOf(file.id to MemoryFileSource(file.id, bytes))
        )
        receiver.startScanning()
        receiver.onOfferDiscovered(
            (sender.state.value as SenderState.Grabbed).offer
        )
        return Fixture(sender, receiver, counts)
    }

    @Test
    fun defaultsAndValidation() {
        val defaults = Timeouts()
        assertEquals(60_000L, defaults.offerMs)
        assertEquals(10_000L, defaults.claimWindowMs)
        assertEquals(60_000L, defaults.acceptPromptMs)
        assertEquals(20_000L, defaults.linkingMs)
        assertEquals(15_000L, defaults.socketIdleMs)
        assertEquals(5_000L, defaults.pingIntervalMs)
        assertFailsWith<IllegalArgumentException> {
            defaults.copy(socketIdleMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            defaults.copy(acceptPromptMs = -1)
        }
    }

    @Test
    fun senderOfferDeadlineAndCleanup() = runTest {
        val f = fixture(senderTimeouts = slow.copy(offerMs = 100))
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Grabbed)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Failed)
        assertEquals(1, f.counts.sender)
    }

    @Test
    fun receiverClaimWindowStartsAtOfferSeen() = runTest {
        val f = fixture(receiverTimeouts = slow.copy(claimWindowMs = 100))
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.OfferSeen)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Failed)
        assertEquals(1, f.counts.receiver)
    }

    @Test
    fun senderAcceptPromptDeadline() = runTest {
        val f = fixture(senderTimeouts = slow.copy(acceptPromptMs = 100))
        f.claim()
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.AwaitAccept)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Failed)
        assertEquals(1, f.counts.sender)
    }

    @Test
    fun receiverAcceptPromptDeadline() = runTest {
        val f = fixture(receiverTimeouts = slow.copy(acceptPromptMs = 100))
        f.claim()
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.AcceptPrompt)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Failed)
        assertEquals(1, f.counts.receiver)
    }

    @Test
    fun senderLinkingDeadline() = runTest {
        val f = fixture(senderTimeouts = slow.copy(linkingMs = 100))
        f.link()
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Linking)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Failed)
        assertEquals(1, f.counts.sender)
    }

    @Test
    fun receiverLinkingDeadline() = runTest {
        val f = fixture(receiverTimeouts = slow.copy(linkingMs = 100))
        f.link()
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Linking)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Failed)
        assertEquals(1, f.counts.receiver)
    }

    @Test
    fun senderIdleDeadlineClosesConnectionDuringAuth() = runTest {
        val f = fixture(senderTimeouts = slow.copy(socketIdleMs = 100))
        f.link()
        val (senderWire, _) = LoopbackConnection.createPair()
        f.sender.attachConnection(senderWire)
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Auth)
        advanceTimeBy(1)
        runCurrent()
        val failed = assertIs<SenderState.Failed>(f.sender.state.value)
        assertTrue(failed.error.contains("Socket idle timeout"))
        assertTrue(senderWire.isClosed)
        assertTrue(failed.canResume)
        assertEquals(0, f.counts.sender)
    }

    @Test
    fun receiverIdleDeadlineClosesConnectionDuringAuth() = runTest {
        val f = fixture(receiverTimeouts = slow.copy(socketIdleMs = 100))
        f.link()
        val (_, receiverWire) = LoopbackConnection.createPair()
        f.receiver.attachConnection(receiverWire)
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Auth)
        advanceTimeBy(1)
        runCurrent()
        val failed = assertIs<ReceiverState.Failed>(f.receiver.state.value)
        assertTrue(failed.error.contains("Socket idle timeout"))
        assertTrue(receiverWire.isClosed)
        assertTrue(failed.canResume)
        assertEquals(0, f.counts.receiver)
    }

    @Test
    fun receiverPingAndPongResetReadIdleDeadline() = runTest {
        val f = fixture(receiverTimeouts = slow.copy(socketIdleMs = 100))
        f.link()
        val (peerWire, receiverWire) = LoopbackConnection.createPair()
        f.receiver.attachConnection(receiverWire)
        runCurrent()

        advanceTimeBy(90)
        peerWire.writeFrame(ProtocolCodec.encodeToFrame(
            FrameType.PING, PingPongMessage(1, false)
        ))
        runCurrent()
        advanceTimeBy(90)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Auth)

        peerWire.writeFrame(ProtocolCodec.encodeToFrame(
            FrameType.PING, PingPongMessage(2, true)
        ))
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Auth)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Failed)
    }

    @Test
    fun senderAckAndPingResetReadIdleDeadline() = runTest {
        val f = fixture(senderTimeouts = slow.copy(socketIdleMs = 100))
        f.link()
        val (senderWire, receiverWire) = LoopbackConnection.createPair()

        // Both peers are real; the transport drops only the chunk request.
        val droppingRequest = object : Connection by receiverWire {
            override suspend fun writeFrame(frame: RawFrame) {
                if (frame.type != FrameType.CHUNK_REQ) {
                    receiverWire.writeFrame(frame)
                }
            }
        }

        f.sender.attachConnection(senderWire)
        f.receiver.attachConnection(droppingRequest)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Transferring)

        advanceTimeBy(90)
        receiverWire.writeFrame(ProtocolCodec.encodeToFrame(
            FrameType.ACK, AckMessage("file", 0)
        ))
        runCurrent()
        advanceTimeBy(90)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Transferring)

        receiverWire.writeFrame(ProtocolCodec.encodeToFrame(
            FrameType.PING, PingPongMessage(3, false)
        ))
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Transferring)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.sender.state.value is SenderState.Failed)
    }

    @Test
    fun claimingCancelsWindowAndStartsFreshPromptDeadline() = runTest {
        val f = fixture(
            receiverTimeouts = slow.copy(
                claimWindowMs = 100,
                acceptPromptMs = 300
            )
        )
        runCurrent()
        advanceTimeBy(99)
        f.claim()
        runCurrent()

        advanceTimeBy(299)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.AcceptPrompt)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.receiver.state.value is ReceiverState.Failed)
    }

    @Test
    fun configurationsAreIndependentBetweenSessions() = runTest {
        val first = fixture(senderTimeouts = slow.copy(offerMs = 100))
        val second = fixture(senderTimeouts = slow.copy(offerMs = 200))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(first.sender.state.value is SenderState.Failed)
        assertTrue(second.sender.state.value is SenderState.Grabbed)

        advanceTimeBy(100)
        runCurrent()
        assertTrue(second.sender.state.value is SenderState.Failed)
    }

    @Test
    fun cancelStopsTimersWithoutRepeatedCleanup() = runTest {
        val tiny = Timeouts(
            offerMs = 100,
            claimWindowMs = 100,
            acceptPromptMs = 100,
            linkingMs = 100,
            socketIdleMs = 100
        )
        val f = fixture(tiny, tiny)
        f.claim()
        f.sender.cancel("stop")
        f.receiver.cancel("stop")
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()

        assertIs<SenderState.Cancelled>(f.sender.state.value)
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
        assertEquals(1, f.counts.sender)
        assertEquals(1, f.counts.receiver)
    }
}
