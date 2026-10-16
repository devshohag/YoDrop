package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.LoopbackConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step5StateGuardTest {
    private val offer = OfferMessage(
        ByteArray(16), ByteArray(32), 1, 1, "file.txt"
    )
    private val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234)
    private val file = FileMeta(
        "file", "file.txt", 1, "text/plain", ByteArray(32)
    )

    @Test
    fun fullSenderStateEventMatrix() {
        val states = listOf(
            SenderState.Idle,
            SenderState.Grabbed(offer), SenderState.Claimed,
            SenderState.AwaitAccept("1234"), SenderState.Linking(link),
            SenderState.Auth, SenderState.Transferring(0f, "file.txt"),
            SenderState.Done, SenderState.Cancelled("x"), SenderState.Failed("x")
        )
        val expected = listOf(
            setOf(SessionEvent.GRAB),
            setOf(SessionEvent.CLAIM, SessionEvent.CANCEL),
            setOf(SessionEvent.CANCEL),
            setOf(SessionEvent.DECISION, SessionEvent.CANCEL),
            setOf(SessionEvent.ATTACH, SessionEvent.CANCEL),
            setOf(SessionEvent.ATTACH, SessionEvent.CANCEL),
            setOf(SessionEvent.ATTACH, SessionEvent.CANCEL),
            setOf(SessionEvent.RESET),
            setOf(SessionEvent.RESET),
            setOf(SessionEvent.RESET)
        )
        states.forEachIndexed { index, state ->
            SessionEvent.entries.forEach { event ->
                assertEquals(
                    event in expected[index],
                    SessionPolicy.sender(state, event),
                    "$state / $event"
                )
            }
        }
    }

    @Test
    fun fullReceiverStateEventMatrix() {
        val states = listOf(
            ReceiverState.Idle, ReceiverState.Scanning,
            ReceiverState.OfferSeen(offer), ReceiverState.Claimed,
            ReceiverState.AcceptPrompt(offer, "1234", "file.txt"),
            ReceiverState.Linking(link), ReceiverState.Auth,
            ReceiverState.Receiving(0f, "file.txt"),
            ReceiverState.Verifying("file"),
            ReceiverState.Done, ReceiverState.Cancelled("x"),
            ReceiverState.Failed("x")
        )
        val expected = listOf(
            setOf(SessionEvent.SCAN),
            setOf(SessionEvent.OFFER, SessionEvent.CANCEL),
            setOf(SessionEvent.CLAIM, SessionEvent.CANCEL),
            setOf(SessionEvent.CANCEL),
            setOf(SessionEvent.ACCEPT, SessionEvent.DENY, SessionEvent.CANCEL),
            setOf(SessionEvent.ATTACH, SessionEvent.CANCEL),
            setOf(SessionEvent.ATTACH, SessionEvent.CANCEL),
            setOf(SessionEvent.ATTACH, SessionEvent.CANCEL),
            setOf(SessionEvent.ATTACH, SessionEvent.CANCEL),
            setOf(SessionEvent.RESET),
            setOf(SessionEvent.RESET),
            setOf(SessionEvent.RESET)
        )
        states.forEachIndexed { index, state ->
            SessionEvent.entries.forEach { event ->
                assertEquals(
                    event in expected[index],
                    SessionPolicy.receiver(state, event),
                    "$state / $event"
                )
            }
        }
    }

    @Test
    fun firstClaimWinsAndForeignMessagesAreIgnored() = runTest {
        val sender = SenderStateMachine(backgroundScope)
        sender.onGrabbed(listOf(file), emptyMap())
        val advertised = (sender.state.value as SenderState.Grabbed).offer
        val firstKey = CryptoEngine.generateX25519KeyPair()
        val secondKey = CryptoEngine.generateX25519KeyPair()

        val foreignSid = advertised.sessionId.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        assertFalse(sender.onClaimReceived(
            ClaimMessage(foreignSid, firstKey.publicKey)
        ))
        assertTrue(sender.state.value is SenderState.Grabbed)

        val first = async {
            sender.onClaimReceived(
                ClaimMessage(advertised.sessionId, firstKey.publicKey)
            )
        }
        val second = async {
            sender.onClaimReceived(
                ClaimMessage(advertised.sessionId, secondKey.publicKey)
            )
        }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it })
        val awaiting = sender.state.value
        assertTrue(awaiting is SenderState.AwaitAccept)

        assertFalse(sender.onDecisionReceived(
            DecisionMessage(foreignSid, true), link
        ))
        assertEquals(awaiting, sender.state.value)

        val history = sender.transitions.replayCache
        assertEquals(SenderState.Idle, history[0])
        assertTrue(history[1] is SenderState.Grabbed)
        assertEquals(SenderState.Claimed, history[2])
        assertTrue(history[3] is SenderState.AwaitAccept)
    }

    @Test
    fun claimedIsObservableAndIllegalPromptActionsAreRejected() = runTest {
        val sender = SenderStateMachine(backgroundScope)
        val receiver = ReceiverStateMachine(backgroundScope)

        assertFailsWith<IllegalStateException> { receiver.claimOffer() }
        assertFailsWith<IllegalStateException> { receiver.acceptTransfer(link) }

        sender.onGrabbed(listOf(file), emptyMap())
        val advertised = (sender.state.value as SenderState.Grabbed).offer
        receiver.startScanning()
        receiver.onOfferDiscovered(advertised)

        val claim = receiver.claimOffer()
        assertEquals(ReceiverState.Claimed, receiver.state.value)
        assertFailsWith<IllegalStateException> { receiver.claimOffer() }
        assertFailsWith<IllegalStateException> { receiver.acceptTransfer(link) }

        sender.onClaimReceived(claim)
        receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))
        runCurrent()
        assertTrue(receiver.state.value is ReceiverState.AcceptPrompt)
        assertTrue(receiver.transitions.replayCache.contains(ReceiverState.Claimed))

        receiver.acceptTransfer(link)
        assertFailsWith<IllegalStateException> { receiver.acceptTransfer(link) }
        assertFailsWith<IllegalStateException> { receiver.denyTransfer() }
    }

    @Test
    fun terminalStatesRequireExplicitReset() = runTest {
        val sender = SenderStateMachine(backgroundScope)
        assertFalse(sender.cancel())
        sender.onGrabbed(listOf(file), emptyMap())
        assertTrue(sender.cancel("stop"))
        val terminal = sender.state.value
        assertFalse(sender.cancel("again"))
        assertFalse(sender.onGrabbed(listOf(file), emptyMap()))
        assertEquals(terminal, sender.state.value)
        assertTrue(sender.reset())
        assertEquals(SenderState.Idle, sender.state.value)
        assertTrue(sender.onGrabbed(listOf(file), emptyMap()))

        val receiver = ReceiverStateMachine(backgroundScope)
        receiver.startScanning()
        receiver.cancel()
        assertFalse(receiver.startScanning())
        assertTrue(receiver.reset())
        assertTrue(receiver.startScanning())
    }

    @Test
    fun prematureConnectionsAreClosedWithoutStartingAuth() = runTest {
        val sender = SenderStateMachine(backgroundScope)
        val receiver = ReceiverStateMachine(backgroundScope)
        val (a, b) = LoopbackConnection.createPair()

        assertFalse(sender.attachConnection(a))
        assertTrue(a.isClosed)
        assertEquals(SenderState.Idle, sender.state.value)

        assertFalse(receiver.attachConnection(b))
        assertTrue(b.isClosed)
        assertEquals(ReceiverState.Idle, receiver.state.value)
    }
}
