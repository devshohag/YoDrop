package com.yodrop.android.trust

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.trust.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No private key serialization. Small authenticated metadata only, outside backup/Downloads. */
class AndroidTrustStorage(context: Context) : LocalDeviceIdentity, TrustedDeviceStorage {
    private val file = File(context.applicationContext.noBackupFilesDir, "trusted-pairing/peers.v1")
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()
    private val magic = "YTR1".encodeToByteArray()
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun hasRegistry() = file.exists() || File(file.path + ".bak").exists()
    private fun identityKey(store: KeyStore): ByteArray {
        if (!store.containsAlias(IDENTITY_ALIAS)) {
            check(!hasRegistry()) { "Identity key missing; reset trusted pairing" }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(IDENTITY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return checkNotNull(store.getCertificate(IDENTITY_ALIAS)).publicKey.encoded.also { IdentityKeys.validate(it) }
    }
    override suspend fun publicKey(): ByteArray = withContext(Dispatchers.IO) { mutex.withLock { identityKey(keystore()).copyOf() } }
    override suspend fun sign(transcript: ByteArray): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        val store = keystore(); identityKey(store)
        Signature.getInstance("SHA256withECDSA").run {
            initSign(store.getKey(IDENTITY_ALIAS, null) as PrivateKey); update(transcript); sign()
        }
    } }
    private fun wrappingKey(store: KeyStore): SecretKey {
        if (!store.containsAlias(STORE_ALIAS)) {
            check(!hasRegistry()) { "Wrapping key missing; reset trusted pairing" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(STORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        return store.getKey(STORE_ALIAS, null) as SecretKey
    }
    private fun aad(publicKey: ByteArray) = "YoDrop-trusted-devices-v1".encodeToByteArray() + CryptoEngine.sha256(publicKey)
    private fun readBounded(): ByteArray = atomic.openRead().use { input ->
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            if (result.size() + count > TrustedDeviceCodec.MAX_BYTES + 64) throw IOException("Trusted registry too large")
            result.write(buffer, 0, count)
        }
        result.toByteArray()
    }
    override suspend fun read(): List<TrustedDevice> = withContext(Dispatchers.IO) { mutex.withLock {
        val store = keystore(); val publicKey = identityKey(store)
        if (!hasRegistry()) return@withLock emptyList()
        val bytes = readBounded()
        require(bytes.size >= 33 && bytes.copyOfRange(0, 4).contentEquals(magic))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(store), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        cipher.updateAAD(aad(publicKey))
        val plaintext = cipher.doFinal(bytes.copyOfRange(16, bytes.size))
        try { TrustedDeviceCodec.decode(plaintext) } finally { plaintext.fill(0) }
    } }
    override suspend fun write(devices: List<TrustedDevice>) = withContext(Dispatchers.IO) { mutex.withLock {
        val plaintext = TrustedDeviceCodec.encode(devices)
        try {
            val store = keystore(); val publicKey = identityKey(store)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(store)); cipher.updateAAD(aad(publicKey))
            check(cipher.iv.size == 12)
            val encoded = magic + cipher.iv + cipher.doFinal(plaintext)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val output = atomic.startWrite()
            try { output.write(encoded); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
            // Framework finishWrite can log a rename/sync failure instead of throwing.
            check(readBounded().contentEquals(encoded)) { "Trusted registry commit unconfirmed" }
        } finally { plaintext.fill(0) }
    } }
    override suspend fun reset() = withContext(Dispatchers.IO) { mutex.withLock {
        atomic.delete()
        check(!hasRegistry()) { "Could not clear trusted registry" }
        val store = keystore()
        store.deleteEntry(IDENTITY_ALIAS); store.deleteEntry(STORE_ALIAS)
        Unit
    } }
    private companion object {
        const val IDENTITY_ALIAS = "YoDrop.DeviceIdentity.P256.v1"
        const val STORE_ALIAS = "YoDrop.TrustedRegistry.AES.v1"
    }
}
