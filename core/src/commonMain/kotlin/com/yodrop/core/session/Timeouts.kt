package com.yodrop.core.session

data class Timeouts(
    val offerMs: Long = 60_000L,
    val claimWindowMs: Long = 10_000L,
    val acceptPromptMs: Long = 60_000L,
    val linkingMs: Long = 20_000L,
    val socketIdleMs: Long = 15_000L,
    val pingIntervalMs: Long = 5_000L
) {
    init {
        require(
            listOf(
                offerMs, claimWindowMs, acceptPromptMs,
                linkingMs, socketIdleMs, pingIntervalMs
            ).all { it > 0 }
        ) { "All timeout values must be positive" }
    }
}