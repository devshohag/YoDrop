package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.FileMeta
import com.yodrop.core.session.SenderState
import com.yodrop.core.session.SenderStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

class T1RandomSessionIdTest {

    @Test
    fun testGenerate1000DistinctSessionIds() {
        val count = 1000
        val set = mutableSetOf<String>()
        val dummyFile = FileMeta("id", "name", 100L, "text/plain", ByteArray(32))
        val scope = CoroutineScope(Dispatchers.Unconfined)

        for (i in 0 until count) {
            val sm = SenderStateMachine(scope)
            sm.onGrabbed(listOf(dummyFile), emptyMap())
            val adv = sm.state.value as SenderState.Advertising
            val sidHex = adv.offer.sessionId.joinToString("") { "%02x".format(it) }
            assertEquals(16, adv.offer.sessionId.size, "SessionId must be exactly 16 bytes")
            set.add(sidHex)
        }

        assertEquals(count, set.size, "All 1000 generated session IDs must be distinct")
    }
}
