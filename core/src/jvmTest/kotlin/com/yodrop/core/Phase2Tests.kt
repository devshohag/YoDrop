package com.yodrop.core

import kotlinx.coroutines.flow.first

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.DiskFileSink
import com.yodrop.core.platform.DiskFileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.ReceiverStateMachine
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase2Tests {

    private class PipeConn(
        private val inCh: Channel<RawFrame>,
        private val outCh: Channel<RawFrame>
    ) : Connection {
        private var closed = false
        override val isClosed: Boolean get() = closed
        override suspend fun readFrame(): RawFrame = inCh.receive()
        override suspend fun writeFrame(frame: RawFrame) = outCh.send(frame)
        override suspend fun close() {
            closed = true
            inCh.close()
            outCh.close()
        }
    }

    @Test
    fun testCompleteHandshakeAndTransferViaLoopback() {
        runBlocking {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "yodrop-test-p2-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            val srcFile = File(tempDir, "sample.txt")
            srcFile.writeBytes("YoDrop Streaming Loopback Transfer Verification Content".toByteArray())

            val dstFile = File(tempDir, "received.txt")

            val sha = CryptoEngine.sha256(srcFile.readBytes())
            val meta = FileMeta("file-1", "sample.txt", srcFile.length(), "text/plain", sha)
            val source = DiskFileSource(meta.id, srcFile)

            val sender = SenderStateMachine(this)
            val receiver = ReceiverStateMachine(this, sinkFactory = { DiskFileSink(it.id, dstFile) })

            sender.onGrabbed(listOf(meta), mapOf(meta.id to source))
            val adv = sender.state.value as SenderState.Grabbed

            receiver.startScanning()
            receiver.onOfferDiscovered(adv.offer)
            val claim = receiver.claimOffer()
            sender.onClaimReceived(claim)
            receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))

            val linkInfo = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 9999)
            receiver.state.first { it is ReceiverState.AcceptPrompt }
            val decision = receiver.acceptTransfer(linkInfo)
            sender.onDecisionReceived(decision, linkInfo)

            val s2r = Channel<RawFrame>(Channel.UNLIMITED)
            val r2s = Channel<RawFrame>(Channel.UNLIMITED)

            val senderConn = PipeConn(r2s, s2r)
            val receiverConn = PipeConn(s2r, r2s)

            sender.attachConnection(senderConn)
            receiver.attachConnection(receiverConn)

            withTimeout(5000) {
                while (sender.state.value !is SenderState.Done || receiver.state.value !is ReceiverState.Done) {
                    delay(10)
                }
            }

            assertEquals(SenderState.Done, sender.state.value)
            assertEquals(ReceiverState.Done, receiver.state.value)
            assertTrue(dstFile.exists())
            assertEquals(srcFile.readText(), dstFile.readText())

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testUserDenyFlow() {
        runBlocking {
            val dummyFile = FileMeta("id", "test.txt", 10L, "text/plain", ByteArray(32))
            val sender = SenderStateMachine(this)
            val receiver = ReceiverStateMachine(this)

            sender.onGrabbed(listOf(dummyFile), emptyMap())
            val adv = sender.state.value as SenderState.Grabbed

            receiver.startScanning()
            receiver.onOfferDiscovered(adv.offer)
            val claim = receiver.claimOffer()
            sender.onClaimReceived(claim)
            receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))

            receiver.state.first { it is ReceiverState.AcceptPrompt }
            val deny = receiver.denyTransfer("Not trusted")
            sender.onDecisionReceived(deny, LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234))

            assertTrue(sender.state.value is SenderState.Failed)
            assertTrue(receiver.state.value is ReceiverState.Cancelled)
        }
    }
}
