package com.yodrop.core.protocol

import kotlin.time.TimeSource

/** Bounded session cache. A UUID-only advertisement cannot reveal a new session until reread. */
class BleOfferCache(
    private val ttlMs: Long = 60_000,
    private val maxEntries: Int = 16,
    private val nowMs: (() -> Long)? = null
) {
    private val origin = TimeSource.Monotonic.markNow()
    private fun now() = nowMs?.invoke() ?: origin.elapsedNow().inWholeMilliseconds
    private data class Entry(val peer: String, val offer: OfferMessage, val readAt: Long)
    private val entries = mutableListOf<Entry>()
    init { require(ttlMs > 0 && maxEntries > 0) }
    private fun expire() { val now = now(); entries.removeAll { now - it.readAt >= ttlMs } }
    private fun copy(offer: OfferMessage) = offer.copy(sessionId = offer.sessionId.copyOf(), senderPubKey = offer.senderPubKey.copyOf())
    fun put(peer: String, offer: OfferMessage) {
        require(peer.isNotBlank())
        BleMessageCodec.encode(offer) // Apply the same bounds as the real GATT path.
        expire()
        entries.removeAll { it.peer == peer && it.offer.sessionId.contentEquals(offer.sessionId) }
        if (entries.size >= maxEntries) entries.removeAt(0)
        entries.add(Entry(peer, copy(offer), now()))
    }
    fun get(peer: String, sessionId: ByteArray): OfferMessage? {
        expire()
        return entries.firstOrNull { it.peer == peer && it.offer.sessionId.contentEquals(sessionId) }?.offer?.let(::copy)
    }
    fun alreadyRead(peer: String): Boolean { expire(); return entries.any { it.peer == peer } }
    fun clear() { entries.clear() }
}
