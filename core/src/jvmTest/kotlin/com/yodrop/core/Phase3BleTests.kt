package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase3BleTests {

    @Test
    fun testBlePacketFragmentationAndReassembly() {
        // Large 1200-byte payload simulating multiple files in OFFER
        val largeData = ByteArray(1200) { (it % 250).toByte() }
        val mtu = 100 // 100-byte small MTU constraint

        val fragments = BleFragmenter.fragment(msgId = 42, data = largeData, mtu = mtu)
        assertTrue(fragments.size > 10, "Should split into multiple fragments")

        val reassembler = BleFragmenter.Reassembler()
        var assembledPayload: ByteArray? = null

        for (packet in fragments) {
            val result = reassembler.onPacketReceived(packet)
            if (result != null) {
                assembledPayload = result
            }
        }

        assertNotNull(assembledPayload, "Payload must be completely assembled")
        assertTrue(largeData.contentEquals(assembledPayload), "Reassembled bytes must match original exactly")
    }

    @Test
    fun testBleHandshakeControlSerialization() {
        val aliceKey = CryptoEngine.generateX25519KeyPair()
        val sid = ByteArray(16) { 0x07 }

        val offer = OfferMessage(
            sessionId = sid,
            senderPubKey = aliceKey.publicKey,
            fileCount = 3,
            totalBytes = 1024 * 1024 * 15,
            previewName = "vacation_photos.zip"
        )

        val encoded = ProtocolCodec.cbor.encodeToByteArray(OfferMessage.serializer(), offer)
        val decoded = ProtocolCodec.cbor.decodeFromByteArray(OfferMessage.serializer(), encoded)

        assertEquals("vacation_photos.zip", decoded.previewName)
        assertEquals(3, decoded.fileCount)
        assertTrue(decoded.senderPubKey.contentEquals(aliceKey.publicKey))
    }
}
