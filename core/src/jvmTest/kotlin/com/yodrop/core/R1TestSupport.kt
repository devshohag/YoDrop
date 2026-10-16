package com.yodrop.core

import com.yodrop.core.trust.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

internal class R1TestIdentity : LocalDeviceIdentity {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override suspend fun publicKey() = keys.public.encoded.copyOf()
    override suspend fun sign(transcript: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private); update(transcript); sign()
    }
    suspend fun fingerprint() = IdentityKeys.fingerprint(publicKey())
}
internal class R1TestStorage : TrustedDeviceStorage {
    var bytes = TrustedDeviceCodec.encode(emptyList())
    var failRead = false
    var failWrite = false
    var writes = 0
    override suspend fun read(): List<TrustedDevice> {
        check(!failRead); return TrustedDeviceCodec.decode(bytes)
    }
    override suspend fun write(devices: List<TrustedDevice>) {
        check(!failWrite); bytes = TrustedDeviceCodec.encode(devices); writes++
    }
    override suspend fun reset() { failRead = false; failWrite = false; bytes = TrustedDeviceCodec.encode(emptyList()) }
}
