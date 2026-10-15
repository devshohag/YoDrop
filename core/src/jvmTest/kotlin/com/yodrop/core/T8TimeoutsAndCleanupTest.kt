package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class T8TimeoutsAndCleanupTest {

    private val dummyFile = FileMeta("id", "doc.txt", 100L, "text/plain", ByteArray(32))

    @After
    fun tearDown() {
        SessionTimeouts.resetToDefaults()
    }

    @Test
    fun testOfferTimeoutTransitionsToFailedAndCleansUp() {
        runBlocking(Dispatchers.IO) {
            SessionTimeouts.OFFER_TIMEOUT_MS = 60L
            val cleanedUp = AtomicBoolean(false)
            val sender = SenderStateMachine(this, onCleanup = { cleanedUp.set(true) })

            sender.onGrabbed(listOf(dummyFile), emptyMap())
            assertTrue(sender.state.value is SenderState.Advertising)

            delay(150L)

            assertTrue(sender.state.value is SenderState.Failed, "State must be Failed after offer timeout")
            assertTrue(cleanedUp.get(), "Cleanup hook must be invoked on terminal timeout")
        }
    }

    @Test
    fun testClaimTimeoutTransitionsToFailedAndCleansUp() {
        runBlocking(Dispatchers.IO) {
            SessionTimeouts.CLAIM_TIMEOUT_MS = 60L
            val cleanedUp = AtomicBoolean(false)
            val sender = SenderStateMachine(this, onCleanup = { cleanedUp.set(true) })

            sender.onGrabbed(listOf(dummyFile), emptyMap())
            val rxKey = CryptoEngine.generateX25519KeyPair()
            sender.onClaimReceived(com.yodrop.core.protocol.ClaimMessage(sender.sessionId!!, rxKey.publicKey))

            assertTrue(sender.state.value is SenderState.AwaitAccept)

            delay(150L)

            assertTrue(sender.state.value is SenderState.Failed, "State must be Failed after claim timeout")
            assertTrue(cleanedUp.get(), "Cleanup hook must be invoked")
        }
    }

    @Test
    fun testLinkingTimeoutTransitionsToFailedAndCleansUp() {
        runBlocking(Dispatchers.IO) {
            SessionTimeouts.LINKING_TIMEOUT_MS = 60L
            val cleanedUp = AtomicBoolean(false)
            val sender = SenderStateMachine(this, onCleanup = { cleanedUp.set(true) })

            sender.onGrabbed(listOf(dummyFile), emptyMap())
            val rxKey = CryptoEngine.generateX25519KeyPair()
            sender.onClaimReceived(com.yodrop.core.protocol.ClaimMessage(sender.sessionId!!, rxKey.publicKey))
            sender.onDecisionReceived(
                com.yodrop.core.protocol.DecisionMessage(sender.sessionId!!, true),
                LinkInfoMessage(LinkType.LAN, "127.0.0.1", 9000)
            )

            assertTrue(sender.state.value is SenderState.Linking)

            delay(150L)

            assertTrue(sender.state.value is SenderState.Failed, "State must be Failed after linking timeout")
            assertTrue(cleanedUp.get(), "Cleanup hook must be invoked")
        }
    }

    @Test
    fun testCancelInEachStateInvokesCleanup() {
        runBlocking(Dispatchers.IO) {
            val senderCleaned = AtomicBoolean(false)
            val sender = SenderStateMachine(this, onCleanup = { senderCleaned.set(true) })
            sender.onGrabbed(listOf(dummyFile), emptyMap())
            sender.cancel("User cancelled")
            assertTrue(sender.state.value is SenderState.Cancelled)
            assertTrue(senderCleaned.get())

            val receiverCleaned = AtomicBoolean(false)
            val receiver = ReceiverStateMachine(this, onCleanup = { receiverCleaned.set(true) })
            receiver.startScanning()
            receiver.cancel("Scanning cancelled")
            assertTrue(receiver.state.value is ReceiverState.Cancelled)
            assertTrue(receiverCleaned.get())
        }
    }
}
