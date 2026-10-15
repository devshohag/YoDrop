package com.yodrop.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SanityTest {
    @Test fun protocolVersion() = assertEquals(1, YoDrop.PROTOCOL_VERSION)
}
