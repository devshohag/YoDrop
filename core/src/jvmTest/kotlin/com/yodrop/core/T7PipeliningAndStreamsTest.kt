package com.yodrop.core

import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.LoopbackConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** All protocol handling is performed by the real state machines. Wrappers inject faults only. */
class T7PipeliningAndStreamsTest {
    private class Wire(private val socket: PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }
    private suspend fun tcpPair(): Pair<Connection, Connection> = coroutineScope {
        val server = createPlatformServerSocket(0)
        try {
            val accepted = async { server.accept() }
            val client = createPlatformSocket()
            client.connect("127.0.0.1", server.localPort)
            Wire(accepted.await()) to Wire(client)
        } finally { server.close() }
    }
    private fun makeFile(file: File, megabytes: Int, seed: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(1024 * 1024) { ((it + seed) % 241).toByte() }
        file.outputStream().use { out -> repeat(megabytes) { out.write(block); digest.update(block) } }
        return digest.digest()
    }
    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest()
    }
    private suspend fun handshake(sender: SenderStateMachine, receiver: ReceiverStateMachine) {
        receiver.startScanning()
        receiver.onOfferDiscovered(assertIs<SenderState.Grabbed>(sender.state.value).offer)
        sender.onClaimReceived(receiver.claimOffer())
        receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))
        receiver.state.first { it is ReceiverState.AcceptPrompt }
        val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234)
        sender.onDecisionReceived(receiver.acceptTransfer(link), link)
    }
    private fun header(payload: ByteArray): Pair<String, Pair<Int, Int>> {
        val n = payload[0].toInt() and 0xff
        fun intAt(offset: Int): Int {
            var value = 0
            repeat(4) { value = (value shl 8) or (payload[offset + it].toInt() and 0xff) }
            return value
        }
        return payload.decodeToString(1, 1 + n) to (intAt(1 + n) to intAt(5 + n))
    }

    private fun exercise(mode: String) = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(60_000) {
            val dir = Files.createTempDirectory("yodrop-step9-$mode").toFile()
            val job = SupervisorJob()
            val owner = CoroutineScope(Dispatchers.IO + job)
            val senderClosed = CompletableDeferred<Unit>()
            val receiverClosed = CompletableDeferred<Unit>()
            val sender = SenderStateMachine(owner, onCleanup = { senderClosed.complete(Unit) })
            val receiver = ReceiverStateMachine(owner,
                onCleanup = { receiverClosed.complete(Unit) },
                sinkFactory = { DiskFileSink(it.id, dir.resolve("received-${it.name}")) }, requestWindow = 2)
            val pairs = (0 until 4).map { tcpPair() }
            val firstRequests = AtomicInteger()
            val requestsReady = CompletableDeferred<Unit>()
            val corrupted = AtomicBoolean()
            val nacks = AtomicInteger()
            val auths = AtomicInteger()
            val chunksBySocket = List(4) { AtomicInteger() }
            val attempts = ConcurrentHashMap<String, MutableSet<Int>>()
            val assignments = ConcurrentHashMap<String, AtomicInteger>()
            try {
                val files = listOf(32, 16).mapIndexed { index, size ->
                    val source = dir.resolve("file$index.dat")
                    val sha = makeFile(source, size, index)
                    FileMeta("file$index", source.name, source.length(), "application/octet-stream", sha)
                }
                sender.onGrabbed(files, files.associate { it.id to DiskFileSource(it.id, dir.resolve(it.name)) })
                handshake(sender, receiver)
                pairs.forEachIndexed { index, (a, b) ->
                    assertTrue(sender.attachConnection(object : Connection by a {
                        override suspend fun writeFrame(frame: RawFrame) {
                            if (frame.type == FrameType.AUTH) auths.incrementAndGet()
                            var outgoing = frame
                            if (frame.type == FrameType.CHUNK_DATA) {
                                chunksBySocket[index].incrementAndGet()
                                val (id, numbers) = header(frame.payload)
                                val key = "$id/${numbers.first}"
                                attempts.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(numbers.second)
                                if (index == 0 && (mode == "exhaust" ||
                                    (mode == "retry" && corrupted.compareAndSet(false, true)))) {
                                    val damaged = frame.payload.copyOf()
                                    damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
                                    outgoing = RawFrame(frame.type, damaged)
                                }
                            }
                            a.writeFrame(outgoing)
                        }
                    }))
                    val first = AtomicBoolean(true)
                    assertTrue(receiver.attachConnection(object : Connection by b {
                        override suspend fun writeFrame(frame: RawFrame) {
                            if (frame.type == FrameType.NACK) {
                                val nack = ProtocolCodec.decodePayload<NackMessage>(frame)
                                assertEquals("CHUNK_AUTHENTICATION_FAILED", nack.reason)
                                nacks.incrementAndGet()
                            }
                            if (frame.type == FrameType.CHUNK_REQ) {
                                val request = ProtocolCodec.decodePayload<ChunkReqMessage>(frame)
                                request.chunkIndices.forEach { chunk ->
                                    assignments.computeIfAbsent("${request.fileId}/$chunk") { AtomicInteger() }.incrementAndGet()
                                }
                                if (first.compareAndSet(true, false)) {
                                    if (firstRequests.incrementAndGet() == 4) requestsReady.complete(Unit)
                                    requestsReady.await()
                                }
                            }
                            b.writeFrame(frame)
                        }
                    }))
                }
                val s = sender.state.first { it is SenderState.Done || it is SenderState.Failed || it is SenderState.Cancelled }
                assertEquals(SenderState.Done, s)
                val r = receiver.state.first { it is ReceiverState.Done || it is ReceiverState.Failed || it is ReceiverState.Cancelled }
                assertEquals(ReceiverState.Done, r)
                senderClosed.await(); receiverClosed.await()
                assertEquals(4, auths.get())
                assertTrue(chunksBySocket.all { it.get() > 0 }, "All four real sockets must carry chunks")
                assertEquals(12, assignments.size)
                assertEquals(1, sender.transitions.replayCache.count { it is SenderState.Done })
                assertEquals(1, receiver.transitions.replayCache.count { it is ReceiverState.Done })
                assertFalse(sender.transitions.replayCache.any { it is SenderState.Failed })
                assertFalse(receiver.transitions.replayCache.any { it is ReceiverState.Failed })
                for (file in files) {
                    val target = dir.resolve("received-${file.name}")
                    assertEquals(file.size, target.length())
                    assertContentEquals(file.sha256, hash(target))
                    assertFalse(dir.resolve("received-${file.name}.part").exists())
                }
                if (mode == "retry") {
                    assertEquals(1, nacks.get())
                    assertEquals(1, attempts.values.count { it.size == 2 }, "Retry must use a fresh attempt/nonce counter")
                    assertTrue(assignments.values.all { it.get() == 1 }, "NACK retains its lease; no duplicate initial requests")
                    assertEquals(13, chunksBySocket.sumOf { it.get() })
                } else {
                    assertTrue(nacks.get() >= 2)
                    assertTrue(pairs[0].second.isClosed)
                    assertTrue(assignments.values.any { it.get() > 1 }, "Failed worker's leases must be reassigned")
                }
                println("Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=$mode; NACK=${nacks.get()}; Done once; both SHA-256 matched")
            } finally {
                withContext(NonCancellable) {
                    sender.cancel("teardown"); receiver.cancel("teardown")
                    job.cancelAndJoin()
                    pairs.forEach { (a, b) -> runCatching { a.close() }; runCatching { b.close() } }
                    withTimeoutOrNull(5_000) { senderClosed.await(); receiverClosed.await() }
                    dir.deleteRecursively()
                }
            }
        }
    }

    @Test fun testFourParallelConnectionsAndNackHandling() = exercise("retry")
    @Test fun retryExhaustionOnOneWorkerReassignsItsChunksWithoutFailingSession() = exercise("exhaust")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun fifthConnectionIsRejectedAndClosedOnBothSides() = kotlinx.coroutines.test.runTest {
        val sender = SenderStateMachine(backgroundScope)
        val receiver = ReceiverStateMachine(backgroundScope, sinkFactory = { MemoryFileSink(it.id) })
        val bytes = byteArrayOf(1)
        val file = FileMeta("file", "file.dat", 1, "text/plain",
            com.yodrop.core.crypto.CryptoEngine.sha256(bytes))
        sender.onGrabbed(listOf(file), mapOf("file" to MemoryFileSource("file", bytes)))
        handshake(sender, receiver)
        repeat(4) {
            val (a, b) = LoopbackConnection.createPair()
            assertTrue(sender.attachConnection(a))
            assertTrue(receiver.attachConnection(b))
        }
        val (extraSender, extraReceiver) = LoopbackConnection.createPair()
        assertFalse(sender.attachConnection(extraSender))
        assertFalse(receiver.attachConnection(extraReceiver))
        assertTrue(extraSender.isClosed)
        assertTrue(extraReceiver.isClosed)
        sender.cancel("teardown")
        receiver.cancel("teardown")
    }

    @Test
    fun requestWindowRefillsBeforeItsLastOutstandingChunkArrives() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(15_000) {
            val dir = Files.createTempDirectory("yodrop-step9-window").toFile()
            val job = SupervisorJob()
            val owner = CoroutineScope(Dispatchers.IO + job)
            val senderClosed = CompletableDeferred<Unit>()
            val receiverClosed = CompletableDeferred<Unit>()
            val sender = SenderStateMachine(owner, onCleanup = { senderClosed.complete(Unit) })
            val receiver = ReceiverStateMachine(owner, onCleanup = { receiverClosed.complete(Unit) },
                sinkFactory = { DiskFileSink(it.id, dir.resolve("received.dat")) }, requestWindow = 2)
            val (a, b) = LoopbackConnection.createPair()
            val refill = CompletableDeferred<Unit>()
            try {
                val source = dir.resolve("file.dat")
                val sha = makeFile(source, 20, 3)
                val file = FileMeta("file", source.name, source.length(), "application/octet-stream", sha)
                sender.onGrabbed(listOf(file), mapOf(file.id to DiskFileSource(file.id, source)))
                handshake(sender, receiver)
                sender.attachConnection(object : Connection by a {
                    override suspend fun writeFrame(frame: RawFrame) {
                        if (frame.type == FrameType.CHUNK_DATA && header(frame.payload).second.first == 1) refill.await()
                        a.writeFrame(frame)
                    }
                })
                receiver.attachConnection(object : Connection by b {
                    override suspend fun writeFrame(frame: RawFrame) {
                        if (frame.type == FrameType.CHUNK_REQ &&
                            2 in ProtocolCodec.decodePayload<ChunkReqMessage>(frame).chunkIndices) refill.complete(Unit)
                        b.writeFrame(frame)
                    }
                })
                assertEquals(SenderState.Done, sender.state.first { it is SenderState.Done || it is SenderState.Failed })
                assertEquals(ReceiverState.Done, receiver.state.first { it is ReceiverState.Done || it is ReceiverState.Failed })
                senderClosed.await(); receiverClosed.await()
                assertTrue(refill.isCompleted)
                assertContentEquals(sha, hash(dir.resolve("received.dat")))
            } finally {
                withContext(NonCancellable) {
                    sender.cancel("teardown"); receiver.cancel("teardown")
                    job.cancelAndJoin()
                    withTimeoutOrNull(5_000) { senderClosed.await(); receiverClosed.await() }
                    dir.deleteRecursively()
                }
            }
        }
    }
}
