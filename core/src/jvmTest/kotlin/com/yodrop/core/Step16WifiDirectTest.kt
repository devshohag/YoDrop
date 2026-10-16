package com.yodrop.core

import com.yodrop.core.crypto.*
import com.yodrop.core.platform.*
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import com.yodrop.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Android callbacks are the test boundary; sessions, crypto, state machines and TCP are real. */
@OptIn(ExperimentalCoroutinesApi::class)
class Step16WifiDirectTest {
    private val credentials = WifiDirectCredentials("DIRECT-ab-YoDrop-unit", "9f6cbf4a7164a561fe903322f1eab832")
    private class PlatformPort(private val owner: Boolean, private val ip: String = "10.77.3.1") : WifiDirectPlatform {
        var present: WifiDirectGroup? = null
        var discoveries = 0; var creates = 0; var joins = 0; var shutdowns = 0
        var barrier: CompletableDeferred<Unit>? = null
        var wrongRole = false
        var activeDuringShutdown = false
        val requested = mutableListOf<WifiDirectCredentials>()
        override suspend fun currentGroup() = present
        override suspend fun discoverPeers() { discoveries++ }
        private suspend fun form(c: WifiDirectCredentials): WifiDirectGroup {
            requested.add(c); barrier?.await()
            return WifiDirectGroup(if (wrongRole) !owner else owner, ip, c, "p2p-test").also { present = it }
        }
        override suspend fun createGroup(credentials: WifiDirectCredentials): WifiDirectGroup { creates++; return form(credentials) }
        override suspend fun joinGroup(credentials: WifiDirectCredentials): WifiDirectGroup { joins++; return form(credentials) }
        override suspend fun shutdown() { activeDuringShutdown = currentCoroutineContext().isActive; shutdowns++; present = null }
    }

    @Test fun privateGroupCredentialsAreRandomBoundedAndRedacted() {
        val generated = (1..100).map { WifiDirectCredentials.generate().also { it.validate() } }
        assertEquals(100, generated.map { it.networkName }.toSet().size)
        assertEquals(100, generated.map { it.passphrase }.toSet().size)
        assertTrue(generated.all { it.networkName.encodeToByteArray().size <= 32 && it.passphrase.length in 8..63 })
        assertFalse(credentials.toString().contains(credentials.passphrase))
    }

    @Test fun malformedOrMissingCredentialsAreRejected() {
        for (name in listOf("", "DIRECT-x", "wrong-ab-name", "DIRECT-!!-name", "DIRECT-ab-" + "x".repeat(40)))
            assertFails { credentials.copy(networkName = name).validate() }
        for (pass in listOf("", "short", "x".repeat(64), "pass\nword")) assertFails { credentials.copy(passphrase = pass).validate() }
        assertFails { WifiDirectCredentials.fromLink(LinkInfoMessage(LinkType.LAN, "10.1.1.1", 1234)) }
        assertFails { WifiDirectCredentials.fromLink(LinkInfoMessage(LinkType.WIFI_DIRECT, "10.1.1.1", 1234)) }
    }

    @Test fun senderIsOwnerAndEndpointUsesActualQueriedAddressAndPort() = runTest {
        val port = PlatformPort(true); val session = WifiDirectSession(port, owner = true)
        val group = session.ensureGroup(credentials)
        val info = session.linkInfo(group, 43210)
        assertEquals("10.77.3.1", info.host); assertEquals(43210, info.port)
        assertEquals(credentials, WifiDirectCredentials.fromLink(info))
        assertEquals(1, port.creates); assertEquals(0, port.joins); assertEquals(1, port.discoveries)
        assertEquals(group, session.ensureGroup())
        assertEquals(1, port.creates)
        session.close()
    }

    @Test fun receiverJoinsOnlyTheEncryptedGroupAndNeverBecomesOwner() = runTest {
        val port = PlatformPort(false); val session = WifiDirectSession(port, owner = false)
        val group = session.ensureGroup(credentials)
        assertFalse(group.isGroupOwner); assertEquals(listOf(credentials), port.requested)
        assertEquals(1, port.joins); assertEquals(0, port.creates)
        assertFails { session.linkInfo(group, 1234) }
        session.close()
    }

    @Test fun unexpectedOwnerRoleAndUnrelatedExistingGroupAreRejected() = runTest {
        val bad = PlatformPort(true).apply { wrongRole = true }
        val session = WifiDirectSession(bad, owner = true)
        assertFails { session.ensureGroup(credentials) }; session.close()
        assertEquals(1, bad.shutdowns)
        val foreign = PlatformPort(true).apply { present = WifiDirectGroup(true, "10.1.1.1", credentials.copy(passphrase = "other-secret"), "p2p-other") }
        val isolated = WifiDirectSession(foreign, owner = true)
        assertFails { isolated.ensureGroup(credentials) }
        assertEquals(0, foreign.creates); assertEquals(0, foreign.discoveries)
        isolated.close()
    }

    @Test fun cancellingDuringGroupCreationRetiresSessionAndClosesOnce() = runTest {
        val port = PlatformPort(true).apply { barrier = CompletableDeferred() }
        val session = WifiDirectSession(port, owner = true)
        val startup = async { runCatching { session.ensureGroup(credentials) } }
        runCurrent(); assertEquals(1, port.creates)
        session.close()
        assertIs<IllegalStateException>(startup.await().exceptionOrNull()); assertEquals(1, port.shutdowns)
        assertTrue(port.activeDuringShutdown)
        session.close(); assertEquals(1, port.shutdowns)
        assertFails { session.ensureGroup(credentials) }
    }

    @Test fun startupDeadlineUsesVirtualTimeAndCleanupRemainsAvailable() = runTest {
        val port = PlatformPort(true).apply { barrier = CompletableDeferred() }
        val session = WifiDirectSession(port, owner = true, timeoutMs = 100)
        val startup = async { runCatching { session.ensureGroup(credentials) } }
        runCurrent(); advanceTimeBy(99); runCurrent(); assertFalse(startup.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue(startup.await().isFailure)
        session.close(); assertEquals(1, port.shutdowns)
    }

    @Test fun concurrentGroupRequestsCannotCreateTwoGroups() = runTest {
        val port = PlatformPort(true).apply { barrier = CompletableDeferred() }
        val session = WifiDirectSession(port, owner = true)
        val first = async { session.ensureGroup(credentials) }; runCurrent()
        assertFails { session.ensureGroup(credentials) }; assertEquals(1, port.creates)
        port.barrier!!.complete(Unit); first.await(); session.close()
    }

    @Test fun radioLossRecreatesTheSamePrivateGroupWithoutReplacingSessionCredentials() = runTest {
        val port = PlatformPort(true); val session = WifiDirectSession(port, owner = true)
        val first = session.ensureGroup(credentials); port.present = null
        val restored = session.ensureGroup()
        assertEquals(first, restored); assertEquals(listOf(credentials, credentials), port.requested)
        assertEquals(2, port.creates); session.close()
    }

    @Test fun changedOwnerAddressCannotSilentlyRedirectAResumedSession() = runTest {
        val port = PlatformPort(true); val session = WifiDirectSession(port, owner = true)
        val first = session.ensureGroup(credentials)
        port.present = first.copy(ownerIp = "10.88.4.1")
        assertFails { session.ensureGroup() }; session.close()
    }

    @Test fun linkInfoNeverExposesGroupCredentialsInBlePlaintextOrDebugStrings() = runTest {
        val port = PlatformPort(true); val session = WifiDirectSession(port, owner = true)
        val group = session.ensureGroup(credentials); val info = session.linkInfo(group, 45000)
        val sid = ByteArray(16) { 4 }; val keys = CryptoEngine.deriveSessionKeys(ByteArray(32) { 9 }, sid)
        val wire = BleMessageCodec.encode(LinkInfoCipher.seal(keys, sid, 0, info))
        for (text in listOf(credentials.networkName, credentials.passphrase)) {
            val secret = text.encodeToByteArray()
            assertFalse((0..wire.size - secret.size).any { i -> secret.indices.all { wire[i + it] == secret[it] } })
            assertFalse(info.toString().contains(text)); assertFalse(group.toString().contains(text))
        }
        assertEquals(credentials, WifiDirectCredentials.fromLink(LinkInfoCipher.open(keys, sid, BleMessageCodec.decode(wire) as EncryptedLinkInfoMessage)))
        session.close()
    }

    @Test fun realStateMachineTerminalHooksReleaseBothGroups() = runTest {
        val a = PlatformPort(true); val b = PlatformPort(false)
        val owner = WifiDirectSession(a, true); val client = WifiDirectSession(b, false)
        val sender = SenderStateMachine(backgroundScope, onCleanup = { owner.close() })
        val receiver = ReceiverStateMachine(backgroundScope, onCleanup = { client.close() })
        handshake(sender, receiver)
        val group = owner.ensureGroup(credentials)
        client.ensureGroup(WifiDirectCredentials.fromLink(owner.linkInfo(group, 40000)))
        sender.cancel("Test cancel"); receiver.cancel("Test cancel"); runCurrent()
        assertEquals(1, a.shutdowns); assertEquals(1, b.shutdowns)
        sender.reset(); receiver.reset(); assertEquals(1, a.shutdowns); assertEquals(1, b.shutdowns)
    }

    @Test fun linkingDeadlineCleansUpBothRealStateMachineGroups() = runTest {
        val a = PlatformPort(true); val b = PlatformPort(false)
        val owner = WifiDirectSession(a, true); val client = WifiDirectSession(b, false)
        val sender = SenderStateMachine(backgroundScope, onCleanup = { owner.close() })
        val receiver = ReceiverStateMachine(backgroundScope, onCleanup = { client.close() })
        handshake(sender, receiver)
        owner.ensureGroup(credentials); client.ensureGroup(credentials)
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertIs<SenderState.Failed>(sender.state.value); assertIs<ReceiverState.Failed>(receiver.state.value)
        assertEquals(1, a.shutdowns); assertEquals(1, b.shutdowns)
    }

    @Test fun receiverResetWaitsForSuspendCleanupToFinish() = runTest {
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val port = PlatformPort(false); val session = WifiDirectSession(port, false)
        val sender = SenderStateMachine(backgroundScope)
        val receiver = ReceiverStateMachine(backgroundScope, onCleanup = { entered.complete(Unit); gate.await(); session.close() })
        handshake(sender, receiver); session.ensureGroup(credentials)
        receiver.cancel(); runCurrent(); assertTrue(entered.isCompleted)
        val reset = async { receiver.reset() }; runCurrent(); assertFalse(reset.isCompleted)
        gate.complete(Unit); assertTrue(reset.await()); assertEquals(1, port.shutdowns)
        assertEquals(ReceiverState.Idle, receiver.state.value)
    }

    @Test fun groupSnapshotRequiresARealAddressAndInterface() {
        val group = WifiDirectGroup(true, "10.1.2.3", credentials, "p2p0")
        group.validate()
        assertFails { group.copy(ownerIp = "0.0.0.0").validate() }
        assertFails { group.copy(ownerIp = "not-an-ip").validate() }
        assertFails { group.copy(interfaceName = "").validate() }
    }

    private suspend fun handshake(sender: SenderStateMachine, receiver: ReceiverStateMachine) {
        val bytes = ByteArray(128 * 1024)
        val meta = FileMeta("f", "private.bin", bytes.size.toLong(), "application/octet-stream", CryptoEngine.sha256(bytes))
        sender.onGrabbed(listOf(meta), mapOf("f" to MemoryFileSource("f", bytes)))
        receiver.startScanning(); receiver.onOfferDiscovered(assertIs<SenderState.Grabbed>(sender.state.value).offer)
        sender.onClaimReceived(receiver.claimOffer())
        receiver.onEncryptedOfferDetailsReceived(assertNotNull(sender.createEncryptedOfferDetails()))
        sender.onDecisionReceived(receiver.acceptTransfer())
    }

    @Test fun realTcpWorkersUseAuthenticatedWifiDirectEndpointAndCleanupOnDone() = runBlocking {
        withTimeout(15_000) {
            val ownerPort = PlatformPort(true, "127.0.0.1"); val clientPort = PlatformPort(false, "127.0.0.1")
            val owner = WifiDirectSession(ownerPort, true); val client = WifiDirectSession(clientPort, false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val server = createPlatformServerSocket(0)
            val bytes = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
            val meta = FileMeta("f", "eight-mib.bin", bytes.size.toLong(), "application/octet-stream", CryptoEngine.sha256(bytes))
            val sink = MemoryFileSink("f")
            val sender = SenderStateMachine(scope, onCleanup = { owner.close() })
            val receiver = ReceiverStateMachine(scope, onCleanup = { client.close() }, sinkFactory = { sink })
            try {
                sender.onGrabbed(listOf(meta), mapOf("f" to MemoryFileSource("f", bytes)))
                receiver.startScanning(); receiver.onOfferDiscovered(assertIs<SenderState.Grabbed>(sender.state.value).offer)
                sender.onClaimReceived(receiver.claimOffer())
                receiver.onEncryptedOfferDetailsReceived(assertNotNull(sender.createEncryptedOfferDetails()))
                sender.onDecisionReceived(receiver.acceptTransfer())
                val group = owner.ensureGroup(credentials)
                val info = owner.linkInfo(group, server.localPort)
                val envelope = assertNotNull(sender.createEncryptedLinkInfo(info))
                val received = assertNotNull(receiver.onEncryptedLinkInfoReceived(BleMessageCodec.decode(BleMessageCodec.encode(envelope)) as EncryptedLinkInfoMessage))
                client.ensureGroup(WifiDirectCredentials.fromLink(received))
                coroutineScope {
                    repeat(4) {
                        launch(Dispatchers.IO) { sender.attachConnection(TcpConnection(server.accept())) }
                        launch(Dispatchers.IO) { receiver.attachConnection(LanTransport().connect(received)) }
                    }
                }
                sender.state.first { it is SenderState.Done || it is SenderState.Failed }
                receiver.state.first { it is ReceiverState.Done || it is ReceiverState.Failed }
                assertEquals(SenderState.Done, sender.state.value); assertEquals(ReceiverState.Done, receiver.state.value)
                assertContentEquals(meta.sha256, CryptoEngine.sha256(sink.toByteArray()))
                sender.reset(); receiver.reset()
                assertEquals(1, ownerPort.shutdowns); assertEquals(1, clientPort.shutdowns)
                println("Step16: encrypted WFD metadata -> real TCP 4 sockets / 8 MiB; SHA-256 matched; both terminal hooks closed groups (platform port stub, not radio evidence)")
            } finally { sender.cancel(); receiver.cancel(); server.close(); scope.cancel() }
        }
    }
}
