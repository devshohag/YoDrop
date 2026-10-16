package com.yodrop.core.trust

import com.yodrop.core.crypto.CryptoEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReceiveWindow(val token: String, val target: String, val openedAtMs: Long, val expiresAtMs: Long, val session: String? = null)

/** Single session-owner thread. Monotonic time is checked on every authorization, not only by a UI timer. */
class TrustedReceiveWindow(private val nowMs: () -> Long) {
    companion object { const val DURATION_MS = 60_000L }
    private val _state = MutableStateFlow<ReceiveWindow?>(null)
    val state = _state.asStateFlow()
    fun open(fingerprint: String): ReceiveWindow {
        require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
        val now = nowMs(); require(now in 0..Long.MAX_VALUE - DURATION_MS)
        return ReceiveWindow(CryptoEngine.randomBytes(16).trustHex(), fingerprint, now, now + DURATION_MS).also { _state.value = it }
    }
    fun isLive(window: ReceiveWindow): Boolean {
        val current = _state.value ?: return false
        if (nowMs().let { it < current.openedAtMs || it >= current.expiresAtMs }) { _state.value = null; return false }
        return current.token == window.token && current.target == window.target &&
            current.openedAtMs == window.openedAtMs && current.expiresAtMs == window.expiresAtMs
    }
    fun bind(session: String): ReceiveWindow? {
        val window = _state.value ?: return null
        if (!isLive(window) || (window.session != null && window.session != session)) return null
        return window.copy(session = session).also { _state.value = it }
    }
    fun permits(window: ReceiveWindow, session: String, fingerprint: String): Boolean =
        isLive(window) && window.session == session && _state.value?.session == session && window.target == fingerprint
    fun consume(window: ReceiveWindow, session: String, fingerprint: String): Boolean {
        if (!permits(window, session, fingerprint)) return false
        _state.value = null; return true
    }
    fun close(token: String? = null): Boolean {
        val current = _state.value ?: return false
        if (token != null && token != current.token) return false
        _state.value = null; return true
    }
}
