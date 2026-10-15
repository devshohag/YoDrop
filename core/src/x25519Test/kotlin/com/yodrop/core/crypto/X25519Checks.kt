package com.yodrop.core.crypto

// Published test data: RFC 7748 sections 5.2 and 6.1.
// https://www.rfc-editor.org/rfc/rfc7748.txt
internal object X25519Checks {
    private fun hex(value: String): ByteArray {
        require(value.length == 64)
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun expect(expected: ByteArray, actual: ByteArray) {
        check(expected.contentEquals(actual)) {
            "X25519 result does not match expected bytes"
        }
    }

    private fun rejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        check(rejected) { "Invalid key was accepted" }
    }

    fun vector1() {
        expect(
            hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            CryptoEngine.computeSharedSecret(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
            )
        )
    }

    fun vector2() {
        expect(
            hex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"),
            CryptoEngine.computeSharedSecret(
                hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
            )
        )
    }

    fun aliceBob() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        val base = ByteArray(32).also { it[0] = 9 }

        expect(alicePublic, CryptoEngine.computeSharedSecret(alice, base))
        expect(bobPublic, CryptoEngine.computeSharedSecret(bob, base))
        expect(expected, CryptoEngine.computeSharedSecret(alice, bobPublic))
        expect(expected, CryptoEngine.computeSharedSecret(bob, alicePublic))
    }

    fun highBitMasking() {
        val privateKey = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val original = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val highBit = original.copyOf()
        highBit[31] = (highBit[31].toInt() or 0x80).toByte()
        val before = highBit.copyOf()

        expect(
            CryptoEngine.computeSharedSecret(privateKey, original),
            CryptoEngine.computeSharedSecret(privateKey, highBit)
        )
        expect(before, highBit)
    }

    fun rejectsLowOrder() {
        val privateKey = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val points = listOf(
            ByteArray(32),
            ByteArray(32).also { it[0] = 1 },
            hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            hex("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")
        )
        for (point in points) {
            rejected {
                CryptoEngine.computeSharedSecret(privateKey, point)
            }
        }
    }

    fun rejectsWrongLengths() {
        val key = ByteArray(32).also { it[0] = 9 }
        for (length in listOf(0, 31, 33)) {
            rejected {
                CryptoEngine.computeSharedSecret(ByteArray(length), key)
            }
            rejected {
                CryptoEngine.computeSharedSecret(key, ByteArray(length))
            }
        }
    }

    fun generatedKeysAgree() {
        val alice = CryptoEngine.generateX25519KeyPair()
        val bob = CryptoEngine.generateX25519KeyPair()
        check(alice.privateKey.size == 32 && alice.publicKey.size == 32)
        check(bob.privateKey.size == 32 && bob.publicKey.size == 32)
        expect(
            CryptoEngine.computeSharedSecret(alice.privateKey, bob.publicKey),
            CryptoEngine.computeSharedSecret(bob.privateKey, alice.publicKey)
        )
    }

    fun runAll() {
        vector1()
        vector2()
        aliceBob()
        highBitMasking()
        rejectsLowOrder()
        rejectsWrongLengths()
        generatedKeysAgree()
    }
}