package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transfer.InMemoryResumeStore
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.LoopbackConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step7LifecycleTest {
    private val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234)

    private class Resources(val bytes: ByteArray, val verifyMs: Long, val matches: Boolean) {
        var sourceCloses = 0
        var sinkCloses = 0
        var discards = 0
        var senderCleanup = 0
        var receiverCleanup = 0
        var commits = 0
        val memory = MemoryFileSink("file")
        val source = object : FileSource by MemoryFileSource("file", bytes) {
            override suspend fun close() { sourceCloses++ }
        }
        val sink = object : FileSink by memory {
            override suspend fun verifySha256(expectedSha256: ByteArray): Boolean {
                delay(verifyMs)
                return matches && memory.verifySha256(expectedSha256)
            }
            override suspend fun commit() { commits++ }
            override suspend fun discard() { discards++; memory.discard() }
            override suspend fun close() { sinkCloses++ }
        }
    }

    private class Fixture(
        val sender: SenderStateMachine,
        val receiver: ReceiverStateMachine,
        val resources: Resources,
        val store: InMemoryResumeStore,
        val sid: ByteArray
    )

    private suspend fun TestScope.fixture(
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        verifyMs: Long = 0,
        matches: Boolean = true,
        timeouts: Timeouts = Timeouts(),
        owner: CoroutineScope = backgroundScope
    ): Fixture {
        val r = Resources(bytes, verifyMs, matches)
        val store = InMemoryResumeStore()
        val sender = SenderStateMachine(owner, { r.senderCleanup++ }, timeouts)
        val receiver = ReceiverStateMachine(owner, store,
            onCleanup = { r.receiverCleanup++ }, sinkFactory = { r.sink }, timeouts = timeouts)
        val file = FileMeta("file", "file.dat", bytes.size.toLong(),
            "application/octet-stream", CryptoEngine.sha256(bytes))
        assertTrue(sender.onGrabbed(listOf(file), mapOf("file" to r.source)))
        val offer = assertIs<SenderState.Advertising>(sender.state.value).offer
        receiver.startScanning()
        receiver.onOfferDiscovered(offer)
        sender.onClaimReceived(receiver.claimOffer())
        receiver.state.first { it is ReceiverState.AcceptPrompt }
        sender.onDecisionReceived(receiver.acceptTransfer(link), link)
        return Fixture(sender, receiver, r, store, offer.sessionId)
    }

    @Test
    fun periodicPingKeepsBothPeersAliveDuringFortySecondVerification() = runTest {
        val f = fixture(verifyMs = 40_000)
        val (a, b) = LoopbackConnection.createPair()
        var senderPings = 0
        var receiverPings = 0
        f.sender.attachConnection(object : Connection by a {
            override suspend fun writeFrame(frame: RawFrame) {
                if (frame.type == FrameType.PING &&
                    !ProtocolCodec.decodePayload<PingPongMessage>(frame).reply) senderPings++
                a.writeFrame(frame)
            }
        })
        f.receiver.attachConnection(object : Connection by b {
            override suspend fun writeFrame(frame: RawFrame) {
                if (frame.type == FrameType.PING &&
                    !ProtocolCodec.decodePayload<PingPongMessage>(frame).reply) receiverPings++
                b.writeFrame(frame)
            }
        })
        runCurrent()
        assertIs<ReceiverState.Verifying>(f.receiver.state.value)
        advanceTimeBy(35_000)
        runCurrent()
        assertIs<SenderState.Transferring>(f.sender.state.value)
        assertIs<ReceiverState.Verifying>(f.receiver.state.value)
        assertTrue(senderPings >= 7)
        assertTrue(receiverPings >= 7)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(SenderState.Done, f.sender.state.value)
        assertEquals(ReceiverState.Done, f.receiver.state.value)
        assertEquals(1, f.resources.senderCleanup)
        assertEquals(1, f.resources.receiverCleanup)
        assertEquals(0, f.resources.discards)
    }

    @Test
    fun droppedConnectionKeepsSourcesAndSinkAndReauthRequestsOnlyMissingChunk() = runTest {
        val bytes = ByteArray(4 * 1024 * 1024 + 3) { (it % 241).toByte() }
        val f = fixture(bytes)
        val (a, b) = LoopbackConnection.createPair()
        var chunks = 0
        f.sender.attachConnection(object : Connection by a {
            override suspend fun writeFrame(frame: RawFrame) {
                if (frame.type == FrameType.CHUNK_DATA && ++chunks == 2) awaitCancellation()
                a.writeFrame(frame)
            }
        })
        f.receiver.attachConnection(b)
        runCurrent()
        assertEquals(listOf(1), f.store.loadBitmap(f.sid, "file", 2).getMissingChunks())
        a.close()
        runCurrent()
        assertTrue(assertIs<SenderState.Failed>(f.sender.state.value).canResume)
        assertTrue(assertIs<ReceiverState.Failed>(f.receiver.state.value).canResume)
        assertEquals(0, f.resources.sourceCloses)
        assertEquals(0, f.resources.discards)
        assertEquals(0, f.resources.senderCleanup)
        assertEquals(0, f.resources.receiverCleanup)

        val (c, d) = LoopbackConnection.createPair()
        val requested = mutableListOf<Int>()
        assertTrue(f.sender.attachConnection(c))
        assertTrue(f.receiver.attachConnection(object : Connection by d {
            override suspend fun writeFrame(frame: RawFrame) {
                if (frame.type == FrameType.CHUNK_REQ) {
                    requested += ProtocolCodec.decodePayload<ChunkReqMessage>(frame).chunkIndices
                }
                d.writeFrame(frame)
            }
        }))
        runCurrent()
        assertEquals(listOf(1), requested)
        assertEquals(SenderState.Done, f.sender.state.value)
        assertEquals(ReceiverState.Done, f.receiver.state.value)
        assertContentEquals(bytes, f.resources.memory.toByteArray())
        assertEquals(1, f.resources.sourceCloses)
        assertEquals(1, f.resources.commits)
    }

    @Test
    fun senderCancelReasonInterruptsReceiverVerification() = runTest {
        val f = fixture(verifyMs = 40_000)
        val (a, b) = LoopbackConnection.createPair()
        f.sender.attachConnection(a)
        f.receiver.attachConnection(b)
        runCurrent()
        assertIs<ReceiverState.Verifying>(f.receiver.state.value)
        f.sender.cancel("Stopped by sender")
        runCurrent()
        assertEquals(ReceiverState.Cancelled("Stopped by sender"), f.receiver.state.value)
        assertEquals(1, f.resources.discards)
        assertEquals(0, f.resources.commits)
        assertEquals(1, f.resources.senderCleanup)
        assertEquals(1, f.resources.receiverCleanup)
    }

    @Test
    fun receiverCancelReasonReachesSenderAndCleanupIsIdempotent() = runTest {
        val f = fixture(verifyMs = 40_000)
        val (a, b) = LoopbackConnection.createPair()
        f.sender.attachConnection(a)
        f.receiver.attachConnection(b)
        runCurrent()
        assertTrue(f.receiver.cancel("Stopped by receiver"))
        runCurrent()
        assertEquals(SenderState.Cancelled("Stopped by receiver"), f.sender.state.value)
        assertFalse(f.receiver.cancel("again"))
        assertFalse(f.sender.cancel("again"))
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.resources.sourceCloses)
        assertEquals(1, f.resources.discards)
        assertEquals(1, f.resources.senderCleanup)
        assertEquals(1, f.resources.receiverCleanup)
        assertTrue(f.sender.reset())
        assertTrue(f.receiver.reset())
        runCurrent()
        assertEquals(SenderState.Idle, f.sender.state.value)
        assertEquals(ReceiverState.Idle, f.receiver.state.value)
    }

    @Test
    fun scopeCancellationClosesPendingReadsAndSources() = runTest {
        val ownerJob = Job(backgroundScope.coroutineContext[Job])
        val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
        val f = fixture(owner = owner)
        val (a, _) = LoopbackConnection.createPair()
        f.sender.attachConnection(a)
        runCurrent()
        ownerJob.cancel()
        runCurrent()
        assertIs<SenderState.Cancelled>(f.sender.state.value)
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
        assertTrue(a.isClosed)
        assertEquals(1, f.resources.sourceCloses)
        assertEquals(1, f.resources.senderCleanup)
        assertEquals(1, f.resources.receiverCleanup)
    }

    @Test
    fun scopeCancellationBeforeAWorkerStartsStillClosesSources() = runTest {
        val ownerJob = Job(backgroundScope.coroutineContext[Job])
        val f = fixture(owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob))
        ownerJob.cancel()
        runCurrent()
        assertEquals(1, f.resources.sourceCloses)
        assertEquals(1, f.resources.senderCleanup)
        assertEquals(1, f.resources.receiverCleanup)
    }

    @Test
    fun idleLossClosesSocketAndReconnectExpiryClosesSession() = runTest {
        val f = fixture(timeouts = Timeouts(socketIdleMs = 100, linkingMs = 200,
            pingIntervalMs = 1_000))
        val (a, _) = LoopbackConnection.createPair()
        f.sender.attachConnection(a)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(a.isClosed)
        val lost = assertIs<SenderState.Failed>(f.sender.state.value)
        assertTrue(lost.canResume)
        assertTrue(lost.error.contains("Socket idle timeout"))
        assertEquals(0, f.resources.sourceCloses)
        advanceTimeBy(200)
        runCurrent()
        assertFalse(assertIs<SenderState.Failed>(f.sender.state.value).canResume)
        assertEquals(1, f.resources.sourceCloses)
        assertEquals(1, f.resources.senderCleanup)
        val (c, _) = LoopbackConnection.createPair()
        assertFalse(f.sender.attachConnection(c))
        assertTrue(c.isClosed)
    }

    @Test
    fun userCancelAfterLossClearsBitmapAndPartialData() = runTest {
        val f = fixture(verifyMs = 40_000)
        val (a, b) = LoopbackConnection.createPair()
        f.sender.attachConnection(a)
        f.receiver.attachConnection(b)
        runCurrent()
        assertTrue(f.store.loadBitmap(f.sid, "file", 1).isComplete())
        a.close()
        runCurrent()
        assertTrue(assertIs<ReceiverState.Failed>(f.receiver.state.value).canResume)
        assertEquals(0, f.resources.discards)
        assertTrue(f.receiver.cancel("Discard partial file"))
        runCurrent()
        assertEquals(1, f.resources.discards)
        assertEquals(listOf(0), f.store.loadBitmap(f.sid, "file", 1).getMissingChunks())
        assertEquals(0, f.resources.memory.toByteArray().size)
    }

    @Test
    fun checksumFailureIsTerminalAndDeletesResumeState() = runTest {
        val f = fixture(matches = false)
        val (a, b) = LoopbackConnection.createPair()
        f.sender.attachConnection(a)
        f.receiver.attachConnection(b)
        runCurrent()
        assertFalse(assertIs<ReceiverState.Failed>(f.receiver.state.value).canResume)
        assertFalse(assertIs<SenderState.Failed>(f.sender.state.value).canResume)
        assertEquals(1, f.resources.discards)
        assertEquals(1, f.resources.sourceCloses)
        assertEquals(listOf(0), f.store.loadBitmap(f.sid, "file", 1).getMissingChunks())
    }

    @Test
    fun invalidAuthIsTerminalAndReasonIsSentBeforeClose() = runTest {
        val f = fixture()
        val (a, b) = LoopbackConnection.createPair()
        f.receiver.attachConnection(b)
        a.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH,
            AuthMessage(f.sid, ByteArray(32))))
        runCurrent()
        val failed = assertIs<ReceiverState.Failed>(f.receiver.state.value)
        assertFalse(failed.canResume)
        val reason = ProtocolCodec.decodePayload<CancelMessage>(a.readFrame()).reason
        assertTrue(reason.contains("AUTH tag verification failed"))
        assertTrue(b.isClosed)
        assertEquals(1, f.resources.receiverCleanup)
    }

    @Test
    fun malformedTransportFrameIsTerminalRatherThanResumable() = runTest {
        val f = fixture()
        val (a, _) = LoopbackConnection.createPair()
        f.sender.attachConnection(object : Connection by a {
            override suspend fun readFrame(): RawFrame =
                throw IllegalArgumentException("Untrusted frame length")
        })
        runCurrent()
        assertFalse(assertIs<SenderState.Failed>(f.sender.state.value).canResume)
        assertEquals(1, f.resources.sourceCloses)
    }

    @Test
    fun resumableFailurePolicyAllowsAttachAndCancelButRequiresTerminalBeforeReset() {
        for (event in SessionEvent.entries) {
            val expected = event == SessionEvent.ATTACH || event == SessionEvent.CANCEL
            assertEquals(expected, SessionPolicy.sender(SenderState.Failed("EOF", true), event))
            assertEquals(expected, SessionPolicy.receiver(ReceiverState.Failed("EOF", true), event))
        }
    }

    @Test
    fun scopeCancellationUnblocksRealTcpRead() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(5_000) {
            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(Dispatchers.IO + ownerJob)
            val cleaned = CompletableDeferred<Unit>()
            val reading = CompletableDeferred<Unit>()
            val sender = SenderStateMachine(owner, onCleanup = { cleaned.complete(Unit) })
            val receiver = ReceiverStateMachine(owner)
            val server = createPlatformServerSocket(0)
            var peer: com.yodrop.core.platform.PlatformSocket? = null
            try {
                val bytes = byteArrayOf(1)
                val file = FileMeta("file", "file.dat", 1, "application/octet-stream",
                    CryptoEngine.sha256(bytes))
                sender.onGrabbed(listOf(file), mapOf("file" to MemoryFileSource("file", bytes)))
                receiver.startScanning()
                receiver.onOfferDiscovered(assertIs<SenderState.Advertising>(sender.state.value).offer)
                sender.onClaimReceived(receiver.claimOffer())
                receiver.state.first { it is ReceiverState.AcceptPrompt }
                sender.onDecisionReceived(receiver.acceptTransfer(link), link)
                val accepted = async { server.accept() }
                peer = createPlatformSocket().also { it.connect("127.0.0.1", server.localPort) }
                val socket = accepted.await()
                sender.attachConnection(object : Connection {
                    override val isClosed: Boolean get() = !socket.isConnected
                    override suspend fun readFrame(): RawFrame {
                        reading.complete(Unit)
                        return socket.readFrame()
                    }
                    override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
                    override suspend fun close() = socket.close()
                })
                reading.await()
                ownerJob.cancelAndJoin()
                cleaned.await()
                assertFalse(socket.isConnected)
                assertIs<SenderState.Cancelled>(sender.state.value)
            } finally {
                ownerJob.cancel()
                peer?.close()
                server.close()
            }
        }
    }

    @Test
    fun diskDiscardRemovesPartAndRetainsExistingDestination() = runBlocking {
        val dir = Files.createTempDirectory("yodrop-discard").toFile()
        try {
            val target = dir.resolve("file.dat").apply { writeText("keep") }
            val sink = DiskFileSink("file", target)
            sink.writeChunk(0, byteArrayOf(1, 2, 3))
            assertTrue(dir.resolve("file.dat.part").exists())
            sink.discard()
            assertFalse(dir.resolve("file.dat.part").exists())
            assertEquals("keep", target.readText())
        } finally { dir.deleteRecursively() }
    }
}
