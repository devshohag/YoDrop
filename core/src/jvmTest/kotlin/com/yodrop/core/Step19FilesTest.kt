package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step19FilesTest {
    private class Provider(val bytes: ByteArray, val seekable: Boolean = true) {
        var opens = 0; var closes = 0; var released = 0; var bytesRead = 0; var maximumRead = 0
        var available = bytes.size
        var zeroRead = false
        var seekFailure = false
        var shortReads = Int.MAX_VALUE
        fun source() = RangeFileSource("doc", bytes.size.toLong(), open = {
            opens++
            object : FileReadHandle {
                var position = 0
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    maximumRead = maxOf(maximumRead, length)
                    if (zeroRead) return 0
                    if (position >= available) return -1
                    val n = minOf(length, available - position, shortReads)
                    bytes.copyInto(buffer, offset, position, position + n)
                    position += n; bytesRead += n
                    return n
                }
                override suspend fun seek(offset: Long): Boolean {
                    if (seekFailure) throw IOException("Provider seek failed")
                    if (seekable) position = offset.toInt()
                    return seekable
                }
                override suspend fun close() { closes++ }
            }
        }, release = { released++ })
    }

    @Test fun seekableDocumentsSupportShortAndOutOfOrderReads() = runTest {
        val p = Provider(ByteArray(1024) { it.toByte() }).apply { shortReads = 7 }; val source = p.source()
        for ((offset, n) in listOf(700 to 31, 0 to 99, 400 to 50, 1020 to 50))
            assertContentEquals(p.bytes.copyOfRange(offset, minOf(offset + n, p.bytes.size)), source.readChunk(offset.toLong(), n))
        source.close(); assertEquals(1, p.opens); assertEquals(1, p.closes); assertEquals(1, p.released)
    }

    @Test fun pipeProviderSequentialChunksKeepOneReaderWithoutRecopyingPrefix() = runTest {
        val p = Provider(ByteArray(256 * 1024) { (it % 241).toByte() }, false); val source = p.source()
        repeat(4) { i -> assertContentEquals(p.bytes.copyOfRange(i * 65536, (i + 1) * 65536), source.readChunk(i * 65536L, 65536)) }
        source.close(); assertEquals(1, p.opens); assertEquals(p.bytes.size, p.bytesRead)
    }

    @Test fun pipeBackwardRetryReopensAndForwardSkipUsesBoundedBuffer() = runTest {
        val p = Provider(ByteArray(1024 * 1024) { (it % 251).toByte() }, false); val source = p.source()
        assertContentEquals(p.bytes.copyOfRange(900000, 900100), source.readChunk(900000, 100))
        assertContentEquals(p.bytes.copyOfRange(12, 22), source.readChunk(12, 10))
        assertEquals(2, p.opens); assertEquals(1, p.closes); assertTrue(p.maximumRead <= 65536)
        source.close(); assertEquals(2, p.closes)
    }

    @Test fun invalidRangesCannotAllocateOrOpenAProvider() = runTest {
        val p = Provider(ByteArray(4)); val source = p.source()
        for ((offset, n) in listOf(-1L to 1, 5L to 1, Long.MAX_VALUE to 1, 0L to -1, 0L to Int.MAX_VALUE))
            assertFailsWith<IllegalArgumentException> { source.readChunk(offset, n) }
        assertEquals(0, p.opens)
        assertContentEquals(ByteArray(0), source.readChunk(4, 10))
        source.close()
    }

    @Test fun emptyDocumentNeedsNoReaderButStillReleasesGrant() = runTest {
        val p = Provider(ByteArray(0)); val source = p.source()
        assertContentEquals(ByteArray(0), source.readChunk(0, 65536)); source.close(); source.close()
        assertEquals(0, p.opens); assertEquals(1, p.released)
        assertFailsWith<IllegalStateException> { source.readChunk(0, 0) }
    }

    @Test fun largeDocumentOffsetsStayLongWithoutWholeFileAllocation() = runTest {
        val offset = 3L * 1024 * 1024 * 1024
        var sought = -1L
        val source = RangeFileSource("large", offset + 100, open = {
            object : FileReadHandle {
                var position = 0L
                override suspend fun seek(offset: Long): Boolean { sought = offset; position = offset; return true }
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    repeat(length) { buffer[offset + it] = ((position + it) % 251).toByte() }
                    position += length; return length
                }
                override suspend fun close() {}
            }
        })
        assertContentEquals(ByteArray(16) { ((offset + it) % 251).toByte() }, source.readChunk(offset, 16))
        assertEquals(offset, sought); source.close()
    }

    @Test fun truncatedDocumentClosesBrokenReaderAndRetryStartsClean() = runTest {
        val p = Provider(ByteArray(10) { it.toByte() }).apply { available = 3 }; val source = p.source()
        assertFailsWith<IllegalStateException> { source.readChunk(0, 10) }
        assertEquals(1, p.closes)
        p.available = 10
        assertContentEquals(p.bytes, source.readChunk(0, 10))
        source.close(); assertEquals(2, p.closes); assertEquals(1, p.released)
    }

    @Test fun zeroProgressReadFailsRatherThanSpinning() = runTest {
        val p = Provider(ByteArray(10)).apply { zeroRead = true }; val source = p.source()
        assertFailsWith<IllegalStateException> { source.readChunk(0, 10) }
        assertEquals(1, p.closes); source.close(); assertEquals(1, p.released)
    }

    @Test fun realSeekFailureIsNotDisguisedAsAnUnseekableProvider() = runTest {
        val p = Provider(ByteArray(10)); val source = p.source()
        source.readChunk(0, 1); p.seekFailure = true
        assertFailsWith<IOException> { source.readChunk(5, 1) }
        assertEquals(1, p.opens); assertEquals(1, p.closes); source.close()
    }

    @Test fun cancellationDuringProviderReadClosesHandleAndReleaseIsOnce() = runTest {
        var closed = 0; var released = 0
        val entered = CompletableDeferred<Unit>()
        val source = RangeFileSource("doc", 10, open = {
            object : FileReadHandle {
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int { entered.complete(Unit); awaitCancellation() }
                override suspend fun seek(offset: Long) = false
                override suspend fun close() { closed++ }
            }
        }, release = { released++ })
        val job = launch { source.readChunk(0, 10) }
        entered.await(); job.cancelAndJoin(); source.close(); source.close()
        assertEquals(1, closed); assertEquals(1, released)
    }

    @Test fun concurrentChunkRequestsSerializeOneSharedDescriptor() = runTest {
        val p = Provider(ByteArray(1024) { it.toByte() }); val source = p.source()
        (0..7).map { i -> async { assertContentEquals(p.bytes.copyOfRange(i * 100, i * 100 + 100), source.readChunk(i * 100L, 100)) } }.awaitAll()
        source.close(); assertEquals(1, p.opens); assertEquals(1, p.closes)
    }

    @Test fun providerNamesBecomeValidBoundedBasenames() {
        assertEquals("video.mp4", SelectedFileName.fromDisplayName("../../video.mp4"))
        assertEquals("clip.mp4", SelectedFileName.fromDisplayName("D:\\private\\clip.mp4"))
        assertEquals("a_b_.txt", SelectedFileName.fromDisplayName("a:b?.txt"))
        for (raw in listOf(null, "", ".hidden", "CON", "NUL.txt", "COM1.log", "..")) assertEquals("received_file", SelectedFileName.fromDisplayName(raw))
        for (raw in listOf("ভিডিও".repeat(100), "a..b.txt", "a\u0000b.txt", "trailing. ")) {
            val name = SelectedFileName.fromDisplayName(raw)
            ProtocolBounds.validateFile(FileMeta("doc", name, 0, "application/octet-stream", ByteArray(32)))
        }
    }

    @Test fun stagingPartCommitsBeforePublishAndSuccessIsIdempotent() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("yodrop-publish").toFile()
        try {
            val target = dir.resolve("stage"); val disk = DiskFileSink("f", target); var publishes = 0
            val sink = PublishingFileSink(disk, publish = {
                assertFalse(dir.resolve("stage.part").exists())
                assertContentEquals(byteArrayOf(1, 2, 3), disk.committedFile.readBytes()); publishes++
            }, removeStaging = { target.delete(); Unit })
            sink.writeChunk(0, byteArrayOf(1, 2, 3)); sink.force()
            assertTrue(dir.resolve("stage.part").exists()); assertFalse(target.exists()); assertEquals(0, publishes)
            assertTrue(sink.verifySha256(CryptoEngine.sha256(byteArrayOf(1, 2, 3))))
            sink.commit(); sink.commit(); sink.discard(); sink.close()
            assertEquals(1, publishes); assertFalse(target.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedPublishKeepsStagingUntilTerminalDiscard() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("yodrop-publish-fail").toFile()
        try {
            val target = dir.resolve("stage"); val existing = dir.resolve("existing").apply { writeText("keep") }
            val disk = DiskFileSink("f", target)
            val sink = PublishingFileSink(disk, publish = { throw IOException("Download provider full") }, removeStaging = { target.delete(); Unit })
            sink.writeChunk(0, byteArrayOf(1)); assertFailsWith<IOException> { sink.commit() }
            assertTrue(target.exists()); sink.discard(); sink.discard(); sink.close()
            assertFalse(target.exists()); assertEquals("keep", existing.readText())
            assertFailsWith<IllegalStateException> { sink.commit() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationBeforeCommitDeletesOnlyPartAndNeverPublishes() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("yodrop-publish-cancel").toFile()
        try {
            val target = dir.resolve("stage"); val disk = DiskFileSink("f", target); var publishes = 0
            val sink = PublishingFileSink(disk, publish = { publishes++ }, removeStaging = { target.delete(); Unit })
            sink.writeChunk(0, byteArrayOf(1)); sink.discard(); sink.close()
            assertFalse(dir.resolve("stage.part").exists()); assertEquals(0, publishes)
        } finally { dir.deleteRecursively() }
    }

    @Test fun stagingCommitFailureNeverCallsPublisher() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("yodrop-publish-stage-fail").toFile()
        try {
            dir.resolve("stage.part").mkdir()
            var published = false
            val sink = PublishingFileSink(DiskFileSink("f", dir.resolve("stage")), publish = { published = true }, removeStaging = {})
            assertFailsWith<IOException> { sink.commit() }; assertFalse(published); sink.close()
        } finally { dir.deleteRecursively() }
    }

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        file.inputStream().use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest()
    }

    @Test fun realLanAutoSelectionTransfersMultiFileRangeSourcesAndPublishesOnlyVerifiedFiles() = runBlocking(Dispatchers.IO) {
        transfer(false)
    }
    @Test fun realReceiverTreatsDownloadPublishFailureAsTerminalWithoutFalseFileDone() = runBlocking(Dispatchers.IO) {
        transfer(true)
    }

    private suspend fun transfer(publishFails: Boolean) = withTimeout(30_000) {
        val dir = Files.createTempDirectory("yodrop-step19-lan").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val closed = AtomicInteger(); val published = AtomicInteger(); val successfulFileDone = AtomicInteger()
        val senderCleaned = CompletableDeferred<Unit>(); val receiverCleaned = CompletableDeferred<Unit>()
        var listener: PlatformServerSocket? = null; var accepting: Job? = null
        val sender = SenderStateMachine(owner, onCleanup = { listener?.close(); accepting?.cancelAndJoin(); senderCleaned.complete(Unit) })
        val receiver = ReceiverStateMachine(owner, onCleanup = { receiverCleaned.complete(Unit) }, sinkFactory = { meta ->
            val target = dir.resolve(meta.id + ".stage"); val disk = DiskFileSink(meta.id, target)
            PublishingFileSink(disk, publish = {
                assertContentEquals(meta.sha256, hash(disk.committedFile))
                if (publishFails) throw IOException("Download provider rejected publication")
                Files.copy(disk.committedFile.toPath(), dir.resolve(meta.id + ".download").toPath())
                published.incrementAndGet()
            }, removeStaging = { target.delete(); Unit })
        })
        val unusedSender = SenderStateMachine(owner); val unusedReceiver = ReceiverStateMachine(owner)
        val queue = LinkedBlockingQueue<Pair<Boolean, ByteArray>>()
        val caps = DeviceCaps.CAP_LAN or DeviceCaps.CAP_WIFI_DIRECT or DeviceCaps.CAP_BLE
        val hello = HelloMessage("Sender", Platform.ANDROID, caps, ByteArray(16) { 1 }, "127.0.0.1")
        val selected = CompletableDeferred<TransportSelection>()
        val sending = BleHandshakeController(sender, unusedReceiver, hello,
            selectTransport = {
                sender.selectTransport(Platform.ANDROID, caps, it, LanAddress("127.0.0.1", 8), it.lanIp).also { choice -> selected.complete(choice) }
            }, prepareLink = { _, choice ->
                assertEquals(TransportSelection.DataLink(LinkType.LAN), choice)
                val server = createPlatformServerSocket(0); listener = server
                accepting = owner.launch { sender.attachConnection(TcpConnection(server.accept())) }
                SenderLinkInfoFactory.lan("127.0.0.1", server.localPort)
            }) { _, message -> queue.add(true to BleMessageCodec.encode(message)) }
        val receiving = BleHandshakeController(unusedSender, receiver, hello.copy(deviceName = "Receiver", deviceId = ByteArray(16) { 2 }),
            onLinkReady = {
                val connection = LanTransport().connect(it)
                receiver.attachConnection(object : Connection by connection {
                    override suspend fun writeFrame(frame: RawFrame) {
                        if (frame.type == FrameType.FILE_DONE && ProtocolCodec.decodePayload<FileDoneMessage>(frame).ok)
                            successfulFileDone.incrementAndGet()
                        connection.writeFrame(frame)
                    }
                })
            }) { _, message -> queue.add(false to BleMessageCodec.encode(message)) }
        suspend fun pump() {
            while (true) {
                val item = queue.poll() ?: break
                if (item.first) receiving.onMessage(BlePeerMessage("sender", BleMessageCodec.decode(item.second)))
                else sending.onMessage(BlePeerMessage("receiver", BleMessageCodec.decode(item.second)))
            }
        }
        try {
            val originals = (0..2).map { dir.resolve("original$it") }
            val block = ByteArray(1024 * 1024) { (it % 251).toByte() }
            originals[0].outputStream().use { out -> repeat(16) { out.write(block) }; out.write(byteArrayOf(7, 8, 9)) }
            originals[1].createNewFile(); originals[2].writeBytes(byteArrayOf(1, 3, 5))
            val files = originals.mapIndexed { i, file -> FileMeta("f$i", "same-name.bin", file.length(), "application/octet-stream", hash(file)) }
            val sources = files.mapIndexed { i, meta ->
                meta.id to RangeFileSource(meta.id, meta.size, open = {
                    val input = RandomAccessFile(originals[i], "r")
                    object : FileReadHandle {
                        override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = input.read(buffer, offset, length)
                        override suspend fun seek(offset: Long): Boolean { input.seek(offset); return true }
                        override suspend fun close() { input.close() }
                    }
                }, release = { closed.incrementAndGet(); Unit })
            }.toMap()
            sender.onGrabbed(files, sources); receiver.startScanning()
            sending.onMessage(BlePeerMessage("receiver", hello.copy(deviceId = ByteArray(16) { 2 }))); pump()
            receiving.claim(); pump()
            receiver.state.first { it is ReceiverState.AcceptPrompt }
            assertNull(listener) // No data listener before explicit consent.
            receiving.accept(); pump()
            assertEquals(TransportSelection.DataLink(LinkType.LAN), selected.await())
            val result = receiver.state.first { it is ReceiverState.Done || it is ReceiverState.Failed }
            if (publishFails) {
                assertFalse(assertIs<ReceiverState.Failed>(result).canResume)
                assertEquals(0, published.get())
                assertEquals(0, successfulFileDone.get())
                val peerResult = sender.state.first { it is SenderState.Failed || it is SenderState.Cancelled || it is SenderState.Done }
                assertTrue(peerResult is SenderState.Failed || peerResult is SenderState.Cancelled)
            } else {
                assertEquals(ReceiverState.Done, result)
                assertEquals(SenderState.Done, sender.state.first { it is SenderState.Done || it is SenderState.Failed })
                assertEquals(3, published.get())
                assertEquals(3, successfulFileDone.get())
                for (meta in files) assertContentEquals(meta.sha256, hash(dir.resolve(meta.id + ".download")))
            }
            senderCleaned.await(); receiverCleaned.await()
            assertEquals(3, closed.get())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".stage") || it.name.endsWith(".part") })
            println("Step19: URI-style RangeFileSource -> auto-selected LAN / real TCP / 3 files including empty and duplicate names; publishFailure=$publishFails; grants released=3 (provider/publication ports, not Android device evidence)")
        } finally {
            withContext(NonCancellable) {
                sender.cancel(); receiver.cancel(); owner.cancel(); accepting?.cancelAndJoin(); listener?.close()
                withTimeoutOrNull(3000) { senderCleaned.await(); receiverCleaned.await() }
                dir.deleteRecursively()
            }
        }
    }
}
