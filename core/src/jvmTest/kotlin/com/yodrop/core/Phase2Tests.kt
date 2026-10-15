package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.LinkType
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.LoopbackConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class Phase2Tests {

    @Test
    fun testCompleteHandshakeAndTransferViaLoopback() = runTest {
        val sender = SenderStateMachine(this)
        val receiver = ReceiverStateMachine(this)

        // 1. Prepare sample 8 MiB test file payload
        val testFileContent = ByteArray(8 * 1024 * 1024) { (it % 127).toByte() }
        val testSha256 = CryptoEngine.sha256(testFileContent)
        val meta = FileMeta(
            id = "test-doc-1",
            name = "report.pdf",
            size = testFileContent.size.toLong(),
            mime = "application/pdf",
            sha256 = testSha256
        )

        // 2. Sender grab gesture
        sender.onGrabbed(listOf(meta), mapOf(meta.id to testFileContent))
        val advState = sender.state.value as SenderState.Advertising

        // 3. Receiver scans & discovers offer
        receiver.startScanning()
        receiver.onOfferDiscovered(advState.offer)

        // 4. Receiver claims offer (Fist -> Palm gesture simulation)
        val claimMsg = receiver.claimOffer()
        sender.onClaimReceived(claimMsg)

        // Verify that 4-digit code matches on both sender and receiver screens
        val senderAwait = sender.state.value as SenderState.AwaitAccept
        val receiverPrompt = receiver.state.value as ReceiverState.AcceptPrompt
        assertEquals(senderAwait.verifyCode, receiverPrompt.verifyCode, "4-digit verify code must match!")

        // 5. Receiver accepts and creates LinkInfo
        val linkInfo = LinkInfoMessage(LinkType.LAN, host = "127.0.0.1", port = 9001)
        val decision = receiver.acceptTransfer(linkInfo)
        sender.onDecisionReceived(decision, linkInfo)

        // 6. Connect via bidirectional in-memory loopback transport
        val (senderConn, receiverConn) = LoopbackConnection.createPair()
        sender.attachConnection(senderConn)
        receiver.attachConnection(receiverConn)

        // 7. Await transfer completion
        while (sender.state.value !is SenderState.Done || receiver.state.value !is ReceiverState.Done) {
            kotlinx.coroutines.delay(10)
        }

        assertEquals(SenderState.Done, sender.state.value)
        assertEquals(ReceiverState.Done, receiver.state.value)

        // Verify bit-exact integrity
        val transferredBytes = receiver.completedFilesData[meta.id]
        assertTrue(transferredBytes != null && testFileContent.contentEquals(transferredBytes))
    }

    @Test
    fun testUserDenyFlow() = runTest {
        val sender = SenderStateMachine(this)
        val receiver = ReceiverStateMachine(this)

        val meta = FileMeta("f-1", "doc.txt", 100, "text/plain", ByteArray(32))
        sender.onGrabbed(listOf(meta), mapOf("f-1" to ByteArray(100)))
        val offer = (sender.state.value as SenderState.Advertising).offer

        receiver.onOfferDiscovered(offer)
        val claim = receiver.claimOffer()
        sender.onClaimReceived(claim)

        val denyDecision = receiver.denyTransfer("Not trusted")
        val linkInfo = LinkInfoMessage(LinkType.LAN, host = "127.0.0.1", port = 9001)
        sender.onDecisionReceived(denyDecision, linkInfo)

        assertTrue(sender.state.value is SenderState.Failed)
        assertTrue(receiver.state.value is ReceiverState.Cancelled)
    }
}
