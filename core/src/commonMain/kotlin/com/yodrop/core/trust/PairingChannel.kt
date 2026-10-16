package com.yodrop.core.trust

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.protocol.EncryptedPairingMessage
import com.yodrop.core.protocol.PairingRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

@Serializable
internal sealed interface PairingControl {
    @Serializable data class Identity(val publicKey: ByteArray, val signature: ByteArray) : PairingControl {
        override fun toString() = "Identity(publicKey=<redacted>, signature=<redacted>)"
    }
    @Serializable data class Consent(val accepted: Boolean, val reason: String = "") : PairingControl
}

/** Fresh ephemeral session keys per instance; no private transfer key leaves this internal channel. */
@OptIn(ExperimentalSerializationApi::class)
internal class PairingChannel(
    private val keys: SessionKeys,
    sessionId: ByteArray,
    senderEphemeralKey: ByteArray,
    receiverEphemeralKey: ByteArray,
    val role: PairingRole,
    private var sendCounter: Long = 0
) {
    private val binding = sessionId.copyOf() + senderEphemeralKey.copyOf() + receiverEphemeralKey.copyOf()
    val sessionId = sessionId.copyOf()
    val token = CryptoEngine.sha256(binding).trustHex()
    private var receivedCounter = -1L
    private val cbor = Cbor { ignoreUnknownKeys = false }
    init { require(sessionId.size == 16 && senderEphemeralKey.size == 32 && receiverEphemeralKey.size == 32) }
    private fun roleByte(role: PairingRole) = byteArrayOf(if (role == PairingRole.SENDER) 0x53 else 0x52)
    private fun key(role: PairingRole): ByteArray = CryptoEngine.computeHmacSha256(
        if (role == PairingRole.SENDER) keys.senderToReceiverKey else keys.receiverToSenderKey,
        "YoDrop-Pairing-Control-v1/".encodeToByteArray() + roleByte(role) + byteArrayOf(1))
    private fun nonce(counter: Long): ByteArray {
        require(counter in 0..0xffff_ffffL) { "Pairing nonce counter exhausted" }
        return "PAIRCTL1".encodeToByteArray() + byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())
    }
    private fun aad(role: PairingRole) = "YoDrop-Pairing-v1".encodeToByteArray() + binding + roleByte(role)
    fun identityTranscript(publicKey: ByteArray, signerRole: PairingRole = role): ByteArray {
        IdentityKeys.validate(publicKey)
        return "YoDrop-Identity-P256-v1".encodeToByteArray() + binding + roleByte(signerRole) + publicKey
    }
    fun trustCode(localPublicKey: ByteArray, peerPublicKey: ByteArray): String {
        IdentityKeys.validate(localPublicKey); IdentityKeys.validate(peerPublicKey)
        val ordered = if (role == PairingRole.SENDER) localPublicKey + peerPublicKey else peerPublicKey + localPublicKey
        return CryptoEngine.sha256("YoDrop-Trust-Code-v1".encodeToByteArray() + binding + ordered)
            .trustHex().take(24).uppercase().chunked(4).joinToString(" ")
    }
    fun verifies(proof: PairingControl.Identity): Boolean = IdentitySignatures.verify(
        proof.publicKey, identityTranscript(proof.publicKey, opposite()), proof.signature)
    private fun opposite() = if (role == PairingRole.SENDER) PairingRole.RECEIVER else PairingRole.SENDER
    fun seal(body: PairingControl): EncryptedPairingMessage {
        validate(body)
        require(sendCounter in 0..0xffff_ffffL) { "Pairing nonce counter exhausted" }
        val counter = sendCounter++ // Consume before encryption, including encryption failure.
        val nonce = nonce(counter)
        val plaintext = cbor.encodeToByteArray(PairingControl.serializer(), body)
        require(plaintext.size <= 2048)
        val key = key(role)
        return try { EncryptedPairingMessage(role, counter, CryptoEngine.encryptAesGcm(key, nonce, plaintext, aad(role))) }
        finally { key.fill(0); plaintext.fill(0) }
    }
    fun open(message: EncryptedPairingMessage): PairingControl {
        require(message.role == opposite() && message.counter > receivedCounter && message.ciphertext.size in 17..2064) { "Invalid/replayed pairing control" }
        val key = key(message.role)
        var plaintext: ByteArray? = null
        return try {
            plaintext = CryptoEngine.decryptAesGcm(key, nonce(message.counter), message.ciphertext, aad(message.role))
            require(plaintext.size <= 2048)
            cbor.decodeFromByteArray(PairingControl.serializer(), plaintext).also { validate(it); receivedCounter = message.counter }
        } catch (_: Exception) { throw IllegalArgumentException("Pairing authentication failed") }
        finally { key.fill(0); plaintext?.fill(0) }
    }
    private fun validate(body: PairingControl) {
        when (body) {
            is PairingControl.Identity -> { IdentityKeys.validate(body.publicKey); require(body.signature.size in 8..72) }
            is PairingControl.Consent -> require(body.reason.encodeToByteArray().size <= 512)
        }
    }
}
