package com.yodrop.core

import com.yodrop.core.protocol.*
import com.yodrop.core.transport.GattOperationKey
import com.yodrop.core.transport.GattOperationQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class Step13GattTest {
    private val a = GattOperationKey("peer-a", "write")
    private val b = GattOperationKey("peer-b", "notify")
    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }
    private fun assemble(packets: List<ByteArray>): ByteArray? {
        val reassembler = BleFragmenter.Reassembler()
        var result: ByteArray? = null
        for (packet in packets) reassembler.onPacketReceived(packet)?.let { result = it }
        return result
    }

    @Test
    fun queueStartsOnlyOneOperationUntilItsExactCallback() = runTest {
        val queue = GattOperationQueue()
        val started = mutableListOf<String>()
        val first = async { queue.execute(a) { started.add("a"); true } }
        val second = async { queue.execute(b) { started.add("b"); true } }
        runCurrent()
        assertEquals(listOf("a"), started)
        assertFalse(queue.complete(b, 0))
        assertFalse(queue.complete(a.copy(operation = "descriptor"), 0))
        runCurrent()
        assertEquals(listOf("a"), started)
        assertTrue(queue.complete(a, 0))
        assertFalse(queue.complete(a, 0))
        runCurrent()
        assertEquals(listOf("a", "b"), started)
        assertEquals(0, first.await())
        assertTrue(queue.complete(b, 5))
        assertEquals(5, second.await())
    }

    @Test
    fun callbackArrivingDuringStartIsNotLost() = runTest {
        val queue = GattOperationQueue()
        val operation = async {
            queue.execute(a) {
                launch(start = CoroutineStart.UNDISPATCHED) { queue.complete(a, 0) }
                true
            }
        }
        assertEquals(0, operation.await())
    }

    @Test
    fun immediateStartRejectionDoesNotWaitForCallback() = runTest {
        val queue = GattOperationQueue()
        assertNull(queue.execute(a) { false })
        assertFalse(queue.complete(a, 0))
        val next = async { queue.execute(b) { true } }
        runCurrent()
        assertTrue(queue.complete(b, 0))
        assertEquals(0, next.await())
    }

    @Test
    fun timeoutPoisonsQueueAndNeverStartsWaitingPacket() = runTest {
        val queue = GattOperationQueue(timeoutMs = 100)
        var secondStarted = false
        val first = async { runCatching { queue.execute(a) { true } } }
        val second = async { runCatching { queue.execute(b) { secondStarted = true; true } } }
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertIs<IllegalStateException>(first.await().exceptionOrNull())
        assertTrue(second.await().isFailure)
        assertFalse(secondStarted)
        assertFalse(queue.complete(a, 0))
    }

    @Test
    fun disconnectUnblocksActiveAndQueuedOperations() = runTest {
        val queue = GattOperationQueue()
        var secondStarted = false
        val first = async { runCatching { queue.execute(a) { true } } }
        val second = async { runCatching { queue.execute(b) { secondStarted = true; true } } }
        runCurrent()
        queue.close(IllegalStateException("disconnected"))
        assertTrue(first.await().isFailure)
        assertTrue(second.await().isFailure)
        assertFalse(secondStarted)
        assertFalse(queue.complete(a, 0))
    }

    @Test
    fun cancellingInFlightOperationCannotAdvanceOldQueue() = runTest {
        val queue = GattOperationQueue()
        val first = launch { queue.execute(a) { true } }
        runCurrent(); first.cancelAndJoin()
        var started = false
        assertFails { queue.execute(b) { started = true; true } }
        assertFalse(started)
        assertFalse(queue.complete(a, 0))
    }

    @Test
    fun cancellingWaitingCallerLeavesInFlightOperationIntact() = runTest {
        val queue = GattOperationQueue()
        val first = async { queue.execute(a) { true } }
        val waiting = launch { queue.execute(b) { error("must not start") } }
        runCurrent(); waiting.cancelAndJoin()
        assertTrue(queue.complete(a, 0))
        assertEquals(0, first.await())
    }

    @Test
    fun retiredQueueCallbacksCannotCompleteReplacement() = runTest {
        val old = GattOperationQueue()
        old.close()
        val replacement = GattOperationQueue()
        val pending = async { replacement.execute(a) { true } }
        runCurrent()
        assertFalse(old.complete(a, 0))
        assertFalse(pending.isCompleted)
        assertTrue(replacement.complete(a, 0))
        assertEquals(0, pending.await())
    }

    @Test
    fun oneKiBRoundTripsAtDefault247And517Mtu() {
        val bytes = payload(1024)
        for (mtu in listOf(23, 247, 517)) {
            val capacity = BleFragmenter.packetSizeForMtu(mtu)
            val packets = BleFragmenter.fragment(65535, bytes, capacity)
            assertTrue(packets.all { it.size <= mtu - 3 && it.size <= 512 })
            assertEquals(if (mtu == 23) 64 else if (mtu == 247) 5 else 3, packets.size)
            assertContentEquals(bytes, assemble(packets))
        }
        assertFails { BleFragmenter.packetSizeForMtu(22) }
        assertFails { BleFragmenter.packetSizeForMtu(518) }
    }

    @Test
    fun fragmentLimitAndMessageIdBoundsFailBeforeWireTransmission() {
        assertEquals(255, BleFragmenter.fragment(0, payload(255 * 16), 20).size)
        assertFails { BleFragmenter.fragment(0, payload(255 * 16 + 1), 20) }
        assertFails { BleFragmenter.fragment(-1, payload(1), 20) }
        assertFails { BleFragmenter.fragment(65536, payload(1), 20) }
        assertFails { BleFragmenter.fragment(0, payload(1), 4) }
        assertFails { BleFragmenter.fragment(0, payload(1), 513) }
        assertContentEquals(byteArrayOf(), assemble(BleFragmenter.fragment(0, byteArrayOf(), 20)))
    }

    @Test
    fun zeroTotalOutOfRangeIndicesAndChangingTotalsAreRejected() {
        val data = payload(40)
        val packets = BleFragmenter.fragment(7, data, 20)
        for (malformed in listOf(byteArrayOf(0, 7, 0, 0), byteArrayOf(0, 7, 3, 3),
            byteArrayOf(0, 7, -1, 3), byteArrayOf(0, 7, 0))) {
            val assembler = BleFragmenter.Reassembler()
            assertNull(assembler.onPacketReceived(malformed))
            var result: ByteArray? = null
            for (packet in packets) assembler.onPacketReceived(packet)?.let { result = it }
            assertContentEquals(data, result)
        }
        val assembler = BleFragmenter.Reassembler()
        assembler.onPacketReceived(packets[0])
        assertNull(assembler.onPacketReceived(packets[1].copyOf().also { it[3] = 4 }))
        assertNull(assembler.onPacketReceived(packets.last()))
    }

    @Test
    fun outOfOrderFragmentsAndIdenticalDuplicatesPreserveIntegrity() {
        val data = payload(60)
        val packets = BleFragmenter.fragment(9, data, 20)
        val assembler = BleFragmenter.Reassembler()
        assertNull(assembler.onPacketReceived(packets[2]))
        assertNull(assembler.onPacketReceived(packets[2]))
        assertNull(assembler.onPacketReceived(packets[0]))
        assertNull(assembler.onPacketReceived(packets[3]))
        assertContentEquals(data, assembler.onPacketReceived(packets[1]))
    }

    @Test
    fun conflictingDuplicateAndOversizedAssemblyDiscardPartialData() {
        val packets = BleFragmenter.fragment(2, payload(40), 20)
        val conflict = BleFragmenter.Reassembler()
        conflict.onPacketReceived(packets[0])
        assertNull(conflict.onPacketReceived(packets[0].copyOf().also { it[4] = 99 }))
        assertNull(conflict.onPacketReceived(packets[1]))
        assertNull(conflict.onPacketReceived(packets[2]))
        val bounded = BleFragmenter.Reassembler(maxMessageBytes = 20)
        assertNull(bounded.onPacketReceived(packets[0]))
        assertNull(bounded.onPacketReceived(packets[1]))
        assertNull(bounded.onPacketReceived(packets[2]))
        assertContentEquals(byteArrayOf(8), bounded.onPacketReceived(byteArrayOf(0, 3, 0, 1, 8)))
    }

    @Test
    fun partialMessageExpiresEvenIfFragmentsKeepArrivingSlowly() {
        var now = 0L
        val assembler = BleFragmenter.Reassembler(timeoutMs = 100, nowMs = { now })
        val packets = BleFragmenter.fragment(5, payload(40), 20)
        assembler.onPacketReceived(packets[0])
        now = 90; assembler.onPacketReceived(packets[1])
        now = 100
        assertTrue(assembler.expireStale())
        assertNull(assembler.onPacketReceived(packets[2]))
        assertNull(assembler.onPacketReceived(packets[0]))
        assertContentEquals(payload(40), assembler.onPacketReceived(packets[1]))
    }

    @Test
    fun peersWithSameMessageIdAndInterleavedPacketsNeverMixData() {
        val links = BlePeerLinks()
        assertTrue(links.connect("a")); assertTrue(links.connect("b"))
        val dataA = ByteArray(80) { 1 }; val dataB = ByteArray(80) { 2 }
        val a = links.fragments("a", 1, dataA); val b = links.fragments("b", 1, dataB)
        var resultA: ByteArray? = null; var resultB: ByteArray? = null
        for (i in a.indices) {
            links.receive("a", a[i])?.let { resultA = it }
            links.receive("b", b[i])?.let { resultB = it }
        }
        assertContentEquals(dataA, resultA); assertContentEquals(dataB, resultB)
    }

    @Test
    fun mtuSubscriptionAndPartialStateResetOnDisconnect() {
        val links = BlePeerLinks(maxPeers = 1)
        assertTrue(links.connect("a")); assertFalse(links.connect("b"))
        assertEquals(23, links.mtu("a"))
        assertTrue(links.setMtu("a", 247)); assertFalse(links.setMtu("a", 0))
        assertTrue(links.subscribe("a", true)); assertTrue(links.subscribed("a"))
        val packets = links.fragments("a", 5, payload(600))
        links.receive("a", packets[0])
        links.disconnect("a")
        assertNull(links.receive("a", packets[1]))
        assertTrue(links.connect("a"))
        assertEquals(23, links.mtu("a")); assertFalse(links.subscribed("a"))
        assertTrue(links.setMtu("a", 247))
        assertNull(links.receive("a", packets[1]))
        assertNull(links.receive("a", packets[2]))
        assertContentEquals(payload(600), links.receive("a", packets[0]))
    }

    @Test
    fun peerPartialExpiryDoesNotDiscardOtherPeersProgress() {
        var now = 0L
        val links = BlePeerLinks(newReassembler = { BleFragmenter.Reassembler(timeoutMs = 100, nowMs = { now }) })
        links.connect("a"); links.connect("b")
        val a = links.fragments("a", 1, payload(24)); val b = links.fragments("b", 1, payload(24))
        links.receive("a", a[0]); now = 90; links.receive("b", b[0])
        now = 100; links.expireStale()
        assertNull(links.receive("a", a[1]))
        assertContentEquals(payload(24), links.receive("b", b[1]))
    }

    @Test
    fun discoveryPinsPeerAndSessionAndCopiesTheAdvertisement() {
        val target = BleDiscoveryTarget()
        val sid = ByteArray(16) { 7 }
        assertFalse(target.bindSession("a", ByteArray(15)))
        assertTrue(target.select("a") && target.bindSession("a", sid))
        sid[0] = 8
        assertTrue(target.matches("a", ByteArray(16) { 7 }))
        assertFalse(target.select("b"))
        assertFalse(target.bindSession("a", ByteArray(16) { 8 }))
        target.clear()
        assertTrue(target.select("b") && target.bindSession("b", ByteArray(16) { 8 }))
    }

    @Test
    fun debugProbeIsExactlyOneKiBInBothDirectionsAtBothMtus() {
        val request = GattDiagnosticProbe.request(ByteArray(16) { it.toByte() })
        val reply = GattDiagnosticProbe.reply(request)
        assertTrue(GattDiagnosticProbe.isProbe(request))
        for (message in listOf(request, reply)) {
            val bytes = BleMessageCodec.encode(message)
            assertEquals(1024, bytes.size)
            for (mtu in listOf(23, 247)) {
                assertContentEquals(bytes, assemble(BleFragmenter.fragment(12, bytes,
                    BleFragmenter.packetSizeForMtu(mtu))))
            }
        }
        assertEquals("Q", request.extra[GattDiagnosticProbe.MODE])
        assertEquals("R", reply.extra[GattDiagnosticProbe.MODE])
        assertEquals(request.extra["token"], reply.extra["token"])
        assertFails { GattDiagnosticProbe.reply(reply) }
    }
}
