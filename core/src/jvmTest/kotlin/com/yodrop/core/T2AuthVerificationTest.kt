package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.protocol.*
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class T2AuthVerificationTest {

    private class MemoryPipeConnection(
        private val incoming: Channel<RawFrame>,
        private val outgoing: Channel<RawFrame>
    ) : Connection {
        private var closed = false
        override val isClosed: Boolean get() = closed

        override suspend fun readFrame(): RawFrame {
            if (closed) error("Connection closed")
            return incoming.receive()
        }

        override suspend fun writeFrame(frame: RawFrame) {
            if (closed) error("Connection closed")
            outgoing.send(frame)
        }

        override suspend fun close() {
            closed = true
            incoming.close()
            outgoing.close()
        }
    }

    private fun createPipes(): Pair<Connection, Connection> {
        val aToB = Channel<RawFrame>(Channel.UNLIMITED)
        val bToA = Channel<RawFrame>(Channel.UNLIMITED)
        return Pair(MemoryPipeConnection(bToA, aToB), MemoryPipeConnection(aToB, bToA))
    }

    @Test
    fun testWrongSessionIdEndsInFailed() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val receiver = ReceiverStateMachine(scope)
        val dummyKeys = SessionKeys(ByteArray(32), ByteArray(32), ByteArray(12), "1234")
        val correctSid = ByteArray(16) { 1 }
        val dummyOffer = OfferMessage(correctSid, ByteArray(32), 1, 100L, "test")

        receiver.sessionKeys = dummyKeys
        receiver.activeOffer = dummyOffer

        val (connRx, connAttacker) = createPipes()
        receiver.attachConnection(connRx)

        // Attacker sends AUTH with WRONG sessionId
        val wrongSid = ByteArray(16) { 9 }
        val authMsg = AuthMessage(sessionId = wrongSid, authTag = ByteArray(32))
        connAttacker.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, authMsg))

        withTimeout(3000) {
            while (receiver.state.value !is ReceiverState.Failed) {
                delay(10)
            }
        }
        assertTrue(receiver.state.value is ReceiverState.Failed, "Wrong sessionId must lead to Failed state")
    }

    @Test
    fun testWrongAuthTagEndsInFailed() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val receiver = ReceiverStateMachine(scope)
        val dummyKeys = SessionKeys(ByteArray(32), ByteArray(32), ByteArray(12), "1234")
        val correctSid = ByteArray(16) { 1 }
        val dummyOffer = OfferMessage(correctSid, ByteArray(32), 1, 100L, "test")

        receiver.sessionKeys = dummyKeys
        receiver.activeOffer = dummyOffer

        val (connRx, connAttacker) = createPipes()
        receiver.attachConnection(connRx)

        // Attacker sends correct sid but wrong authTag
        val wrongTag = ByteArray(32) { 0xFF.toByte() }
        val authMsg = AuthMessage(sessionId = correctSid, authTag = wrongTag)
        connAttacker.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, authMsg))

        withTimeout(3000) {
            while (receiver.state.value !is ReceiverState.Failed) {
                delay(10)
            }
        }
        assertTrue(receiver.state.value is ReceiverState.Failed, "Wrong auth tag must lead to Failed state")
    }

    @Test
    fun testWrongRoleLabelEndsInFailed() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val receiver = ReceiverStateMachine(scope)
        val s2r = ByteArray(32) { 2 }
        val r2s = ByteArray(32) { 3 }
        val dummyKeys = SessionKeys(s2r, r2s, ByteArray(12), "1234")
        val sid = ByteArray(16) { 1 }
        val dummyOffer = OfferMessage(sid, ByteArray(32), 1, 100L, "test")

        receiver.sessionKeys = dummyKeys
        receiver.activeOffer = dummyOffer

        val (connRx, connAttacker) = createPipes()
        receiver.attachConnection(connRx)

        // Attacker computes tag using WRONG role label ("authreceiver" instead of "authsender")
        val wrongRolePayload = "authreceiver".encodeToByteArray() + sid
        val tagWithWrongRole = CryptoEngine.computeHmacSha256(s2r, wrongRolePayload)
        val authMsg = AuthMessage(sessionId = sid, authTag = tagWithWrongRole)
        connAttacker.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, authMsg))

        withTimeout(3000) {
            while (receiver.state.value !is ReceiverState.Failed) {
                delay(10)
            }
        }
        assertTrue(receiver.state.value is ReceiverState.Failed, "Wrong role label must lead to Failed state")
    }
}
