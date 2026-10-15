package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.LoopbackConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class T2AuthVerificationTest {
    private fun exercise(mode: String) = runTest {
        val sender = SenderStateMachine(backgroundScope)
        val receiver = ReceiverStateMachine(backgroundScope)
        val meta = FileMeta(
            "file", "file.txt", 1, "text/plain", ByteArray(32)
        )
        sender.onGrabbed(listOf(meta), emptyMap())
        val offer = (sender.state.value as SenderState.Advertising).offer

        receiver.startScanning()
        receiver.onOfferDiscovered(offer)
        val claim = receiver.claimOffer()
        sender.onClaimReceived(claim)
        receiver.state.first { it is ReceiverState.AcceptPrompt }
        val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234)
        sender.onDecisionReceived(receiver.acceptTransfer(link), link)

        val (senderWire, receiverWire) = LoopbackConnection.createPair()
        val tampered = object : Connection by senderWire {
            override suspend fun writeFrame(frame: RawFrame) {
                if (frame.type != FrameType.AUTH) {
                    senderWire.writeFrame(frame)
                    return
                }
                val auth = ProtocolCodec.decodePayload<AuthMessage>(frame)
                val changed = when (mode) {
                    "session" -> auth.copy(
                        sessionId = auth.sessionId.copyOf().also {
                            it[0] = (it[0].toInt() xor 1).toByte()
                        }
                    )
                    "tag" -> auth.copy(
                        authTag = auth.authTag.copyOf().also {
                            it[0] = (it[0].toInt() xor 1).toByte()
                        }
                    )
                    else -> auth.copy(
                        authTag = CryptoEngine.computeHmacSha256(
                            ByteArray(32),
                            "authreceiver".encodeToByteArray() + offer.sessionId
                        )
                    )
                }
                senderWire.writeFrame(
                    ProtocolCodec.encodeToFrame(FrameType.AUTH, changed)
                )
            }
        }

        sender.attachConnection(tampered)
        receiver.attachConnection(receiverWire)
        runCurrent()
        assertTrue(receiver.state.value is ReceiverState.Failed)
    }

    @Test fun testWrongSessionIdEndsInFailed() = exercise("session")
    @Test fun testWrongAuthTagEndsInFailed() = exercise("tag")
    @Test fun testWrongRoleLabelEndsInFailed() = exercise("role")
}