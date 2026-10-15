package com.yodrop.core.crypto

import kotlin.test.Test

class Step3X25519Test {
    @Test fun rfcVector1() = X25519Checks.vector1()
    @Test fun rfcVector2() = X25519Checks.vector2()
    @Test fun rfcAliceBob() = X25519Checks.aliceBob()
    @Test fun masksHighBit() = X25519Checks.highBitMasking()
    @Test fun rejectsLowOrder() = X25519Checks.rejectsLowOrder()
    @Test fun rejectsWrongLengths() = X25519Checks.rejectsWrongLengths()
    @Test fun generatedKeysAgree() = X25519Checks.generatedKeysAgree()
}