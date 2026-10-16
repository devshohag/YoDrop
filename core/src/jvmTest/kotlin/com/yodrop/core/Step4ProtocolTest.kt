package com.yodrop.core

import kotlinx.coroutines.flow.first

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.VerifyCode
import com.yodrop.core.platform.DiskFileSink
import com.yodrop.core.platform.MemoryFileSink
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.ChunkPlanner
import com.yodrop.core.transport.Connection
import com.yodrop.core.transport.LoopbackConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step4ProtocolTest {
    private fun meta(name: String = "a..b.txt", size: Long = 1) =
        FileMeta("file-1", name, size, "application/octet-stream", ByteArray(32))

    @Test
    fun specFrameNumbersAndNackReason() {
        assertEquals(
            listOf<Byte>(5, 6, 7, 8, 9, 10, 10),
            listOf(
                FrameType.ACK, FrameType.NACK, FrameType.FILE_DONE,
                FrameType.CANCEL, FrameType.DONE, FrameType.PING, FrameType.PONG
            )
        )
        val nack = NackMessage("file-1", listOf(0), "BAD_TAG")
        assertEquals(
            nack,
            ProtocolCodec.decodePayload<NackMessage>(
                ProtocolCodec.encodeToFrame(FrameType.NACK, nack)
            )
        )
        val pong = PingPongMessage(123, true)
        assertEquals(
            pong,
            ProtocolCodec.decodePayload<PingPongMessage>(
                ProtocolCodec.encodeToFrame(FrameType.PING, pong)
            )
        )
    }

    @Test
    fun namesAndBounds() {
        meta().validate()
        for (name in listOf(
            "", " ", ".hidden", "../x", "a/../x", "/tmp/x",
            "C:\\x", "CON.txt", "NUL", "COM1.txt", "a\u0000b",
            "bad.", "bad "
        )) {
            assertFailsWith<IllegalArgumentException>(name) {
                meta(name).validate()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            meta(size = Long.MAX_VALUE).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            ManifestMessage(listOf(meta()), 8 * 1024 * 1024).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            ManifestMessage(listOf(meta(), meta())).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkPlanner.calculateTotalChunks(Long.MAX_VALUE, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ChunkBitmap(Int.MAX_VALUE)
        }
        assertEquals(0, ChunkPlanner.calculateTotalChunks(0))
        assertEquals(2, ChunkPlanner.calculateTotalChunks(65537, 65536))
    }

    @Test
    fun signedIntMinimumHashPrefixIsHandled() {
        // First 4 bytes interpreted as a signed Int would be Int.MIN_VALUE.
        val hash = ByteArray(32).also { it[0] = 0x80.toByte() }
        assertEquals("5789", VerifyCode.fromHash(hash))
        assertEquals("0000", VerifyCode.fromHash(ByteArray(32)))
        assertEquals(
            "0001",
            VerifyCode.fromHash(ByteArray(32).also { it[31] = 1 })
        )
    }

    @Test
    fun diskCommitPreservesExistingFile() = runTest {
        val dir = Files.createTempDirectory("yodrop-step4").toFile()
        try {
            val target = File(dir, "report.txt")
            target.writeText("old")
            val sink = DiskFileSink("file-1", target)
            try {
                sink.writeChunk(0, "new".encodeToByteArray())
                sink.commit()
                assertEquals("old", target.readText())
                assertEquals("report (1).txt", sink.committedFile.name)
                assertEquals("new", sink.committedFile.readText())
            } finally {
                sink.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // Only the transport is decorated. Both protocol peers are real machines.
    private fun exercise(mode: String) = runTest {
        val bytes = ByteArray(80) { it.toByte() }
        val file = meta(size = bytes.size.toLong()).copy(
            sha256 = CryptoEngine.sha256(bytes)
        )
        val sink = MemoryFileSink(file.id)
        val sender = SenderStateMachine(backgroundScope)
        val receiver = ReceiverStateMachine(
            backgroundScope,
            sinkFactory = { sink }
        )
        sender.onGrabbed(
            listOf(file),
            mapOf(file.id to MemoryFileSource(file.id, bytes))
        )
        val offer = (sender.state.value as SenderState.Grabbed).offer
        receiver.startScanning()
        receiver.onOfferDiscovered(offer)
        sender.onClaimReceived(receiver.claimOffer())
        receiver.onEncryptedOfferDetailsReceived(checkNotNull(sender.createEncryptedOfferDetails()))
        val link = LinkInfoMessage(LinkType.LAN, "127.0.0.1", 8888)
        receiver.state.first { it is ReceiverState.AcceptPrompt }
        sender.onDecisionReceived(receiver.acceptTransfer(link), link)

        val (senderWire, receiverWire) = LoopbackConnection.createPair()
        val attempts = mutableListOf<Int>()

        val senderConnection = object : Connection by senderWire {
            override suspend fun writeFrame(frame: RawFrame) {
                var outgoing = frame
                if (frame.type == FrameType.MANIFEST) {
                    val manifest = ProtocolCodec.decodePayload<ManifestMessage>(frame)
                    val changed = when (mode) {
                        "large-chunk" -> manifest.copy(chunkSize = 8 * 1024 * 1024)
                        "huge-size" -> manifest.copy(
                            files = listOf(file.copy(size = Long.MAX_VALUE))
                        )
                        "bad-name" -> manifest.copy(
                            files = listOf(file.copy(name = "../escape"))
                        )
                        else -> manifest
                    }
                    outgoing = ProtocolCodec.encodeToFrame(
                        FrameType.MANIFEST, changed
                    )
                }
                if (frame.type == FrameType.CHUNK_DATA) {
                    val payload = frame.payload.copyOf()
                    val indexOffset = 1 + (payload[0].toInt() and 0xff)
                    val attemptOffset = indexOffset + 4
                    var attempt = 0
                    repeat(4) {
                        attempt = (attempt shl 8) or
                            (payload[attemptOffset + it].toInt() and 0xff)
                    }
                    attempts.add(attempt)

                    if (mode == "retry" && attempts.size == 1) return
                    if (mode == "wrong-file") payload[1] = 'x'.code.toByte()
                    if (mode == "wrong-index") {
                        payload[indexOffset + 3] = 1
                    }
                    outgoing = RawFrame(
                        FrameType.CHUNK_DATA,
                        if (mode == "short-chunk")
                            payload.copyOf(payload.size - 1)
                        else payload
                    )
                }
                senderWire.writeFrame(outgoing)
            }
        }

        val receiverConnection = object : Connection by receiverWire {
            override suspend fun writeFrame(frame: RawFrame) {
                if (frame.type != FrameType.CHUNK_REQ) {
                    receiverWire.writeFrame(frame)
                    return
                }
                val request = ProtocolCodec.decodePayload<ChunkReqMessage>(frame)
                val changed = when (mode) {
                    "negative-index" -> request.copy(chunkIndices = listOf(-1))
                    "outside-index" -> request.copy(chunkIndices = listOf(1))
                    "many-indices" -> request.copy(
                        chunkIndices = List(65) { 0 }
                    )
                    "unknown-file" -> request.copy(fileId = "missing")
                    else -> request
                }
                val outgoing = ProtocolCodec.encodeToFrame(
                    FrameType.CHUNK_REQ, changed
                )
                receiverWire.writeFrame(outgoing)
                if (mode == "retry") {
                    // Transport injects a repeated request and drops response 1.
                    receiverWire.writeFrame(outgoing)
                }
            }
        }

        sender.attachConnection(senderConnection)
        receiver.attachConnection(receiverConnection)
        runCurrent()

        when (mode) {
            "normal", "retry" -> {
                assertEquals(SenderState.Done, sender.state.value)
                assertEquals(ReceiverState.Done, receiver.state.value)
                assertContentEquals(bytes, sink.toByteArray())
                if (mode == "retry") {
                    assertEquals(2, attempts.size)
                    assertNotEquals(attempts[0], attempts[1])
                }
            }
            "negative-index", "outside-index",
            "many-indices", "unknown-file" -> {
                assertTrue(
                    sender.state.value is SenderState.Failed,
                    sender.state.value.toString()
                )
            }
            else -> {
                assertTrue(
                    receiver.state.value is ReceiverState.Failed,
                    receiver.state.value.toString()
                )
                assertContentEquals(byteArrayOf(), sink.toByteArray())
            }
        }
    }

    @Test fun realTransfer() = exercise("normal")
    @Test fun repeatedRequestGetsFreshAttempt() = exercise("retry")
    @Test fun rejectsTamperedFileId() = exercise("wrong-file")
    @Test fun rejectsTamperedIndex() = exercise("wrong-index")
    @Test fun rejectsShortChunk() = exercise("short-chunk")
    @Test fun rejectsOversizeChunkManifest() = exercise("large-chunk")
    @Test fun rejectsHugeFileManifest() = exercise("huge-size")
    @Test fun rejectsMaliciousNameManifest() = exercise("bad-name")
    @Test fun senderRejectsNegativeIndex() = exercise("negative-index")
    @Test fun senderRejectsOutsideIndex() = exercise("outside-index")
    @Test fun senderRejectsLargeRequest() = exercise("many-indices")
    @Test fun senderRejectsUnknownFile() = exercise("unknown-file")
}
