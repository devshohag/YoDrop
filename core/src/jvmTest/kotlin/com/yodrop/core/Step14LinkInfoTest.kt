package com.yodrop.core

import com.yodrop.core.crypto.*
import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.*
import com.yodrop.core.transport.crossplatform.HotspotCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step14LinkInfoTest {
    private val sid = ByteArray(16) { (it + 1).toByte() }
    private val keys = CryptoEngine.deriveSessionKeys(ByteArray(32) { 17 }, sid)
    private val credentials = HotspotCredentials("YoDrop-secret-ssid-7e421", "private-passphrase-9f315", "10.42.8.1", 45678)
    private val link get() = SenderLinkInfoFactory.hotspot(credentials)
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        needle.isNotEmpty() && size >= needle.size && (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }

    @Test
    fun wireContainsNoPlaintextSsidPassphraseOrGatewayAndRoundTrips() {
        val encrypted = LinkInfoCipher.seal(keys, sid, 0, link)
        val wire = BleMessageCodec.encode(encrypted)
        assertEquals(BleMessageType.LINK_INFO, wire[0].toInt())
        for (secret in listOf(credentials.ssid, credentials.passphrase, credentials.gatewayIp)) {
            assertFalse(wire.containsBytes(secret.encodeToByteArray()))
        }
        val decoded = assertIs<EncryptedLinkInfoMessage>(BleMessageCodec.decode(wire))
        assertEquals(link, LinkInfoCipher.open(keys, sid, decoded))
        val packets = BleFragmenter.fragment(7, wire, BleFragmenter.packetSizeForMtu(23))
        val assembler = BleFragmenter.Reassembler()
        var assembled: ByteArray? = null
        for (packet in packets) assembler.onPacketReceived(packet)?.let { assembled = it }
        assertContentEquals(wire, assembled)
    }

    @Test
    fun ciphertextCounterTagAndSessionAadTamperingAreRejected() {
        val message = LinkInfoCipher.seal(keys, sid, 9, link)
        for (index in listOf(0, message.ciphertext.lastIndex)) {
            val changed = message.ciphertext.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<IllegalArgumentException> { LinkInfoCipher.open(keys, sid, message.copy(ciphertext = changed)) }
        }
        assertFails { LinkInfoCipher.open(keys, sid, message.copy(counter = 10)) }
        // Same keys, different AAD: this independently tests session binding.
        assertFails { LinkInfoCipher.open(keys, sid.copyOf().also { it[0] = 99 }, message) }
        assertFails { LinkInfoCipher.open(keys.copy(senderToReceiverKey = keys.receiverToSenderKey), sid, message) }
    }

    @Test
    fun controlCipherDoesNotUseTcpChunkKeyAndNonceDomain() {
        val message = LinkInfoCipher.seal(keys, sid, 0, link)
        val controlNonce = "LINKINFO".encodeToByteArray() + ByteArray(4)
        assertFails { CryptoEngine.decryptAesGcm(keys.senderToReceiverKey, controlNonce, message.ciphertext, sid) }
        assertFails { CryptoEngine.decryptAesGcm(keys.receiverToSenderKey, controlNonce, message.ciphertext, sid) }
        assertFalse(controlNonce.contentEquals(NonceUtils.buildChunkNonce(keys.nonceBase, 0, 0)))
    }

    @Test
    fun unsignedCounterBoundsAndBoundedPlaintextAreEnforced() {
        assertEquals(link, LinkInfoCipher.open(keys, sid,
            LinkInfoCipher.seal(keys, sid, LinkInfoCipher.MAX_COUNTER, link)))
        for (counter in listOf(-1L, LinkInfoCipher.MAX_COUNTER + 1)) {
            assertFails { LinkInfoCipher.seal(keys, sid, counter, link) }
        }
        assertFails { LinkInfoCipher.open(keys, sid, EncryptedLinkInfoMessage(0, ByteArray(16))) }
        assertFails { BleMessageCodec.encode(EncryptedLinkInfoMessage(0, ByteArray(LinkInfoCipher.MAX_CIPHERTEXT_BYTES + 1))) }
        assertFails { LinkInfoCipher.seal(keys, sid, 0,
            link.copy(extra = link.extra + ("padding" to "x".repeat(2048)))) }
    }

    @Test
    fun plaintextLinkInfoIsRejectedEvenUnderTheOldWireTag() {
        assertFailsWith<IllegalArgumentException> { BleMessageCodec.encode(link) }
        val oldPayload = ProtocolCodec.encodeToFrame(1, link).payload
        assertFails { BleMessageCodec.decode(byteArrayOf(BleMessageType.LINK_INFO.toByte()) + oldPayload) }
        assertFails { BleMessageCodec.decode(byteArrayOf(BleMessageType.GATT_PROBE.toByte()) + oldPayload) }
        val probe = GattDiagnosticProbe.request(ByteArray(16))
        assertEquals(BleMessageType.GATT_PROBE, BleMessageCodec.encode(probe)[0].toInt())
        assertEquals(1024, BleMessageCodec.encode(probe).size)
        assertFails { BleMessageCodec.encode(probe.copy(extra = probe.extra + ("passphrase" to credentials.passphrase))) }
    }

    @Test
    fun endpointAndCredentialDebugStringsRedactSecrets() {
        for (text in listOf(link.toString(), credentials.toString(), SenderState.Linking(link).toString())) {
            assertFalse(text.contains(credentials.ssid))
            assertFalse(text.contains(credentials.passphrase))
        }
        assertEquals("10.42.8.1", link.host)
        assertEquals(45678, link.port)
        assertEquals("10.87.0.1", SenderLinkInfoFactory.wifiDirect("10.87.0.1", 54321).host)
    }

    private class Fixture(scope: CoroutineScope, val endpoint: LinkInfoMessage) {
        val sender = SenderStateMachine(scope)
        val receiver = ReceiverStateMachine(scope)
        private val idleSender = SenderStateMachine(scope)
        private val idleReceiver = ReceiverStateMachine(scope)
        var prepared = 0
        var ready: LinkInfoMessage? = null
        var tamper = false
        val sent = mutableListOf<BleControlMessage>()
        private val queue = ArrayDeque<Pair<Boolean, BleControlMessage>>()
        private val helloS = HelloMessage("Sender", Platform.ANDROID, DeviceCaps.CAP_HOTSPOT_HOST, ByteArray(16) { 1 })
        private val helloR = HelloMessage("Receiver", Platform.IOS, DeviceCaps.CAP_HOTSPOT_CLIENT, ByteArray(16) { 2 })
        val sending = BleHandshakeController(sender, idleReceiver, helloS,
            prepareLink = { _, choice ->
                assertEquals(TransportSelection.DataLink(LinkType.HOTSPOT), choice)
                prepared++
                endpoint
            }) { _, message -> sent.add(message); queue.addLast(true to message) }
        val receiving = BleHandshakeController(idleSender, receiver, helloR,
            onLinkReady = { ready = it }) { _, message -> sent.add(message); queue.addLast(false to message) }

        suspend fun discover() {
            val bytes = ByteArray(128 * 1024)
            val meta = FileMeta("f", "file.bin", bytes.size.toLong(), "application/octet-stream", CryptoEngine.sha256(bytes))
            sender.onGrabbed(listOf(meta), mapOf("f" to MemoryFileSource("f", bytes)))
            receiver.startScanning()
            sending.onMessage(BlePeerMessage("receiver", helloR))
            pump()
            receiving.claim()
            pump()
        }
        suspend fun pump() {
            var count = 0
            while (queue.isNotEmpty()) {
                check(++count < 32)
                val (toReceiver, original) = queue.removeFirst()
                val message = if (tamper && original is EncryptedLinkInfoMessage) original.copy(
                    ciphertext = original.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                ) else original
                val decoded = BleMessageCodec.decode(BleMessageCodec.encode(message))
                if (toReceiver) receiving.onMessage(BlePeerMessage("sender", decoded))
                else sending.onMessage(BlePeerMessage("receiver", decoded))
            }
        }
    }

    @Test
    fun endpointIsPreparedBySenderOnlyAfterExplicitAccept() = runTest {
        val f = Fixture(backgroundScope, link)
        f.discover(); runCurrent()
        assertEquals(0, f.prepared)
        assertNull(f.sender.createEncryptedLinkInfo(link))
        assertIs<SenderState.AwaitAccept>(f.sender.state.value)
        f.receiving.accept(); f.pump()
        assertEquals(1, f.prepared)
        assertEquals(link, f.ready)
        assertEquals(link, assertIs<ReceiverState.Linking>(f.receiver.state.value).linkInfo)
        assertEquals(1, f.sent.filterIsInstance<EncryptedLinkInfoMessage>().size)
        assertFalse(f.receiving.accept())
    }

    @Test
    fun denyNeverPreparesEndpointOrEmitsEncryptedLinkInfo() = runTest {
        val f = Fixture(backgroundScope, link)
        f.discover(); runCurrent(); f.receiving.deny(); f.pump()
        assertEquals(0, f.prepared)
        assertTrue(f.sent.none { it is EncryptedLinkInfoMessage })
        assertIs<SenderState.Failed>(f.sender.state.value)
    }

    @Test
    fun tamperedEnvelopeFailsReceiverWithoutCallingEndpointConsumer() = runTest {
        val f = Fixture(backgroundScope, link)
        f.discover(); runCurrent(); f.tamper = true
        f.receiving.accept(); f.pump()
        assertNull(f.ready)
        val failed = assertIs<ReceiverState.Failed>(f.receiver.state.value)
        assertFalse(failed.canResume)
        assertEquals("LINK_INFO authentication failed", failed.error)
        assertFalse(failed.toString().contains(credentials.passphrase))
    }

    @Test
    fun noEnvelopeIsAcceptedBeforeReceiverConsent() = runTest {
        val f = Fixture(backgroundScope, link)
        f.discover(); runCurrent()
        val prompt = assertIs<ReceiverState.AcceptPrompt>(f.receiver.state.value)
        // Sender receives a decision in the fixture, but local receiver has not tapped Accept.
        f.sender.onDecisionReceived(DecisionMessage(prompt.offer.sessionId, true))
        val envelope = assertNotNull(f.sender.createEncryptedLinkInfo(link))
        assertFalse(f.receiving.onMessage(BlePeerMessage("sender", envelope)))
        assertIs<ReceiverState.AcceptPrompt>(f.receiver.state.value)
        assertNull(f.ready)
    }

    @Test
    fun retriesUseFreshCountersAndReceiverRejectsReplayOrEndpointReplacement() = runTest {
        val f = Fixture(backgroundScope, link)
        f.discover(); runCurrent(); f.receiving.accept(); f.pump()
        val first = f.sent.filterIsInstance<EncryptedLinkInfoMessage>().single()
        val retry = assertNotNull(f.sender.createEncryptedLinkInfo(link))
        assertTrue(retry.counter > first.counter)
        assertFalse(retry.ciphertext.contentEquals(first.ciphertext))
        assertFalse(f.receiving.onMessage(BlePeerMessage("sender", first)))
        assertFalse(f.receiving.onMessage(BlePeerMessage("sender", retry)))
        assertNull(f.sender.createEncryptedLinkInfo(link.copy(port = link.port + 1)))
        assertEquals(link, f.ready)
    }

    @Test
    fun sameLanHelloMetadataEnablesSenderSelectionWithRealPrefix() = runTest {
        val f = Fixture(backgroundScope, link)
        f.discover(); runCurrent()
        val peer = HelloMessage("LAN peer", Platform.IOS, DeviceCaps.CAP_LAN, ByteArray(16), "10.30.2.7")
        assertEquals(TransportSelection.DataLink(LinkType.LAN), f.sender.selectTransport(
            Platform.ANDROID, DeviceCaps.CAP_LAN, peer, LanAddress("10.30.1.9", 16), peer.lanIp))
        val decoded = assertIs<HelloMessage>(BleMessageCodec.decode(BleMessageCodec.encode(peer)))
        assertEquals(peer.lanIp, decoded.lanIp)
        assertFails { BleMessageCodec.encode(peer.copy(lanIp = "999.1.2.3")) }
    }

    @Test
    fun concurrentEnvelopeCreationNeverReusesCounter() = runTest {
        val f = Fixture(backgroundScope, link)
        f.discover(); runCurrent(); f.receiving.accept(); f.pump()
        val envelopes = (0 until 32).map { async { assertNotNull(f.sender.createEncryptedLinkInfo(link)) } }.awaitAll()
        assertEquals(32, envelopes.map { it.counter }.distinct().size)
        assertTrue(envelopes.zipWithNext().all { (a, b) -> !a.ciphertext.contentEquals(b.ciphertext) })
    }

    @Test
    fun senderResetDoesNotRewindControlNonceCounter() = runTest {
        val f = Fixture(backgroundScope, link)
        f.discover(); runCurrent(); f.receiving.accept(); f.pump()
        val first = f.sent.filterIsInstance<EncryptedLinkInfoMessage>().last()
        f.sender.cancel("reset"); f.receiver.cancel("reset")
        assertTrue(f.sender.reset()); assertTrue(f.receiver.reset())
        f.sending.reset(); f.receiving.reset()
        f.discover(); runCurrent(); f.receiving.accept(); f.pump()
        val next = f.sent.filterIsInstance<EncryptedLinkInfoMessage>().last()
        assertTrue(next.counter > first.counter)
        assertFalse(next.ciphertext.contentEquals(first.ciphertext))
    }

    @Test
    fun authenticatedBleLinkInfoDrivesOneMiBOverRealTcpWithoutReceiverGuessedEndpoint() = runBlocking {
        withTimeout(15_000) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var listener: PlatformServerSocket? = null
            var accepting: Job? = null
            val sender = SenderStateMachine(owner, onCleanup = { listener?.close(); accepting?.cancelAndJoin() })
            val sink = MemoryFileSink("f")
            val receiver = ReceiverStateMachine(owner, sinkFactory = { sink })
            val unusedSender = SenderStateMachine(owner)
            val unusedReceiver = ReceiverStateMachine(owner)
            val queue = java.util.concurrent.LinkedBlockingQueue<Pair<Boolean, ByteArray>>()
            val hello = HelloMessage("LAN sender", Platform.ANDROID, DeviceCaps.CAP_LAN, ByteArray(16) { 1 }, "127.0.0.1")
            val receivedEndpoint = CompletableDeferred<LinkInfoMessage>()
            val sending = BleHandshakeController(sender, unusedReceiver, hello,
                selectTransport = { sender.selectTransport(Platform.ANDROID, DeviceCaps.CAP_LAN, it,
                    LanAddress("127.0.0.1", 8), it.lanIp) },
                prepareLink = { _, _ ->
                    val server = createPlatformServerSocket(0)
                    listener = server
                    accepting = owner.launch { sender.attachConnection(TcpConnection(server.accept())) }
                    SenderLinkInfoFactory.lan("127.0.0.1", server.localPort)
                }) { _, message -> queue.add(true to BleMessageCodec.encode(message)) }
            val receiving = BleHandshakeController(unusedSender, receiver,
                hello.copy(deviceName = "LAN receiver", deviceId = ByteArray(16) { 2 }),
                onLinkReady = {
                    receivedEndpoint.complete(it)
                    receiver.attachConnection(LanTransport().connect(it))
                }) { _, message -> queue.add(false to BleMessageCodec.encode(message)) }
            suspend fun pump() {
                while (true) {
                    val item = queue.poll() ?: break
                    if (item.first) receiving.onMessage(BlePeerMessage("sender", BleMessageCodec.decode(item.second)))
                    else sending.onMessage(BlePeerMessage("receiver", BleMessageCodec.decode(item.second)))
                }
            }
            try {
                val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
                val meta = FileMeta("f", "data.bin", data.size.toLong(), "application/octet-stream", CryptoEngine.sha256(data))
                sender.onGrabbed(listOf(meta), mapOf("f" to MemoryFileSource("f", data)))
                receiver.startScanning()
                sending.onMessage(BlePeerMessage("receiver", hello.copy(deviceId = ByteArray(16) { 2 })))
                pump(); receiving.claim(); pump()
                receiver.state.first { it is ReceiverState.AcceptPrompt }
                receiving.accept(); pump()
                assertEquals(listener!!.localPort, receivedEndpoint.await().port)
                assertEquals(SenderState.Done, sender.state.first { it is SenderState.Done || it is SenderState.Failed })
                assertEquals(ReceiverState.Done, receiver.state.first { it is ReceiverState.Done || it is ReceiverState.Failed })
                assertContentEquals(data, sink.toByteArray())
                println("Step14: authenticated BLE LINK_INFO -> real TCP 1 MiB; sender-selected ephemeral port; SHA-256 matched")
            } finally {
                sender.cancel("test teardown"); receiver.cancel("test teardown")
                listener?.close(); accepting?.cancelAndJoin()
                owner.cancel()
            }
        }
    }
}
