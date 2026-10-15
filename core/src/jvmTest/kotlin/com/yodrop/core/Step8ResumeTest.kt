package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transfer.*
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class Step8ResumeTest {
    private val chunkSize = 4 * 1024 * 1024

    private class SocketConnection(val socket: PlatformSocket) : Connection {
        override val isClosed: Boolean get() = !socket.isConnected
        override suspend fun readFrame(): RawFrame = socket.readFrame()
        override suspend fun writeFrame(frame: RawFrame) = socket.writeFrame(frame)
        override suspend fun close() = socket.close()
    }

    private suspend fun pair(): Pair<SocketConnection, SocketConnection> = coroutineScope {
        val server = createPlatformServerSocket(0)
        var client: PlatformSocket? = null
        try {
            val accepted = async { server.accept() }
            client = createPlatformSocket().also { it.connect("127.0.0.1", server.localPort) }
            SocketConnection(accepted.await()) to SocketConnection(client)
        } catch (e: Throwable) {
            client?.close()
            throw e
        } finally { server.close() }
    }

    private fun makeFile(file: File, megabytes: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(1024 * 1024) { (it % 241).toByte() }
        file.outputStream().buffered().use { output ->
            repeat(megabytes) { output.write(block); digest.update(block) }
        }
        return digest.digest()
    }

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                digest.update(block, 0, n)
            }
        }
        return digest.digest()
    }

    private suspend fun handshake(sender: SenderStateMachine, receiver: ReceiverStateMachine) {
        receiver.startScanning()
        receiver.onOfferDiscovered(assertIs<SenderState.Advertising>(sender.state.value).offer)
        sender.onClaimReceived(receiver.claimOffer())
        receiver.state.first { it is ReceiverState.AcceptPrompt }
        val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 1234)
        sender.onDecisionReceived(receiver.acceptTransfer(link), link)
    }

    private suspend fun awaitDone(sender: SenderStateMachine, receiver: ReceiverStateMachine) {
        val s = sender.state.first { it is SenderState.Done || it is SenderState.Failed }
        assertEquals(SenderState.Done, s)
        val r = receiver.state.first { it is ReceiverState.Done || it is ReceiverState.Failed }
        assertEquals(ReceiverState.Done, r)
    }

    @Test
    fun realTcp256MiBDropAtFortyPercentResumesOnlyMissingChunks() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(120_000) {
            val dir = Files.createTempDirectory("yodrop-step8-256m").toFile()
            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(Dispatchers.IO + ownerJob)
            val senderClosed = CompletableDeferred<Unit>()
            val receiverClosed = CompletableDeferred<Unit>()
            val sender = SenderStateMachine(owner, onCleanup = { senderClosed.complete(Unit) })
            var receiver: ReceiverStateMachine? = null
            try {
                val sourceFile = dir.resolve("source.dat")
                val expected = makeFile(sourceFile, 256)
                val destination = dir.resolve("received.dat")
                val file = FileMeta("large", "source.dat", sourceFile.length(),
                    "application/octet-stream", expected)
                val diskStore = DiskResumeStore(dir.resolve("resume"))
                val durableCheckpoint = CompletableDeferred<Unit>()
                val store = object : ResumeStore by diskStore {
                    override suspend fun saveBitmap(sessionId: ByteArray, fileId: String, bitmap: ChunkBitmap) {
                        diskStore.saveBitmap(sessionId, fileId, bitmap)
                        val received = (0 until bitmap.totalChunks).count { bitmap.isReceived(it) }
                        if (received == 26 && !durableCheckpoint.isCompleted) {
                            // Pause the real receiver only after the 26th chunk is durable.
                            durableCheckpoint.complete(Unit)
                            awaitCancellation()
                        }
                    }
                }
                receiver = ReceiverStateMachine(owner, store,
                    onCleanup = { receiverClosed.complete(Unit) },
                    sinkFactory = { DiskFileSink(it.id, destination) })
                sender.onGrabbed(listOf(file), mapOf(file.id to DiskFileSource(file.id, sourceFile)))
                val sid = assertIs<SenderState.Advertising>(sender.state.value).offer.sessionId.copyOf()
                handshake(sender, receiver)
                val (a, b) = pair()
                sender.attachConnection(a)
                receiver.attachConnection(b)
                durableCheckpoint.await()
                a.close()
                b.close()
                assertTrue(assertIs<SenderState.Failed>(sender.state.first { it is SenderState.Failed }).canResume)
                assertTrue(assertIs<ReceiverState.Failed>(receiver.state.first { it is ReceiverState.Failed }).canResume)

                // A fresh store instance reads the on-disk snapshot from the killed connection.
                val saved = DiskResumeStore(dir.resolve("resume")).loadBitmap(sid, file.id, 64)
                val missing = (26 until 64).toList()
                assertEquals(missing, saved.getMissingChunks(64))
                assertTrue(dir.resolve("received.dat.part").length() >= 26L * chunkSize)
                assertFalse(senderClosed.isCompleted)
                assertFalse(receiverClosed.isCompleted)

                val requested = ConcurrentHashMap.newKeySet<Int>()
                val chunkFrames = AtomicInteger()
                val plaintextBytes = AtomicLong()
                val authFrames = AtomicInteger()
                val (c, d) = pair()
                sender.attachConnection(object : Connection by c {
                    override suspend fun writeFrame(frame: RawFrame) {
                        if (frame.type == FrameType.AUTH) authFrames.incrementAndGet()
                        if (frame.type == FrameType.CHUNK_DATA) {
                            chunkFrames.incrementAndGet()
                            val idLength = frame.payload[0].toInt() and 0xFF
                            plaintextBytes.addAndGet((frame.payload.size - 1 - idLength - 8 - 16).toLong())
                        }
                        c.writeFrame(frame)
                    }
                })
                receiver.attachConnection(object : Connection by d {
                    override suspend fun writeFrame(frame: RawFrame) {
                        if (frame.type == FrameType.AUTH) authFrames.incrementAndGet()
                        if (frame.type == FrameType.CHUNK_REQ) {
                            requested.addAll(ProtocolCodec.decodePayload<ChunkReqMessage>(frame).chunkIndices)
                        }
                        d.writeFrame(frame)
                    }
                })
                awaitDone(sender, receiver)
                senderClosed.await()
                receiverClosed.await()
                assertEquals(missing.toSet(), requested)
                assertEquals(38, chunkFrames.get())
                assertEquals(38L * chunkSize, plaintextBytes.get())
                assertEquals(2, authFrames.get())
                assertEquals(256L * 1024 * 1024, destination.length())
                assertContentEquals(expected, hash(destination))
                assertFalse(dir.resolve("received.dat.part").exists())
                println("Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=${plaintextBytes.get()}; SHA-256 matched")
            } finally {
                withContext(NonCancellable) {
                    sender.cancel("test teardown")
                    receiver?.cancel("test teardown")
                    ownerJob.cancelAndJoin()
                    withTimeoutOrNull(5_000) { senderClosed.await(); receiverClosed.await() }
                    dir.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun truncatedOrMissingPartResetsPersistedBitsAndCompletesOverTcp() = runBlocking<Unit>(Dispatchers.IO) {
        for (missingPart in listOf(false, true)) withTimeout(30_000) {
            val dir = Files.createTempDirectory("yodrop-step8-crash").toFile()
            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(Dispatchers.IO + ownerJob)
            val senderClosed = CompletableDeferred<Unit>()
            val receiverClosed = CompletableDeferred<Unit>()
            val sender = SenderStateMachine(owner, onCleanup = { senderClosed.complete(Unit) })
            val target = dir.resolve("received.dat")
            val store = DiskResumeStore(dir.resolve("resume"))
            val receiver = ReceiverStateMachine(owner, store,
                onCleanup = { receiverClosed.complete(Unit) }, sinkFactory = { DiskFileSink(it.id, target) })
            try {
                val source = dir.resolve("source.dat")
                val expected = makeFile(source, 8)
                val file = FileMeta("crash", "source.dat", source.length(), "application/octet-stream", expected)
                sender.onGrabbed(listOf(file), mapOf(file.id to DiskFileSource(file.id, source)))
                val sid = assertIs<SenderState.Advertising>(sender.state.value).offer.sessionId
                val persisted = ChunkBitmap(2).apply { markReceived(0) }
                store.saveBitmap(sid, file.id, persisted)
                if (!missingPart) RandomAccessFile(dir.resolve("received.dat.part"), "rw").use {
                    it.setLength(chunkSize.toLong() - 1)
                }
                handshake(sender, receiver)
                val requested = ConcurrentHashMap.newKeySet<Int>()
                val (a, b) = pair()
                sender.attachConnection(a)
                receiver.attachConnection(object : Connection by b {
                    override suspend fun writeFrame(frame: RawFrame) {
                        if (frame.type == FrameType.CHUNK_REQ) requested.addAll(
                            ProtocolCodec.decodePayload<ChunkReqMessage>(frame).chunkIndices)
                        b.writeFrame(frame)
                    }
                })
                awaitDone(sender, receiver)
                senderClosed.await()
                receiverClosed.await()
                assertEquals(setOf(0, 1), requested)
                assertContentEquals(expected, hash(target))
            } finally {
                withContext(NonCancellable) {
                    sender.cancel("teardown"); receiver.cancel("teardown")
                    ownerJob.cancelAndJoin()
                    withTimeoutOrNull(5_000) { senderClosed.await(); receiverClosed.await() }
                    dir.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun atomicReplacementReadersOnlySeeCompleteOldOrNewRecords() = runBlocking<Unit>(Dispatchers.IO) {
        val dir = Files.createTempDirectory("yodrop-step8-atomic").toFile()
        try {
            val store = DiskResumeStore(dir)
            val sid = ByteArray(16)
            val count = ProtocolBounds.MAX_TOTAL_CHUNKS
            val partial = ChunkBitmap(count).apply { markReceived(0) }
            val full = ChunkBitmap(count).apply { for (i in 0 until count) markReceived(i) }
            val old = partial.toByteArray()
            val new = full.toByteArray()
            store.saveBitmap(sid, "file", partial)
            // Exercise the production reader from a distinct instance/path spelling.
            val reader = DiskResumeStore(File(dir, "."))
            coroutineScope {
                val writer = launch { repeat(24) { store.saveBitmap(sid, "file", if (it % 2 == 0) full else partial) } }
                repeat(200) {
                    val bytes = reader.loadBitmap(sid, "file", count).toByteArray()
                    assertTrue(bytes.contentEquals(old) || bytes.contentEquals(new))
                    yield()
                }
                writer.join()
            }
            assertTrue(dir.listFiles()!!.none { it.extension == "tmp" })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun malformedBitmapLengthIsRejectedWithoutTrustingPartialBits() = runBlocking<Unit>(Dispatchers.IO) {
        val dir = Files.createTempDirectory("yodrop-step8-bitmap").toFile()
        try {
            val store = DiskResumeStore(dir)
            val sid = ByteArray(16)
            val bits = ChunkBitmap(65).apply { markReceived(0) }
            store.saveBitmap(sid, "file", bits)
            val record = dir.listFiles()!!.single()
            record.writeBytes(byteArrayOf(1))
            assertEquals(65, store.loadBitmap(sid, "file", 65).getMissingChunks(65).size)
            RandomAccessFile(record, "rw").use { it.setLength(1024 * 1024) }
            assertEquals(65, store.loadBitmap(sid, "file", 65).getMissingChunks(65).size)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun fileDataIsForcedBeforeAnyReceivedBitIsPersisted() = runBlocking<Unit> {
        withTimeout(10_000) {
            val senderClosed = CompletableDeferred<Unit>()
            val receiverClosed = CompletableDeferred<Unit>()
            var forced = false
            var saves = 0
            val memory = MemoryFileSink("file")
            val store = object : ResumeStore by InMemoryResumeStore() {
                override suspend fun saveBitmap(sessionId: ByteArray, fileId: String, bitmap: ChunkBitmap) {
                    assertTrue(forced, "Bitmap published before the sink's force completed")
                    assertTrue(bitmap.isReceived(0))
                    saves++
                }
            }
            val sender = SenderStateMachine(this, onCleanup = { senderClosed.complete(Unit) })
            val receiver = ReceiverStateMachine(this, store,
                onCleanup = { receiverClosed.complete(Unit) }, sinkFactory = {
                    object : FileSink by memory {
                        override suspend fun writeChunk(offset: Long, data: ByteArray) {
                            forced = false
                            memory.writeChunk(offset, data)
                        }
                        override suspend fun force() { forced = true }
                    }
                })
            val bytes = byteArrayOf(1, 2, 3)
            val file = FileMeta("file", "file.dat", 3, "text/plain", CryptoEngine.sha256(bytes))
            sender.onGrabbed(listOf(file), mapOf("file" to MemoryFileSource("file", bytes)))
            handshake(sender, receiver)
            val (a, b) = com.yodrop.core.transport.LoopbackConnection.createPair()
            sender.attachConnection(a)
            receiver.attachConnection(b)
            awaitDone(sender, receiver)
            senderClosed.await(); receiverClosed.await()
            assertEquals(1, saves)
        }
    }

    @Test
    fun checksumMismatchDeletesDiskStateEvenIfFileDoneNotificationFails() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(15_000) {
            val dir = Files.createTempDirectory("yodrop-step8-checksum").toFile()
            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(Dispatchers.IO + ownerJob)
            val senderClosed = CompletableDeferred<Unit>()
            val receiverClosed = CompletableDeferred<Unit>()
            val sender = SenderStateMachine(owner, onCleanup = { senderClosed.complete(Unit) })
            val target = dir.resolve("received.dat")
            val store = DiskResumeStore(dir.resolve("resume"))
            val receiver = ReceiverStateMachine(owner, store,
                onCleanup = { receiverClosed.complete(Unit) }, sinkFactory = { DiskFileSink(it.id, target) })
            try {
                val source = dir.resolve("source.dat")
                makeFile(source, 1)
                val file = FileMeta("bad", "source.dat", source.length(), "application/octet-stream", ByteArray(32))
                sender.onGrabbed(listOf(file), mapOf(file.id to DiskFileSource(file.id, source)))
                val sid = assertIs<SenderState.Advertising>(sender.state.value).offer.sessionId
                handshake(sender, receiver)
                val (a, b) = pair()
                sender.attachConnection(a)
                receiver.attachConnection(object : Connection by b {
                    override suspend fun writeFrame(frame: RawFrame) {
                        if (frame.type == FrameType.FILE_DONE) throw IllegalStateException("Peer disappeared")
                        b.writeFrame(frame)
                    }
                })
                val failed = assertIs<ReceiverState.Failed>(receiver.state.first { it is ReceiverState.Failed })
                assertFalse(failed.canResume)
                assertTrue(failed.error.contains("Checksum mismatch"))
                receiverClosed.await()
                assertFalse(target.exists())
                assertFalse(dir.resolve("received.dat.part").exists())
                assertEquals(listOf(0), store.loadBitmap(sid, file.id, 1).getMissingChunks())
                assertTrue(dir.resolve("resume").listFiles()!!.none { it.extension == "bin" })
            } finally {
                withContext(NonCancellable) {
                    sender.cancel("teardown"); receiver.cancel("teardown")
                    ownerJob.cancelAndJoin()
                    withTimeoutOrNull(5_000) { senderClosed.await(); receiverClosed.await() }
                    dir.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun missingPartOnRetainedSinkReopensItsHandleInsteadOfWritingOrphanedFile() = runBlocking<Unit>(Dispatchers.IO) {
        val dir = Files.createTempDirectory("yodrop-step8-orphan").toFile()
        try {
            val target = dir.resolve("file.dat")
            val sink = DiskFileSink("file", target)
            sink.writeChunk(0, byteArrayOf(1, 2, 3))
            sink.force()
            sink.close() // Windows cannot delete an open handle; reconnect still retains this sink.
            assertTrue(dir.resolve("file.dat.part").delete())
            assertFalse(sink.hasDataThrough(3))
            sink.writeChunk(0, byteArrayOf(4, 5, 6))
            sink.force()
            assertTrue(sink.hasDataThrough(3))
            assertTrue(sink.verifySha256(CryptoEngine.sha256(byteArrayOf(4, 5, 6))))
            sink.commit()
            sink.close()
            assertContentEquals(byteArrayOf(4, 5, 6), target.readBytes())
            assertFalse(dir.resolve("file.dat.part").exists(), "Close must not recreate a .part file")
        } finally { dir.deleteRecursively() }
    }
}
