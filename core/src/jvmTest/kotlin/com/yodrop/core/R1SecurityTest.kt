package com.yodrop.core

import com.yodrop.core.crypto.*
import com.yodrop.core.protocol.*
import com.yodrop.core.trust.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class R1SecurityTest {
    private val sid = ByteArray(16) { it.toByte() }
    private val s = ByteArray(32) { 3 }; private val r = ByteArray(32) { 4 }
    private val keys = CryptoEngine.deriveSessionKeys(ByteArray(32) { 5 }, sid)
    private fun channel(role: PairingRole, id: ByteArray = sid, sender: ByteArray = s, receiver: ByteArray = r, counter: Long = 0) =
        PairingChannel(keys, id, sender, receiver, role, counter)
    private suspend fun proof(c: PairingChannel, identity: R1TestIdentity): PairingControl.Identity {
        val pk = identity.publicKey(); return PairingControl.Identity(pk, identity.sign(c.identityTranscript(pk)))
    }
    @Test fun realP256ProofsVerifyInBothDirections() = runTest {
        val a = channel(PairingRole.SENDER); val b = channel(PairingRole.RECEIVER)
        val ai = proof(a, R1TestIdentity()); val bi = proof(b, R1TestIdentity())
        assertTrue(b.verifies(assertIs<PairingControl.Identity>(b.open(a.seal(ai)))))
        assertTrue(a.verifies(assertIs<PairingControl.Identity>(a.open(b.seal(bi)))))
    }
    @Test fun proofCannotMoveToAnotherSessionEphemeralKeyOrRole() = runTest {
        val a = channel(PairingRole.SENDER); val identity = R1TestIdentity(); val p = proof(a, identity)
        for (bad in listOf(channel(PairingRole.RECEIVER, id = ByteArray(16) { 8 }),
            channel(PairingRole.RECEIVER, sender = ByteArray(32) { 8 }),
            channel(PairingRole.RECEIVER, receiver = ByteArray(32) { 8 }), channel(PairingRole.SENDER)))
            assertFalse(bad.verifies(p))
    }
    @Test fun fullPairingCodeBindsBothIdentitiesInCanonicalRoleOrder() = runTest {
        val a = channel(PairingRole.SENDER); val b = channel(PairingRole.RECEIVER)
        val ai = R1TestIdentity().publicKey(); val bi = R1TestIdentity().publicKey()
        val code = a.trustCode(ai, bi)
        assertEquals(b.trustCode(bi, ai), code)
        assertEquals(6, code.split(" ").size); assertEquals(29, code.length)
        assertNotEquals(code, a.trustCode(ai, R1TestIdentity().publicKey()))
        assertNotEquals(code, channel(PairingRole.SENDER, id = ByteArray(16) { 9 }).trustCode(ai, bi))
    }
    @Test fun borrowedPublicKeyWithoutItsPrivateKeyCannotAuthenticate() = runTest {
        val a = channel(PairingRole.SENDER); val b = channel(PairingRole.RECEIVER)
        val genuine = R1TestIdentity().publicKey(); val impostor = R1TestIdentity()
        assertFalse(b.verifies(PairingControl.Identity(genuine, impostor.sign(a.identityTranscript(genuine)))))
    }
    @Test fun malformedCurveKeysSignaturesAndFingerprintAreRejected() = runTest {
        val identity = R1TestIdentity(); val pk = identity.publicKey(); val sig = identity.sign(s)
        assertTrue(IdentitySignatures.verify(pk, s, sig))
        for (bad in listOf(ByteArray(91), pk.drop(1).toByteArray(), pk.copyOf().also { it[10] = 0 })) {
            assertFalse(IdentitySignatures.verify(bad, s, sig)); assertFails { IdentityKeys.fingerprint(bad) }
        }
        assertFalse(IdentitySignatures.verify(pk, r, sig))
        assertFalse(IdentitySignatures.verify(pk, s, ByteArray(73)))
        assertFalse(IdentitySignatures.verify(pk, s, ByteArray(8)))
    }
    @Test fun encryptedIdentityDoesNotExposePersistentKeyOnWire() = runTest {
        val a = channel(PairingRole.SENDER); val p = proof(a, R1TestIdentity())
        val wire = BleMessageCodec.encode(a.seal(p))
        assertEquals(BleMessageType.PAIRING, wire[0].toInt())
        assertFalse((0..wire.size - p.publicKey.size).any { start -> p.publicKey.indices.all { wire[start + it] == p.publicKey[it] } })
        assertFalse(p.toString().contains(p.publicKey.trustHex()))
    }
    @Test fun encryptedConsentRejectsModificationWrongSessionAndReflection() {
        val a = channel(PairingRole.SENDER); val b = channel(PairingRole.RECEIVER)
        val envelope = b.seal(PairingControl.Consent(true))
        assertFails { a.open(envelope.copy(ciphertext = envelope.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })) }
        assertFails { channel(PairingRole.SENDER, id = ByteArray(16) { 9 }).open(envelope) }
        assertFails { b.open(envelope) }
        assertTrue(assertIs<PairingControl.Consent>(a.open(envelope)).accepted)
    }
    @Test fun replayAndOldCounterCannotBeAcceptedTwice() {
        val a = channel(PairingRole.SENDER); val b = channel(PairingRole.RECEIVER)
        val first = b.seal(PairingControl.Consent(false)); val second = b.seal(PairingControl.Consent(true))
        assertTrue(assertIs<PairingControl.Consent>(a.open(second)).accepted)
        assertFails { a.open(first) }; assertFails { a.open(second) }
    }
    @Test fun counterExhaustionFailsBeforeNonceReuse() {
        val a = channel(PairingRole.SENDER, counter = 0xffff_ffffL)
        val last = a.seal(PairingControl.Consent(true)); assertEquals(0xffff_ffffL, last.counter)
        repeat(2) { assertFails { a.seal(PairingControl.Consent(true)) } }
    }
    @Test fun pairingControlFragmentsAndRoundTripsAt23And247Mtu() = runTest {
        for (mtu in listOf(23, 247)) {
            val a = channel(PairingRole.SENDER); val b = channel(PairingRole.RECEIVER)
            val p = proof(a, R1TestIdentity()); val encoded = BleMessageCodec.encode(a.seal(p))
            val decoder = BleFragmenter.Reassembler(); var bytes: ByteArray? = null
            for (fragment in BleFragmenter.fragment(4, encoded, BleFragmenter.packetSizeForMtu(mtu))) {
                assertTrue(fragment.size <= mtu - 3); decoder.onPacketReceived(fragment)?.let { bytes = it }
            }
            assertContentEquals(encoded, bytes)
            assertTrue(b.verifies(assertIs<PairingControl.Identity>(b.open(assertIs<EncryptedPairingMessage>(BleMessageCodec.decode(assertNotNull(bytes)))))))
        }
    }
    @Test fun invalidPairingEnvelopeLengthsAndReasonsAreBounded() {
        for (counter in listOf(-1L, 0x1_0000_0000L)) assertFails { BleMessageCodec.encode(EncryptedPairingMessage(PairingRole.SENDER, counter, ByteArray(20))) }
        for (size in listOf(16, 2065)) assertFails { BleMessageCodec.encode(EncryptedPairingMessage(PairingRole.SENDER, 0, ByteArray(size))) }
        assertFails { channel(PairingRole.SENDER).seal(PairingControl.Consent(true, "x".repeat(513))) }
    }
    @Test fun pinsSurviveRegistryRecreationAndSameNamesDoNotMergeIdentities() = runTest {
        val storage = R1TestStorage(); val registry = TrustedDeviceRegistry(storage)
        val a = R1TestIdentity(); val b = R1TestIdentity()
        assertTrue(registry.remember(a.publicKey(), "Same name")); assertTrue(registry.remember(b.publicKey(), "Same name"))
        val restarted = TrustedDeviceRegistry(storage); assertTrue(restarted.refresh())
        assertEquals(2, restarted.devices.value.size)
        assertNotNull(restarted.find(a.fingerprint())); assertNotNull(restarted.find(b.fingerprint()))
        val copy = assertNotNull(restarted.find(a.fingerprint())); copy.publicKey.fill(0)
        assertContentEquals(a.publicKey(), assertNotNull(restarted.find(a.fingerprint())).publicKey)
    }
    @Test fun corruptRegistryCannotBecomeEmptyWritableTrust() = runTest {
        val storage = R1TestStorage(); storage.bytes = byteArrayOf(0, 1)
        val registry = TrustedDeviceRegistry(storage)
        assertFalse(registry.refresh()); assertFalse(registry.remember(R1TestIdentity().publicKey(), "Attacker"))
        assertEquals(TrustStorageStatus.UNAVAILABLE, registry.status.value); assertEquals(0, storage.writes)
        assertTrue(registry.devices.value.isEmpty())
    }
    @Test fun failedWritesDoNotGrantTrustAndOldPinsRemainOnDisk() = runTest {
        val storage = R1TestStorage(); val registry = TrustedDeviceRegistry(storage)
        val a = R1TestIdentity(); val b = R1TestIdentity()
        registry.remember(a.publicKey(), "A"); storage.failWrite = true
        assertFalse(registry.remember(b.publicKey(), "B")); assertNull(registry.find(b.fingerprint()))
        assertEquals(listOf(a.fingerprint()), storage.read().map { it.fingerprint })
    }
    @Test fun forgettingAPeerPersistsAndNeverTrustsReplacementByName() = runTest {
        val storage = R1TestStorage(); val registry = TrustedDeviceRegistry(storage); val a = R1TestIdentity()
        registry.remember(a.publicKey(), "Phone"); assertTrue(registry.forget(a.fingerprint()))
        val restart = TrustedDeviceRegistry(storage); restart.refresh()
        assertNull(restart.find(a.fingerprint())); assertNull(restart.find(R1TestIdentity().fingerprint()))
    }
    @Test fun resetIsExplicitRecoveryFromUnavailableStorage() = runTest {
        val storage = R1TestStorage().apply { failRead = true }; val registry = TrustedDeviceRegistry(storage)
        assertFalse(registry.refresh()); assertTrue(registry.reset()); assertEquals(TrustStorageStatus.READY, registry.status.value)
        assertTrue(registry.devices.value.isEmpty())
    }
    @Test fun registryRejectsForgedFingerprintsDuplicateKeysOversizeAndTooManyPeers() = runTest {
        val identity = R1TestIdentity(); val d = TrustedDevice(identity.fingerprint(), "A", identity.publicKey())
        assertFails { TrustedDeviceCodec.encode(listOf(d.copy(fingerprint = "0".repeat(64)))) }
        assertFails { TrustedDeviceCodec.encode(listOf(d, d)) }
        assertFails { TrustedDeviceCodec.encode(listOf(d.copy(label = "x".repeat(257)))) }
        assertFails { TrustedDeviceCodec.decode(ByteArray(TrustedDeviceCodec.MAX_BYTES + 1)) }
        val records = (1..33).map { val i = R1TestIdentity(); TrustedDevice(i.fingerprint(), "P$it", i.publicKey()) }
        assertFails { TrustedDeviceCodec.encode(records) }
    }
    @Test fun receiveWindowHasStrict60SecondDeadlineWithoutSliding() {
        var now = 0L; val gate = TrustedReceiveWindow { now }; val w = gate.open("a".repeat(64))
        now = 59_999; assertTrue(gate.isLive(w)); val bound = assertNotNull(gate.bind("session"))
        now = 60_000; assertFalse(gate.permits(bound, "session", w.target)); assertNull(gate.bind("session"))
    }
    @Test fun receiveWindowBindsOneSessionAndAllowsOneConsumption() {
        val gate = TrustedReceiveWindow { 0 }; gate.open("a".repeat(64)); val w = assertNotNull(gate.bind("one"))
        assertNull(gate.bind("two")); assertFalse(gate.permits(w, "two", w.target))
        assertTrue(gate.consume(w, "one", w.target)); assertFalse(gate.consume(w, "one", w.target))
    }
    @Test fun wrongFingerprintCannotUseReceiveWindow() {
        val gate = TrustedReceiveWindow { 0 }; gate.open("a".repeat(64)); val w = assertNotNull(gate.bind("s"))
        assertFalse(gate.permits(w, "s", "b".repeat(64)))
        assertFalse(gate.permits(w.copy(target = "b".repeat(64)), "s", "b".repeat(64)))
    }
    @Test fun callerCannotExtendWindowOrUseUnboundSnapshot() {
        var now = 0L; val gate = TrustedReceiveWindow { now }; val original = gate.open("a".repeat(64))
        val bound = assertNotNull(gate.bind("s")); assertFalse(gate.permits(original, "s", original.target))
        now = 60_001; assertFalse(gate.permits(bound.copy(expiresAtMs = 999_999), "s", original.target))
    }
    @Test fun oldUiCallbackCannotCloseOrConsumeNewWindow() {
        val gate = TrustedReceiveWindow { 0 }; gate.open("a".repeat(64)); val old = assertNotNull(gate.bind("old"))
        val newer = gate.open("b".repeat(64)); assertFalse(gate.close(old.token)); assertFalse(gate.consume(old, "old", old.target))
        assertTrue(gate.isLive(newer)); assertTrue(gate.close(newer.token))
    }
    @Test fun backgroundRevocationCannotBeReopenedByClaimOrClockRollback() {
        var now = 100L; val gate = TrustedReceiveWindow { now }; val w = gate.open("a".repeat(64))
        now = 99; assertFalse(gate.isLive(w)); now = 101; assertFalse(gate.isLive(w))
        gate.close(w.token); assertNull(gate.bind("late")); assertFalse(gate.isLive(w))
    }
    @Test fun closedTrustedSessionStillRequiresItsWindowUntilExplicitReset() = runTest {
        val trust = TrustedPairing(R1TestIdentity(), TrustedDeviceRegistry(R1TestStorage()), TrustedReceiveWindow { 0 })
        val w = trust.armReceive("a".repeat(64)); trust.receiveWindow.close()
        assertEquals(w.token, trust.requiredWindow?.token)
        trust.clearReceiveSession(); assertNull(trust.requiredWindow)
    }
}
