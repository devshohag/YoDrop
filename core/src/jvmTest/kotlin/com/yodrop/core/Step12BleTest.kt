package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.TransportSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step12BleTest {
    private val sid = ByteArray(16) { it.toByte() }
    private val hello = HelloMessage("Android", Platform.ANDROID,
        DeviceCaps.CAP_BLE or DeviceCaps.CAP_WIFI_DIRECT, ByteArray(16) { 7 })

    @Test
    fun eachMessageHasOneExplicitTypeAndRoundTripsAllFields() {
        val messages = listOf(
            BleMessageType.HELLO to hello,
            BleMessageType.OFFER to OfferMessage(sid, ByteArray(32) { 9 }, 2, 1234567, "photo.jpg"),
            BleMessageType.CLAIM to ClaimMessage(sid, ByteArray(32) { 8 }),
            BleMessageType.ACCEPT to DecisionMessage(sid, true),
            BleMessageType.DENY to DecisionMessage(sid, false, "User declined"),
            BleMessageType.LINK_INFO to EncryptedLinkInfoMessage(0, ByteArray(17) { 9 })
        )
        for ((type, message) in messages) {
            val bytes = BleMessageCodec.encode(message)
            assertEquals(type, bytes[0].toInt() and 255)
            val decoded = BleMessageCodec.decode(bytes)
            // Re-encoding covers array-valued fields as well as all CBOR scalar fields.
            assertContentEquals(bytes, BleMessageCodec.encode(decoded))
            assertEquals(message::class, decoded::class)
        }
        val decoded = BleMessageCodec.decode(BleMessageCodec.encode(hello)) as HelloMessage
        assertContentEquals(hello.deviceId, decoded.deviceId)
        assertEquals(hello.capabilities, decoded.capabilities)
    }

    @Test
    fun unknownEmptyTruncatedAndOversizedMessagesAreRejected() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(0x7f, 0),
            ByteArray(BleMessageCodec.MAX_MESSAGE_BYTES + 1))) {
            assertFails { BleMessageCodec.decode(bytes) }
        }
        val valid = BleMessageCodec.encode(hello)
        assertFails { BleMessageCodec.decode(valid.copyOf(valid.size - 3)) }
    }

    @Test
    fun mismatchedTypeDoesNotFallThroughToAnotherDecoder() {
        val bytes = BleMessageCodec.encode(hello)
        bytes[0] = BleMessageType.OFFER.toByte()
        assertFails { BleMessageCodec.decode(bytes) }
    }

    @Test
    fun decisionTypeMustMatchAcceptedFlag() {
        val accept = BleMessageCodec.encode(DecisionMessage(sid, true))
        accept[0] = BleMessageType.DENY.toByte()
        assertFailsWith<IllegalArgumentException> { BleMessageCodec.decode(accept) }
        val deny = BleMessageCodec.encode(DecisionMessage(sid, false))
        deny[0] = BleMessageType.ACCEPT.toByte()
        assertFailsWith<IllegalArgumentException> { BleMessageCodec.decode(deny) }
    }

    @Test
    fun deviceIdAndSessionKeysAreValidatedOnEncodeAndDecode() {
        assertFailsWith<IllegalArgumentException> {
            BleMessageCodec.encode(hello.copy(deviceId = ByteArray(15)))
        }
        val malformedHello = ProtocolCodec.encodeToFrame(1, hello.copy(deviceId = ByteArray(17))).payload
        assertFailsWith<IllegalArgumentException> {
            BleMessageCodec.decode(byteArrayOf(BleMessageType.HELLO.toByte()) + malformedHello)
        }
        assertFailsWith<IllegalArgumentException> {
            BleMessageCodec.encode(ClaimMessage(sid, ByteArray(31)))
        }
        assertFailsWith<IllegalArgumentException> {
            BleMessageCodec.encode(OfferMessage(ByteArray(15), ByteArray(32), 1, 0, "file"))
        }
    }

    @Test
    fun invalidControlMetadataIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            BleMessageCodec.encode(OfferMessage(sid, ByteArray(32), 0, -1, "file"))
        }
        assertFailsWith<IllegalArgumentException> {
            BleMessageCodec.encode(DecisionMessage(sid, false, "x".repeat(513)))
        }
        assertFailsWith<IllegalArgumentException> {
            BleMessageCodec.encode(LinkInfoMessage(LinkType.LAN, "", 0))
        }
    }

    @Test
    fun fullUnicodeOfferSurvivesManyTinyGattFragments() {
        val original = OfferMessage(sid, ByteArray(32) { 12 }, 100, 1L shl 35,
            "東京".repeat(70) + ".dat")
        val encoded = BleMessageCodec.encode(original)
        val packets = BleFragmenter.fragment(42, encoded, 20)
        assertTrue(packets.size > 20)
        val assembler = BleFragmenter.Reassembler()
        var complete: ByteArray? = null
        for (packet in packets) assembler.onPacketReceived(packet)?.let { complete = it }
        val decoded = BleMessageCodec.decode(assertNotNull(complete)) as OfferMessage
        assertContentEquals(original.sessionId, decoded.sessionId)
        assertContentEquals(original.senderPubKey, decoded.senderPubKey)
        assertEquals(original.fileCount, decoded.fileCount)
        assertEquals(original.totalBytes, decoded.totalBytes)
        assertEquals(original.previewName, decoded.previewName)
    }

    private class Fixture(scope: CoroutineScope) {
        val sender = SenderStateMachine(scope)
        val receiver = ReceiverStateMachine(scope)
        private val unusedSender = SenderStateMachine(scope)
        private val unusedReceiver = ReceiverStateMachine(scope)
        private val helloS = HelloMessage("Sender phone", Platform.ANDROID,
            DeviceCaps.CAP_BLE or DeviceCaps.CAP_WIFI_DIRECT, ByteArray(16) { 1 })
        private val helloR = helloS.copy(deviceName = "Receiver phone", deviceId = ByteArray(16) { 2 })
        private val queue = ArrayDeque<Pair<Boolean, ByteArray>>()
        val sent = mutableListOf<BleControlMessage>()
        val sending = BleHandshakeController(sender, unusedReceiver, helloS) { peer, message ->
            assertEquals("receiver", peer)
            sent.add(message)
            queue.addLast(true to BleMessageCodec.encode(message))
        }
        val receiving = BleHandshakeController(unusedSender, receiver, helloR) { peer, message ->
            assertEquals("sender", peer)
            sent.add(message)
            queue.addLast(false to BleMessageCodec.encode(message))
        }

        suspend fun discover() {
            val data = ByteArray(128 * 1024) { (it % 251).toByte() }
            val file = FileMeta("file", "東京".repeat(35) + ".dat", data.size.toLong(),
                "application/octet-stream", CryptoEngine.sha256(data))
            assertTrue(sender.onGrabbed(listOf(file), mapOf(file.id to MemoryFileSource(file.id, data))))
            assertTrue(receiver.startScanning())
            sending.onMessage(BlePeerMessage("receiver", helloR))
            pump()
        }

        suspend fun pump() {
            var count = 0
            while (queue.isNotEmpty()) {
                check(++count < 32) { "Unexpected HELLO/OFFER loop" }
                val (toReceiver, encoded) = queue.removeFirst()
                val assembler = BleFragmenter.Reassembler()
                var full: ByteArray? = null
                for (packet in BleFragmenter.fragment(count, encoded, 20)) {
                    assembler.onPacketReceived(packet)?.let { full = it }
                }
                val message = BleMessageCodec.decode(assertNotNull(full))
                if (toReceiver) receiving.onMessage(BlePeerMessage("sender", message))
                else sending.onMessage(BlePeerMessage("receiver", message))
            }
        }

        suspend fun claim() {
            assertTrue(receiving.claim())
            pump()
        }
    }

    @Test
    fun offerWaitsForTapThenBothPhonesShowSameFourDigitCode() = runTest {
        val f = Fixture(backgroundScope)
        f.discover()
        assertIs<ReceiverState.OfferSeen>(f.receiver.state.value)
        assertIs<SenderState.Advertising>(f.sender.state.value)
        assertFalse(f.sent.any { it is ClaimMessage })
        assertEquals("Sender phone", f.receiving.peerName.value)
        f.claim()
        runCurrent()
        val senderPrompt = assertIs<SenderState.AwaitAccept>(f.sender.state.value)
        val receiverPrompt = assertIs<ReceiverState.AcceptPrompt>(f.receiver.state.value)
        assertEquals(senderPrompt.verifyCode, receiverPrompt.verifyCode)
        assertTrue(senderPrompt.verifyCode.matches(Regex("[0-9]{4}")))
        assertEquals(TransportSelection.DataLink(LinkType.WIFI_DIRECT), f.sending.selection.value)
        assertFalse(f.receiving.claim())
        assertFalse(f.sent.any { it is DecisionMessage })
    }

    @Test
    fun acceptReachesBothLinkingStatesWithoutFabricatedEndpoint() = runTest {
        val f = Fixture(backgroundScope)
        f.discover(); f.claim(); runCurrent()
        assertTrue(f.receiving.accept())
        f.pump()
        assertNull(assertIs<SenderState.Linking>(f.sender.state.value).linkInfo)
        assertNull(assertIs<ReceiverState.Linking>(f.receiver.state.value).linkInfo)
        assertFalse(f.receiving.accept())
        assertEquals(1, f.sent.filterIsInstance<DecisionMessage>().count { it.accepted })
    }

    @Test
    fun denyReachesSenderFailureAndReceiverCancellation() = runTest {
        val f = Fixture(backgroundScope)
        f.discover(); f.claim(); runCurrent()
        assertTrue(f.receiving.deny())
        f.pump()
        assertIs<ReceiverState.Cancelled>(f.receiver.state.value)
        val failed = assertIs<SenderState.Failed>(f.sender.state.value)
        assertFalse(failed.canResume)
        assertTrue(failed.error.contains("denied"))
        assertFalse(f.receiving.deny())
    }

    @Test
    fun wrongPeerAndPrematureLinkInfoCannotAdvanceAcceptedOffer() = runTest {
        val f = Fixture(backgroundScope)
        f.discover()
        val offer = assertIs<ReceiverState.OfferSeen>(f.receiver.state.value).offer
        val link = LinkInfoMessage(LinkType.LAN, "10.0.0.2", 8888)
        assertFalse(f.receiving.onMessage(BlePeerMessage("sender", link)))
        f.claim(); runCurrent()
        assertFalse(f.sending.onMessage(BlePeerMessage("stranger", DecisionMessage(offer.sessionId, true))))
        assertIs<SenderState.AwaitAccept>(f.sender.state.value)
        f.receiving.accept(); f.pump()
        assertFalse(f.receiving.onMessage(BlePeerMessage("sender", link)))
        val encrypted = assertNotNull(f.sender.createEncryptedLinkInfo(link))
        assertFalse(f.receiving.onMessage(BlePeerMessage("stranger", encrypted)))
        assertTrue(f.receiving.onMessage(BlePeerMessage("sender", encrypted)))
        assertEquals(link, assertIs<ReceiverState.Linking>(f.receiver.state.value).linkInfo)
        assertFalse(f.receiving.onMessage(BlePeerMessage("sender", encrypted)))
    }

    @Test
    fun offerWithoutHelloAndActionsBeforeDiscoveryAreIgnored() = runTest {
        val f = Fixture(backgroundScope)
        assertFalse(f.receiving.claim())
        assertFalse(f.receiving.accept())
        assertFalse(f.receiving.deny())
        assertFalse(f.receiving.onMessage(BlePeerMessage("unknown",
            OfferMessage(sid, ByteArray(32), 1, 1, "file"))))
        assertIs<ReceiverState.Idle>(f.receiver.state.value)
    }

    @Test
    fun duplicateForeignClaimsCannotReplaceChosenReceiver() = runTest {
        val f = Fixture(backgroundScope)
        f.discover(); f.claim(); runCurrent()
        val before = f.sender.state.value
        val claimed = f.sent.filterIsInstance<ClaimMessage>().single()
        assertFalse(f.sending.onMessage(BlePeerMessage("stranger", claimed)))
        assertFalse(f.sending.onMessage(BlePeerMessage("receiver", claimed)))
        assertEquals(before, f.sender.state.value)
        assertEquals("Receiver phone", f.sending.peerName.value)
    }
}
