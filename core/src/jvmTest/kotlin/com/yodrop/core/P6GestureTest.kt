package com.yodrop.core

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.gesture.*
import com.yodrop.core.platform.MemoryFileSource
import com.yodrop.core.protocol.*
import com.yodrop.core.session.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.math.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class P6GestureTest {
    // Synthetic anatomical coordinates test geometry, not MediaPipe accuracy on a phone.
    private fun hand(fist: Boolean): List<HandPoint> {
        val p = MutableList(21) { HandPoint(0f, 0f, 0f) }
        fun point(i: Int, x: Float, y: Float, z: Float = 0f) { p[i] = HandPoint(x * .06f, y * .06f, z * .06f) }
        point(1, -.4f, .25f); point(2, -.8f, .5f); point(3, -1.1f, .8f)
        point(4, if (fist) -.3f else -1.4f, if (fist) .8f else 1.1f)
        for ((column, mcp) in listOf(5, 9, 13, 17).withIndex()) {
            val x = -.5f + column / 3f
            point(mcp, x, 1f); point(mcp + 1, x, 1.6f)
            point(mcp + 2, x, if (fist) 1.3f else 2.1f, if (fist) -.2f else 0f)
            point(mcp + 3, x, if (fist) 1f else 2.5f, if (fist) -.3f else 0f)
        }
        return p
    }
    private class Feed(mode: GestureMode = GestureMode.SEND, token: String = "a") {
        val gate = GestureGate().apply { updateContext(GestureContext(mode, token)) }
        var time = 0L
        fun frame(pose: HandPose, delta: Long = 80): GestureCommand? {
            val command = gate.observe(pose, time); time += delta; return command
        }
        fun hold(pose: HandPose): List<GestureCommand> = (1..4).mapNotNull { frame(pose) }
        fun grab(): List<GestureCommand> = hold(HandPose.PALM) + hold(HandPose.FIST)
        fun release(): List<GestureCommand> = hold(HandPose.FIST) + hold(HandPose.PALM)
    }

    @Test fun completeOpenHandAndClosedHandAreClassified() {
        assertEquals(HandPose.PALM, HandPoseClassifier.classify(hand(false)))
        assertEquals(HandPose.FIST, HandPoseClassifier.classify(hand(true)))
    }
    @Test fun worldGeometryWorksForEitherHandAndThreeDimensionalRotation() {
        for (fist in listOf(false, true)) for (mirror in listOf(-1f, 1f)) for (theta in listOf(0.0, .7, 2.4)) {
            val transformed = hand(fist).map { p ->
                val x = p.x * mirror; val y = p.y * cos(theta) - p.z * sin(theta)
                val z = p.y * sin(theta) + p.z * cos(theta)
                HandPoint((x * cos(theta) - y * sin(theta)).toFloat() + .12f,
                    (x * sin(theta) + y * cos(theta)).toFloat() - .08f, z.toFloat() + .03f)
            }
            assertEquals(if (fist) HandPose.FIST else HandPose.PALM, HandPoseClassifier.classify(transformed))
        }
    }
    @Test fun reasonableHandSizesHaveSamePose() {
        for (fist in listOf(false, true)) for (scale in listOf(.6f, 1f, 1.6f))
            assertEquals(if (fist) HandPose.FIST else HandPose.PALM,
                HandPoseClassifier.classify(hand(fist).map { HandPoint(it.x * scale, it.y * scale, it.z * scale) }))
    }
    @Test fun incompleteNonfiniteAndDegenerateLandmarksAreRejected() {
        for (bad in listOf(emptyList(), hand(false).drop(1), hand(false) + HandPoint(0f, 0f, 0f),
            List(21) { HandPoint(0f, 0f, 0f) }, hand(false).map { HandPoint(it.x * 100, it.y * 100, it.z) }))
            assertEquals(HandPose.NONE, HandPoseClassifier.classify(bad))
        for (number in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val bad = hand(false).toMutableList(); bad[8] = HandPoint(number, 0f, 0f)
            assertEquals(HandPose.NONE, HandPoseClassifier.classify(bad))
        }
    }
    @Test fun pointingVSignAndThumbsUpDoNotBecomeTransferPoses() {
        val open = hand(false); val closed = hand(true)
        for (extended in listOf(listOf(5), listOf(5, 9))) {
            val mixed = closed.toMutableList()
            for (mcp in extended) for (i in mcp..mcp + 3) mixed[i] = open[i]
            assertEquals(HandPose.UNKNOWN, HandPoseClassifier.classify(mixed))
        }
        val thumbsUp = closed.toMutableList(); thumbsUp[4] = HandPoint(-.09f, .14f, 0f)
        assertEquals(HandPose.UNKNOWN, HandPoseClassifier.classify(thumbsUp))
    }
    @Test fun collapsedFingerJointsNeverTrigger() {
        val bad = hand(false).toMutableList(); bad[7] = bad[6]
        assertEquals(HandPose.UNKNOWN, HandPoseClassifier.classify(bad))
    }
    @Test fun senderNeedsStablePalmThenStableFist() {
        val f = Feed(); assertTrue(f.hold(HandPose.FIST).isEmpty())
        assertTrue(f.hold(HandPose.PALM).isEmpty())
        val command = f.hold(HandPose.FIST).single()
        assertEquals(GestureAction.GRAB, command.action); assertEquals(GestureContext(GestureMode.SEND, "a"), command.context)
    }
    @Test fun receiverNeedsStableFistThenStablePalm() {
        val f = Feed(GestureMode.CLAIM)
        assertTrue(f.hold(HandPose.PALM).isEmpty())
        assertEquals(GestureAction.RELEASE, f.release().single().action)
    }
    @Test fun briefJitterDoesNotMakeStableEdges() {
        val f = Feed(); f.hold(HandPose.PALM)
        repeat(30) { assertNull(f.frame(if (it % 2 == 0) HandPose.FIST else HandPose.PALM)) }
        assertTrue(f.hold(HandPose.PALM).isEmpty())
        assertEquals(1, f.hold(HandPose.FIST).size)
    }
    @Test fun minimumFrameCountAndElapsedTimeAreBothRequired() {
        val f = Feed(); repeat(50) { assertNull(f.frame(HandPose.PALM, 1)) }
        repeat(50) { assertNull(f.frame(HandPose.FIST, 1)) }
        f.time += 200; assertNull(f.frame(HandPose.FIST)) // Still no stable preceding palm.
        val slow = Feed(); slow.frame(HandPose.PALM, 230); slow.frame(HandPose.PALM)
        assertTrue(slow.hold(HandPose.FIST).isEmpty()) // Only two palm frames cannot arm.
    }
    @Test fun missingHandOrMultipleHandsClearTheSequence() {
        val f = Feed(); f.hold(HandPose.PALM); assertNull(f.frame(HandPose.NONE))
        assertTrue(f.hold(HandPose.FIST).isEmpty())
        assertEquals(1, f.grab().size)
    }
    @Test fun shortIntermediatePoseAllowsNaturalFingerMovement() {
        val f = Feed(); f.hold(HandPose.PALM)
        repeat(2) { assertNull(f.frame(HandPose.UNKNOWN)) }
        assertEquals(1, f.hold(HandPose.FIST).size)
    }
    @Test fun prolongedUnknownPoseDisarmsEvenIfNextKnownFrameArrivesAfterDeadline() {
        val f = Feed(); f.hold(HandPose.PALM)
        repeat(5) { assertNull(f.frame(HandPose.UNKNOWN)) }
        assertTrue(f.hold(HandPose.FIST).isEmpty())
    }
    @Test fun cameraFrameStallRequiresNewStartingPose() {
        val f = Feed(); f.hold(HandPose.PALM); f.time += 251
        assertTrue(f.hold(HandPose.FIST).isEmpty())
        assertEquals(1, f.grab().size)
    }
    @Test fun oneTransferCommandPerTokenSurvivesHandLossAndBadTimestamps() {
        val f = Feed(); assertEquals(1, f.grab().size)
        f.frame(HandPose.NONE); f.gate.observe(HandPose.PALM, f.time - 100)
        assertTrue(f.grab().isEmpty()); f.time += 10_000; assertTrue(f.grab().isEmpty())
    }
    @Test fun duplicateOrReorderedFramesCannotCompleteSequence() {
        val f = Feed(); f.hold(HandPose.PALM)
        assertNull(f.gate.observe(HandPose.FIST, f.time - 80))
        assertTrue(f.hold(HandPose.FIST).isEmpty())
    }
    @Test fun newSessionOrRoleRequiresFreshSequenceAndCarriesNewToken() {
        val f = Feed(); f.hold(HandPose.PALM)
        f.gate.updateContext(GestureContext(GestureMode.SEND, "b"))
        assertTrue(f.hold(HandPose.FIST).isEmpty())
        assertEquals("b", f.grab().single().context.token)
        f.gate.updateContext(GestureContext(GestureMode.CLAIM, "offer"))
        assertTrue(f.hold(HandPose.PALM).isEmpty())
        assertEquals("offer", f.release().single().context.token)
    }
    @Test fun recomposingSameContextDoesNotRearmConsumedCommand() {
        val f = Feed(); f.grab(); f.gate.updateContext(GestureContext(GestureMode.SEND, "a"))
        assertTrue(f.grab().isEmpty())
    }
    @Test fun offAndOfferScanningNeverEmitActions() {
        for (mode in listOf(GestureMode.OFF, GestureMode.OBSERVE)) {
            val f = Feed(mode); repeat(4) { assertTrue(f.grab().isEmpty()); assertTrue(f.release().isEmpty()) }
        }
    }
    @Test fun senderAndReceiverCannotEmitTheOtherRolesAction() {
        assertTrue(Feed(GestureMode.SEND).release().isEmpty())
        assertTrue(Feed(GestureMode.CLAIM).grab().isEmpty())
    }
    @Test fun practiceAllowsBothDirectionsWithCooldownAndNoTransferLatch() {
        val f = Feed(GestureMode.PRACTICE); assertEquals(GestureAction.GRAB, f.grab().single().action)
        assertTrue(f.hold(HandPose.PALM).isEmpty()) // Cooldown, not a new command.
        repeat(10) { assertNull(f.frame(HandPose.PALM)) }
        assertEquals(GestureAction.GRAB, f.hold(HandPose.FIST).single().action)
        repeat(10) { f.frame(HandPose.FIST) }
        assertEquals(GestureAction.RELEASE, f.hold(HandPose.PALM).single().action)
    }
    @Test fun pauseResetCannotJoinPosesFromTwoCameraRuns() {
        val f = Feed(); f.hold(HandPose.PALM); f.gate.reset()
        assertTrue(f.hold(HandPose.FIST).isEmpty()); assertEquals(1, f.grab().size)
    }
    @Test fun invalidTimingParametersAreRejected() {
        assertFailsWith<IllegalArgumentException> { GestureGate(stableMs = 0) }
        assertFailsWith<IllegalArgumentException> { GestureGate(minimumFrames = 1) }
        assertFailsWith<IllegalArgumentException> { GestureGate(maximumGapMs = 0) }
        assertFailsWith<IllegalArgumentException> { GestureGate(transitionMs = 0) }
        assertFailsWith<IllegalArgumentException> { GestureGate(cooldownMs = 0) }
        assertNull(Feed().gate.observe(HandPose.PALM, -1))
    }

    @Test fun gesturesDriveRealHandshakeButNeverReplaceExplicitAccept() = runTest {
        val sender = SenderStateMachine(backgroundScope); val receiver = ReceiverStateMachine(backgroundScope)
        val bytes = byteArrayOf(1, 2, 3)
        val file = FileMeta("p6", "private.txt", 3, "text/plain", CryptoEngine.sha256(bytes))
        val grab = Feed().grab().single(); assertEquals(GestureAction.GRAB, grab.action)
        assertTrue(sender.onGrabbed(listOf(file), mapOf(file.id to MemoryFileSource(file.id, bytes))))
        val offer = assertIs<SenderState.Grabbed>(sender.state.value).offer
        receiver.startScanning()
        val messages = ArrayDeque<BleControlMessage>()
        val controller = BleHandshakeController(SenderStateMachine(backgroundScope), receiver,
            HelloMessage("Receiver", Platform.ANDROID, DeviceCaps.CAP_LAN, ByteArray(16))) { _, message -> messages.addLast(message) }
        controller.onMessage(BlePeerMessage("sender", offer))
        assertEquals(GestureAction.RELEASE, Feed(GestureMode.CLAIM).release().single().action)
        assertTrue(controller.claim()); assertFalse(controller.claim())
        assertTrue(sender.onClaimReceived(assertIs<ClaimMessage>(messages.removeFirst())))
        assertIs<ReceiverState.Claimed>(receiver.state.value)
        controller.onMessage(BlePeerMessage("sender", assertNotNull(sender.createEncryptedOfferDetails())))
        assertIs<ReceiverState.AcceptPrompt>(receiver.state.value)
        assertIs<SenderState.AwaitAccept>(sender.state.value)
        repeat(3) { Feed(GestureMode.CLAIM).release(); assertFalse(controller.claim()) }
        assertTrue(messages.isEmpty())
        assertTrue(controller.accept())
        assertTrue(assertIs<DecisionMessage>(messages.removeFirst()).accepted)
        assertIs<ReceiverState.Linking>(receiver.state.value)
    }
    @Test fun staleGestureCannotClaimAfterTenSecondOfferWindow() = runTest {
        val receiver = ReceiverStateMachine(backgroundScope)
        receiver.startScanning()
        var writes = 0
        val controller = BleHandshakeController(SenderStateMachine(backgroundScope), receiver,
            HelloMessage("Receiver", Platform.ANDROID, DeviceCaps.CAP_LAN, ByteArray(16))) { _, _ -> writes++ }
        controller.onMessage(BlePeerMessage("sender", OfferMessage(ByteArray(16), ByteArray(32) { 1 }, 1, 1, "Sender")))
        assertIs<ReceiverState.OfferSeen>(receiver.state.value)
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertFalse(receiver.state.value is ReceiverState.OfferSeen)
        assertFalse(controller.claim()); assertEquals(0, writes)
    }
}
