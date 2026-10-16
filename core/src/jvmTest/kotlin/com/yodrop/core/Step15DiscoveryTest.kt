package com.yodrop.core

import com.yodrop.core.crypto.*
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import java.util.UUID
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class Step15DiscoveryTest {
    private val secret = "private-東京-payroll-2026.pdf"
    private val sid = ByteArray(16) { it.toByte() }
    private val keys = CryptoEngine.deriveSessionKeys(ByteArray(32) { 9 }, sid)
    private val hello = HelloMessage("Public sender", Platform.ANDROID, DeviceCaps.CAP_LAN, ByteArray(16))
    private fun ByteArray.containsText(text: String): Boolean {
        val needle = text.encodeToByteArray()
        return size >= needle.size && (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }
    }
    private suspend fun grab(sender: SenderStateMachine): OfferMessage {
        val bytes = byteArrayOf(1)
        val file = FileMeta("f", secret, 1, "application/pdf", CryptoEngine.sha256(bytes))
        assertTrue(sender.onGrabbed(listOf(file), mapOf("f" to MemoryFileSource("f", bytes)), hello.deviceName))
        return assertIs<SenderState.Grabbed>(sender.state.value).offer
    }
    private suspend fun claim(sender: SenderStateMachine, receiver: ReceiverStateMachine): EncryptedOfferDetailsMessage {
        receiver.startScanning(); receiver.onOfferDiscovered(grab(sender))
        assertTrue(sender.onClaimReceived(receiver.claimOffer()))
        return assertNotNull(sender.createEncryptedOfferDetails())
    }

    @Test fun legacyPacketContainsOnlyOneRandom128BitServiceAndFits31Bytes() {
        val uuid = UUID.fromString(Uuids.SERVICE_UUID_STRING)
        assertEquals(4, uuid.version()); assertEquals(2, uuid.variant())
        assertFalse(uuid.toString().endsWith("-0000-1000-8000-00805f9b34fb"))
        val adv = BleAdvertisingPacket.advertisement()
        val scan = BleAdvertisingPacket.scanResponse()
        assertEquals(21, adv.size); assertTrue(adv.size <= 31); assertTrue(scan.size <= 31)
        assertContentEquals(byteArrayOf(2, 1, 6, 17, 7), adv.copyOfRange(0, 5))
        val hex = adv.copyOfRange(5, adv.size).reversedArray().joinToString("") { "%02x".format(it) }
        assertEquals(uuid.toString().replace("-", ""), hex)
        assertTrue(scan.isEmpty())
        assertEquals(60_000, BleAdvertisingPacket.TIMEOUT_MS)
        assertEquals(3, setOf(Uuids.SERVICE_UUID_STRING, Uuids.CHAR_WRITE_UUID_STRING, Uuids.CHAR_NOTIFY_UUID_STRING).size)
    }

    @Test fun plainOfferHasExactlyPublicFieldsAndNoFilename() = runTest {
        val offer = grab(SenderStateMachine(backgroundScope))
        val wire = BleMessageCodec.encode(offer)
        for (key in listOf("sessionId", "senderPubKey", "senderName", "fileCount", "totalBytes")) assertTrue(wire.containsText(key))
        assertFalse(wire.containsText("previewName")); assertFalse(wire.containsText(secret))
        assertEquals("Public sender", assertIs<OfferMessage>(BleMessageCodec.decode(wire)).senderName)
    }

    @Serializable private data class LegacyOffer(val sessionId: ByteArray, val senderPubKey: ByteArray,
        val fileCount: Int, val totalBytes: Long, val previewName: String)
    @Test fun legacyPlainFilenameCannotBeDecodedAsNewOffer() {
        val old = Cbor.encodeToByteArray(LegacyOffer.serializer(), LegacyOffer(sid, ByteArray(32), 1, 1, secret))
        assertFails { BleMessageCodec.decode(byteArrayOf(BleMessageType.OFFER.toByte()) + old) }
        val valid = BleMessageCodec.encode(OfferMessage(sid, ByteArray(32), 1, 1, "Public"))
        // Inject a forbidden sixth map field in otherwise valid indefinite-length CBOR.
        assertEquals(0xff.toByte(), valid.last())
        val extra = byteArrayOf(0x6b) + "previewName".encodeToByteArray() + byteArrayOf(0x61, 0x78)
        assertFails { BleMessageCodec.decode(valid.dropLast(1).toByteArray() + extra + byteArrayOf(-1)) }
    }

    @Test fun filenameIsEncryptedAfterClaimAndFragmentedAtBothMtus() = runTest {
        val sender = SenderStateMachine(backgroundScope); val receiver = ReceiverStateMachine(backgroundScope)
        assertNull(sender.createEncryptedOfferDetails())
        val envelope = claim(sender, receiver)
        val wire = BleMessageCodec.encode(envelope)
        assertEquals(BleMessageType.OFFER_DETAILS, wire[0].toInt())
        assertFalse(wire.containsText(secret)); assertFalse(wire.containsText("previewName"))
        for (mtu in listOf(23, 247)) {
            val assembler = BleFragmenter.Reassembler(); var assembled: ByteArray? = null
            for (packet in BleFragmenter.fragment(17, wire, BleFragmenter.packetSizeForMtu(mtu))) {
                assertTrue(packet.size <= mtu - 3)
                assembler.onPacketReceived(packet)?.let { assembled = it }
            }
            assertContentEquals(wire, assembled)
        }
        assertIs<ReceiverState.Claimed>(receiver.state.value)
        assertFails { receiver.acceptTransfer() }
        assertTrue(receiver.onEncryptedOfferDetailsReceived(envelope))
        val prompt = assertIs<ReceiverState.AcceptPrompt>(receiver.state.value)
        assertEquals(secret, prompt.previewName)
        assertEquals(assertIs<SenderState.AwaitAccept>(sender.state.value).verifyCode, prompt.verifyCode)
        assertFalse(receiver.onEncryptedOfferDetailsReceived(envelope))
    }

    @Test fun modifiedCiphertextFailsWithoutOpeningAcceptPrompt() = runTest {
        val sender = SenderStateMachine(backgroundScope); val receiver = ReceiverStateMachine(backgroundScope)
        val envelope = claim(sender, receiver)
        val changed = envelope.copy(ciphertext = envelope.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertFalse(receiver.onEncryptedOfferDetailsReceived(changed))
        assertEquals("OFFER_DETAILS authentication failed", assertIs<ReceiverState.Failed>(receiver.state.value).error)
        assertFails { receiver.acceptTransfer() }
    }

    @Test fun detailsCipherRejectsSessionCounterTagAndDirectionalKeyTampering() {
        val message = OfferDetailsCipher.seal(keys, sid, 12, OfferDetails(secret))
        assertEquals(secret, OfferDetailsCipher.open(keys, sid, message).previewName)
        assertFails { OfferDetailsCipher.open(keys, sid.copyOf().also { it[0] = 99 }, message) }
        assertFails { OfferDetailsCipher.open(keys, sid, message.copy(counter = 13)) }
        assertFails { OfferDetailsCipher.open(keys.copy(senderToReceiverKey = keys.receiverToSenderKey), sid, message) }
        assertFails { OfferDetailsCipher.open(keys, sid, message.copy(ciphertext = message.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })) }
    }

    @Test fun detailsHaveSeparateKeyAndNonceDomainFromLinkInfoAndTcp() {
        val message = OfferDetailsCipher.seal(keys, sid, 0, OfferDetails(secret))
        assertFails { CryptoEngine.decryptAesGcm(keys.senderToReceiverKey, "OFRDTLS1".encodeToByteArray() + ByteArray(4), message.ciphertext, sid) }
        assertFails { LinkInfoCipher.open(keys, sid, EncryptedLinkInfoMessage(message.counter, message.ciphertext)) }
        assertFalse(message.toString().contains(secret)); assertFalse(OfferDetails(secret).toString().contains(secret))
    }

    @Test fun encryptedDetailsHaveBoundedLengthsAndCounters() {
        for (counter in listOf(-1L, OfferDetailsCipher.MAX_COUNTER + 1)) assertFails { OfferDetailsCipher.seal(keys, sid, counter, OfferDetails(secret)) }
        assertEquals(secret, OfferDetailsCipher.open(keys, sid, OfferDetailsCipher.seal(keys, sid, OfferDetailsCipher.MAX_COUNTER, OfferDetails(secret))).previewName)
        assertFails { OfferDetailsCipher.seal(keys, sid, 0, OfferDetails("x".repeat(1025))) }
        assertFails { BleMessageCodec.encode(EncryptedOfferDetailsMessage(0, ByteArray(16))) }
        assertFails { BleMessageCodec.encode(EncryptedOfferDetailsMessage(0, ByteArray(2065))) }
    }

    @Test fun noMetadataStillTimesOutAfterClaim() = runTest {
        val sender = SenderStateMachine(backgroundScope); val receiver = ReceiverStateMachine(backgroundScope)
        claim(sender, receiver); runCurrent()
        advanceTimeBy(59_999); runCurrent(); assertIs<ReceiverState.Claimed>(receiver.state.value)
        advanceTimeBy(1); runCurrent(); assertIs<ReceiverState.Failed>(receiver.state.value)
        assertFails { receiver.acceptTransfer() }
    }

    @Test fun offerAdvertisingStateExpiresAt60SecondsAndCannotRestartWithoutReset() = runTest {
        val sender = SenderStateMachine(backgroundScope); grab(sender); runCurrent()
        advanceTimeBy(59_999); runCurrent(); assertIs<SenderState.Grabbed>(sender.state.value)
        advanceTimeBy(1); runCurrent(); assertIs<SenderState.Failed>(sender.state.value)
        assertFalse(sender.onGrabbed(listOf(FileMeta("f", "f", 1, "x", ByteArray(32))), emptyMap()))
    }

    @Test fun claimAndCancelBothLeaveAdvertisingState() = runTest {
        val sender = SenderStateMachine(backgroundScope); val receiver = ReceiverStateMachine(backgroundScope)
        claim(sender, receiver); assertIs<SenderState.AwaitAccept>(sender.state.value)
        sender.cancel(); assertIs<SenderState.Cancelled>(sender.state.value)
        assertNull(sender.createEncryptedOfferDetails())
    }

    @Test fun metadataCounterDoesNotRewindOnReset() = runTest {
        val sender = SenderStateMachine(backgroundScope)
        val first = claim(sender, ReceiverStateMachine(backgroundScope))
        sender.cancel(); sender.reset()
        val second = claim(sender, ReceiverStateMachine(backgroundScope))
        assertTrue(second.counter > first.counter)
    }

    @Test fun offerCacheIsPerPeerAndSessionAndCopiesArrays() {
        var now = 0L
        val cache = BleOfferCache(ttlMs = 60, maxEntries = 2, nowMs = { now })
        val offer = OfferMessage(sid.copyOf(), ByteArray(32) { 8 }, 1, 1, "Public")
        cache.put("a", offer); offer.sessionId[0] = 99; offer.senderPubKey[0] = 99
        assertEquals(8, assertNotNull(cache.get("a", sid)).senderPubKey[0].toInt())
        assertNull(cache.get("b", sid)); assertTrue(cache.alreadyRead("a"))
        val read = assertNotNull(cache.get("a", sid)); read.senderPubKey[0] = 99
        assertEquals(8, assertNotNull(cache.get("a", sid)).senderPubKey[0].toInt())
        cache.put("b", read); cache.put("c", read)
        assertFalse(cache.alreadyRead("a")); assertTrue(cache.alreadyRead("b"))
        now = 60; assertFalse(cache.alreadyRead("b")); assertNull(cache.get("c", read.sessionId))
        cache.put("c", read); cache.clear(); assertFalse(cache.alreadyRead("c"))
    }

    @Test fun uuidDiscoveryPinsOnePeerBeforeSessionIsRead() {
        val target = BleDiscoveryTarget()
        assertFalse(target.select("")); assertTrue(target.select("ios-peer")); assertFalse(target.select("other"))
        assertFalse(target.matches("ios-peer", sid)); assertFalse(target.bindSession("other", sid))
        assertTrue(target.bindSession("ios-peer", sid))
        assertFalse(target.bindSession("ios-peer", ByteArray(16) { 9 }))
        target.clear(); assertTrue(target.select("other"))
    }

    @Test fun controllerDisconnectsAfterReadAndReconnectsOnlyWhenUserClaims() = runTest {
        val sender = SenderStateMachine(backgroundScope); val receiver = ReceiverStateMachine(backgroundScope)
        val offer = grab(sender); receiver.startScanning()
        val events = mutableListOf<String>(); var sends = 0
        val controller = BleHandshakeController(SenderStateMachine(backgroundScope), receiver, hello,
            onOfferRead = { peer, read -> assertEquals("sender", peer); assertContentEquals(offer.sessionId, read.sessionId); events.add("read-disconnect") },
            connectForClaim = { _, _ -> events.add("reconnect") }) { _, message ->
            assertIs<ClaimMessage>(message); events.add("claim"); sends++
        }
        assertTrue(controller.onMessage(BlePeerMessage("sender", offer)))
        assertFalse(controller.onMessage(BlePeerMessage("sender", offer)))
        assertEquals(listOf("read-disconnect"), events); assertEquals(0, sends)
        assertTrue(controller.claim()); assertEquals(listOf("read-disconnect", "reconnect", "claim"), events)
        assertFalse(controller.claim()); assertEquals(1, sends)
    }

    @Test fun wrongPeerCannotSupplyEncryptedFilename() = runTest {
        val sender = SenderStateMachine(backgroundScope); val receiver = ReceiverStateMachine(backgroundScope)
        val offer = grab(sender); receiver.startScanning()
        var claim: ClaimMessage? = null
        val controller = BleHandshakeController(SenderStateMachine(backgroundScope), receiver, hello) { _, message -> claim = message as ClaimMessage }
        controller.onMessage(BlePeerMessage("sender", offer)); controller.claim()
        sender.onClaimReceived(assertNotNull(claim))
        val details = assertNotNull(sender.createEncryptedOfferDetails())
        assertFalse(controller.onMessage(BlePeerMessage("stranger", details)))
        assertIs<ReceiverState.Claimed>(receiver.state.value)
        assertTrue(controller.onMessage(BlePeerMessage("sender", details)))
        assertEquals(secret, assertIs<ReceiverState.AcceptPrompt>(receiver.state.value).previewName)
    }

    @Test fun denyFinishesGattWriteBeforeTerminalObserversCloseTheLink() = runTest {
        var denyWrites = 0
        val freshSender = SenderStateMachine(backgroundScope)
        val freshReceiver = ReceiverStateMachine(backgroundScope)
        val wire = ArrayDeque<BleControlMessage>()
        val freshOffer = grab(freshSender); freshReceiver.startScanning()
        val bound = BleHandshakeController(SenderStateMachine(backgroundScope), freshReceiver, hello) { _, message ->
            if (message is DecisionMessage) {
                assertIs<ReceiverState.AcceptPrompt>(freshReceiver.state.value)
                assertFalse(message.accepted); denyWrites++
            } else wire.addLast(message)
        }
        bound.onMessage(BlePeerMessage("sender", freshOffer)); bound.claim()
        freshSender.onClaimReceived(assertIs<ClaimMessage>(wire.removeFirst()))
        bound.onMessage(BlePeerMessage("sender", assertNotNull(freshSender.createEncryptedOfferDetails())))
        assertTrue(bound.deny()); assertEquals(1, denyWrites)
        assertIs<ReceiverState.Cancelled>(freshReceiver.state.value)
    }

}
