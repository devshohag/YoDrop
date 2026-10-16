package com.yodrop.core.gesture

enum class GestureMode { OFF, OBSERVE, PRACTICE, SEND, CLAIM }
enum class GestureAction { GRAB, RELEASE }
data class GestureContext(val mode: GestureMode, val token: String = "")
data class GestureCommand(val context: GestureContext, val action: GestureAction)

/** Single analysis-thread owner. A new file/offer token or pause requires a fresh sequence. */
class GestureGate(
    private val stableMs: Long = 220,
    private val minimumFrames: Int = 4,
    private val maximumGapMs: Long = 250,
    private val transitionMs: Long = 450,
    private val cooldownMs: Long = 700
) {
    init { require(stableMs > 0 && minimumFrames > 1 && maximumGapMs > 0 && transitionMs > 0 && cooldownMs > 0) }
    private var context = GestureContext(GestureMode.OFF)
    private var candidate = HandPose.NONE
    private var stable = HandPose.NONE
    private var since = -1L
    private var last = -1L
    private var knownAt = -1L
    private var emittedAt = -1L
    private var frames = 0
    private var consumed = false

    fun reset() {
        candidate = HandPose.NONE; stable = HandPose.NONE
        since = -1; last = -1; knownAt = -1; emittedAt = -1; frames = 0; consumed = false
    }
    fun updateContext(next: GestureContext) {
        if (next != context) { context = next; reset() }
    }
    fun observe(pose: HandPose, timestampMs: Long): GestureCommand? {
        if (context.mode == GestureMode.OFF || timestampMs < 0) return null
        if (last >= 0 && timestampMs <= last) { resetSequence(); return null }
        if (last >= 0 && timestampMs - last > maximumGapMs) resetSequence()
        last = timestampMs
        if (pose == HandPose.NONE) { resetSequence(); return null }
        if (pose == HandPose.UNKNOWN) {
            candidate = HandPose.NONE; frames = 0; since = -1
            if (knownAt < 0 || timestampMs - knownAt > transitionMs) stable = HandPose.NONE
            return null
        }
        if (knownAt >= 0 && timestampMs - knownAt > transitionMs) stable = HandPose.NONE
        knownAt = timestampMs
        if (candidate != pose) { candidate = pose; since = timestampMs; frames = 1; return null }
        frames++
        if (frames < minimumFrames || timestampMs - since < stableMs || stable == pose) return null
        val previous = stable
        stable = pose
        val action = when {
            previous == HandPose.PALM && pose == HandPose.FIST -> GestureAction.GRAB
            previous == HandPose.FIST && pose == HandPose.PALM -> GestureAction.RELEASE
            else -> return null
        }
        if (consumed || (emittedAt >= 0 && timestampMs - emittedAt < cooldownMs)) return null
        val allowed = context.mode == GestureMode.PRACTICE ||
            (context.mode == GestureMode.SEND && action == GestureAction.GRAB) ||
            (context.mode == GestureMode.CLAIM && action == GestureAction.RELEASE)
        if (!allowed) return null
        emittedAt = timestampMs
        if (context.mode != GestureMode.PRACTICE) consumed = true
        return GestureCommand(context, action)
    }
    private fun resetSequence() {
        candidate = HandPose.NONE; stable = HandPose.NONE; since = -1; knownAt = -1; frames = 0
        // Losing a hand never clears a consumed transfer action for this token.
    }
}
