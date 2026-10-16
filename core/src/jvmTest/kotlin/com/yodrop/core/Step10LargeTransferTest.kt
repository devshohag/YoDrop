package com.yodrop.core

import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class Step10LargeTransferTest {
    private class Wire(private val socket: PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }

    private suspend fun tcpPair(): Pair<Connection, Connection> = coroutineScope {
        val server = createPlatformServerSocket(0)
        var client: PlatformSocket? = null
        try {
            val accepted = async { server.accept() }
            client = createPlatformSocket().also { it.connect("127.0.0.1", server.localPort) }
            Wire(accepted.await()) to Wire(client)
        } catch (e: Throwable) {
            client?.close()
            throw e
        } finally { server.close() }
    }

    private fun writeOneGiB(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(1024 * 1024) { (it % 251).toByte() }
        FileOutputStream(file).use { out ->
            repeat(1024) { index ->
                // Different block prefixes make chunk reordering/duplication visible in the final hash.
                repeat(4) { byte -> block[byte] = (index ushr (byte * 8)).toByte() }
                out.write(block)
                digest.update(block)
            }
            out.channel.force(true)
        }
        return digest.digest()
    }

    private fun streamingHash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest()
    }

    @Test
    fun oneGiBOverFourRealTcpSocketsWith256MiBHeap() = runBlocking<Unit>(Dispatchers.IO) {
        val runtime = Runtime.getRuntime()
        val heapLimit = 256L * 1024 * 1024
        assertTrue(runtime.maxMemory() <= heapLimit,
            "Run this acceptance test with -Xmx256m (core:jvmTest sets maxHeapSize=256m)")
        withTimeout(300_000) {
            val dir = Files.createTempDirectory("yodrop-step10-1g").toFile()
            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(Dispatchers.IO + ownerJob)
            val senderClosed = CompletableDeferred<Unit>()
            val receiverClosed = CompletableDeferred<Unit>()
            val sourceCloses = AtomicInteger()
            val sender = SenderStateMachine(owner, onCleanup = { senderClosed.complete(Unit) })
            val destination = dir.resolve("received.dat")
            val receiver = ReceiverStateMachine(owner,
                resumeStore = DiskResumeStore(dir.resolve("resume")),
                onCleanup = { receiverClosed.complete(Unit) },
                sinkFactory = { DiskFileSink(it.id, destination) })
            val sockets = mutableListOf<Pair<Connection, Connection>>()
            val chunksBySocket = List(4) { AtomicInteger() }
            val indices = ConcurrentHashMap.newKeySet<Int>()
            val plaintextBytes = AtomicLong()
            val peakObservedHeap = AtomicLong()
            val authenticated = AtomicInteger()
            val firstRequests = AtomicInteger()
            val allWorkersReady = CompletableDeferred<Unit>()
            fun sampleHeap() {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peakObservedHeap.accumulateAndGet(used) { previous, current -> maxOf(previous, current) }
            }
            val socketEvents = ConcurrentLinkedQueue<String>()
            fun snapshot(): String = "sender=${sender.state.value}; receiver=${receiver.state.value}; " +
                "chunks=${chunksBySocket.sumOf { it.get() }}; bytes=${plaintextBytes.get()}; " +
                "heapUsedMiB=${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)}"
            fun observed(wire: Connection, label: String): Connection = object : Connection by wire {
                private val closed = AtomicBoolean()
                override suspend fun readFrame(): RawFrame = try {
                    wire.readFrame()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    socketEvents.add("$label READ ${e::class.simpleName}: ${e.message}; ${snapshot()}")
                    throw e
                }
                override suspend fun writeFrame(frame: RawFrame) {
                    try { wire.writeFrame(frame) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        socketEvents.add("$label WRITE type=${frame.type}: ${e.message}; ${snapshot()}")
                        throw e
                    }
                }
                override suspend fun close() {
                    if (closed.compareAndSet(false, true)) {
                        socketEvents.add(Throwable("$label FIRST CLOSE; ${snapshot()}").stackTraceToString())
                    }
                    wire.close()
                }
            }
            suspend fun failureDetails(): String {
                // Observe the peer's failure publication without retrying the transfer.
                withTimeoutOrNull(2_000) {
                    receiver.state.first {
                        it is ReceiverState.Done || it is ReceiverState.Failed || it is ReceiverState.Cancelled
                    }
                }
                return "Step10 failure: ${snapshot()}\n" + socketEvents.joinToString("\n")
            }
            try {
                val started = System.nanoTime()
                val sourceFile = dir.resolve("source.dat")
                val expected = writeOneGiB(sourceFile)
                val fileSize = 1024L * 1024 * 1024
                assertEquals(fileSize, sourceFile.length())
                val meta = FileMeta("large", "source.dat", fileSize, "application/octet-stream", expected)
                val diskSource = DiskFileSource(meta.id, sourceFile)
                sender.onGrabbed(listOf(meta), mapOf(meta.id to object : FileSource by diskSource {
                    override suspend fun close() { sourceCloses.incrementAndGet(); diskSource.close() }
                }))
                receiver.startScanning()
                receiver.onOfferDiscovered(assertIs<SenderState.Grabbed>(sender.state.value).offer)
                sender.onClaimReceived(receiver.claimOffer())
                receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))
                receiver.state.first { it is ReceiverState.AcceptPrompt }
                val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234)
                sender.onDecisionReceived(receiver.acceptTransfer(link), link)

                repeat(4) { socketIndex ->
                    val (senderWire, receiverWire) = tcpPair().also { sockets.add(it) }
                    val a = observed(senderWire, "sender[$socketIndex]")
                    val b = observed(receiverWire, "receiver[$socketIndex]")
                    assertTrue(sender.attachConnection(object : Connection by a {
                        override suspend fun writeFrame(frame: RawFrame) {
                            if (frame.type == FrameType.AUTH) authenticated.incrementAndGet()
                            if (frame.type == FrameType.CHUNK_DATA) {
                                chunksBySocket[socketIndex].incrementAndGet()
                                val payload = frame.payload
                                val idLength = payload[0].toInt() and 0xff
                                val offset = 1 + idLength
                                var index = 0
                                repeat(4) { index = (index shl 8) or (payload[offset + it].toInt() and 0xff) }
                                assertTrue(indices.add(index), "Unexpected duplicate chunk $index")
                                plaintextBytes.addAndGet((payload.size - 1 - idLength - 8 - 16).toLong())
                                sampleHeap()
                            }
                            a.writeFrame(frame)
                        }
                    }))
                    val firstRequest = AtomicBoolean(true)
                    assertTrue(receiver.attachConnection(object : Connection by b {
                        override suspend fun readFrame(): RawFrame {
                            val frame = b.readFrame()
                            if (frame.type == FrameType.CHUNK_DATA) sampleHeap()
                            return frame
                        }
                        override suspend fun writeFrame(frame: RawFrame) {
                            if (frame.type == FrameType.CHUNK_REQ && firstRequest.compareAndSet(true, false)) {
                                if (firstRequests.incrementAndGet() == 4) allWorkersReady.complete(Unit)
                                allWorkersReady.await()
                            }
                            b.writeFrame(frame)
                        }
                    }))
                }
                val sendResult = sender.state.first {
                    it is SenderState.Done || it is SenderState.Failed || it is SenderState.Cancelled
                }
                assertEquals(SenderState.Done, sendResult,
                    if (sendResult != SenderState.Done) failureDetails() else "")
                val receiveResult = receiver.state.first {
                    it is ReceiverState.Done || it is ReceiverState.Failed || it is ReceiverState.Cancelled
                }
                assertEquals(ReceiverState.Done, receiveResult,
                    if (receiveResult != ReceiverState.Done) failureDetails() else "")
                senderClosed.await()
                receiverClosed.await()
                assertEquals(4, authenticated.get())
                assertTrue(chunksBySocket.all { it.get() > 0 }, "All four sockets must carry data")
                assertEquals(256, chunksBySocket.sumOf { it.get() })
                assertEquals((0 until 256).toSet(), indices)
                assertEquals(fileSize, plaintextBytes.get())
                assertEquals(fileSize, destination.length())
                assertContentEquals(expected, streamingHash(destination))
                assertEquals(1, sourceCloses.get())
                assertEquals(1, sender.transitions.replayCache.count { it is SenderState.Done })
                assertEquals(1, receiver.transitions.replayCache.count { it is ReceiverState.Done })
                assertTrue(sockets.all { (a, b) -> a.isClosed && b.isClosed })
                assertFalse(dir.resolve("received.dat.part").exists())
                assertTrue(dir.resolve("resume").listFiles()!!.none { it.extension == "bin" || it.extension == "tmp" })
                println("Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=${plaintextBytes.get()}; " +
                    "heapMaxMiB=${runtime.maxMemory() / (1024 * 1024)}; " +
                    "sampledHeapPeakMiB=${peakObservedHeap.get() / (1024 * 1024)}; " +
                    "SHA-256 matched; seconds=${(System.nanoTime() - started) / 1_000_000_000}")
            } finally {
                withContext(NonCancellable) {
                    sender.cancel("test teardown")
                    receiver.cancel("test teardown")
                    ownerJob.cancelAndJoin()
                    sockets.forEach { (a, b) -> runCatching { a.close() }; runCatching { b.close() } }
                    withTimeoutOrNull(5_000) { senderClosed.await(); receiverClosed.await() }
                    dir.deleteRecursively()
                }
            }
        }
    }
}
