package com.yodrop.core.protocol

/** Caller serializes platform callbacks; each connected peer owns a separate reassembler. */
class BlePeerLinks(
    private val maxPeers: Int = 16,
    private val newReassembler: () -> BleFragmenter.Reassembler = { BleFragmenter.Reassembler() }
) {
    private class Link(val assembler: BleFragmenter.Reassembler) {
        var mtu = 23
        var subscribed = false
    }
    private val links = mutableMapOf<String, Link>()
    init { require(maxPeers > 0) }

    fun connect(peerId: String): Boolean {
        if (peerId.isBlank()) return false
        if (peerId in links) return true
        if (links.size >= maxPeers) return false
        links[peerId] = Link(newReassembler())
        return true
    }
    fun contains(peerId: String) = peerId in links
    fun disconnect(peerId: String) { links.remove(peerId) }
    fun clear() { links.clear() }
    fun setMtu(peerId: String, mtu: Int): Boolean {
        val link = links[peerId] ?: return false
        if (mtu !in 23..517) return false
        link.mtu = mtu
        return true
    }
    fun mtu(peerId: String): Int = checkNotNull(links[peerId]) { "BLE peer disconnected" }.mtu
    fun subscribe(peerId: String, enabled: Boolean): Boolean {
        val link = links[peerId] ?: return false
        link.subscribed = enabled
        return true
    }
    fun subscribed(peerId: String) = links[peerId]?.subscribed == true
    fun fragments(peerId: String, msgId: Int, bytes: ByteArray): List<ByteArray> =
        BleFragmenter.fragment(msgId, bytes, BleFragmenter.packetSizeForMtu(mtu(peerId)))
    fun receive(peerId: String, packet: ByteArray): ByteArray? {
        val link = links[peerId] ?: return null
        if (packet.size > BleFragmenter.packetSizeForMtu(link.mtu)) return null
        return link.assembler.onPacketReceived(packet)
    }
    fun expireStale() { links.values.forEach { it.assembler.expireStale() } }
}

/** UUID-only discovery pins a peer; only a full GATT OFFER binds its session. */
class BleDiscoveryTarget {
    var peerId: String? = null
        private set
    private var sessionId: ByteArray? = null

    fun select(peer: String): Boolean {
        if (peer.isBlank()) return false
        if (peerId == null) peerId = peer
        return peerId == peer
    }
    fun bindSession(peer: String, session: ByteArray): Boolean {
        if (peerId != peer || session.size != 16) return false
        if (sessionId == null) sessionId = session.copyOf()
        return matches(peer, session)
    }
    fun matches(peer: String, session: ByteArray) =
        peerId == peer && sessionId?.contentEquals(session) == true
    fun clear() { peerId = null; sessionId = null }
}
