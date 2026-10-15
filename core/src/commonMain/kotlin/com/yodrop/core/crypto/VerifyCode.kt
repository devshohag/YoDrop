package com.yodrop.core.crypto

internal object VerifyCode {
    // First four digits of the hash interpreted as an unsigned decimal integer.
    fun fromHash(hash: ByteArray): String {
        require(hash.size == 32) { "Expected a SHA-256 digest" }
        val digits = mutableListOf(0)

        for (byte in hash) {
            var carry = byte.toInt() and 0xff
            for (index in digits.indices.reversed()) {
                val value = digits[index] * 256 + carry
                digits[index] = value % 10
                carry = value / 10
            }
            while (carry > 0) {
                digits.add(0, carry % 10)
                carry /= 10
            }
        }
        return digits.joinToString("").take(4).padStart(4, '0')
    }
}