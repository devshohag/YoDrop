package com.yodrop.core.transport

import com.yodrop.core.protocol.LinkInfoMessage
import com.yodrop.core.protocol.RawFrame
import kotlinx.coroutines.channels.Channel

interface Connection {
    val isClosed: Boolean
    suspend fun readFrame(): RawFrame
    suspend fun writeFrame(frame: RawFrame)
    suspend fun close()
}

interface ConnectionListener {
    suspend fun accept(): Connection
    suspend fun close()
}

interface Transport {
    suspend fun connect(linkInfo: LinkInfoMessage): Connection
    suspend fun listen(port: Int = 0): ConnectionListener
}

/**
 * In-memory bidirectional connection used for deterministic loopback tests on JVM/Native.
 */
class LoopbackConnection(
    private val incoming: Channel<RawFrame>,
    private val outgoing: Channel<RawFrame>
) : Connection {
    @Volatile
    private var _closed = false
    override val isClosed: Boolean get() = _closed

    override suspend fun readFrame(): RawFrame {
        if (_closed) throw IllegalStateException("Connection is closed")
        return incoming.receiveCatching().getOrNull() 
            ?: throw IllegalStateException("Channel closed by peer")
    }

    override suspend fun writeFrame(frame: RawFrame) {
        if (_closed) throw IllegalStateException("Connection is closed")
        outgoing.send(frame)
    }

    override suspend fun close() {
        if (!_closed) {
            _closed = true
            outgoing.close()
            incoming.cancel()
        }
    }

    companion object {
        fun createPair(): Pair<LoopbackConnection, LoopbackConnection> {
            val aToB = Channel<RawFrame>(Channel.BUFFERED)
            val bToA = Channel<RawFrame>(Channel.BUFFERED)
            val connA = LoopbackConnection(incoming = bToA, outgoing = aToB)
            val connB = LoopbackConnection(incoming = aToB, outgoing = bToA)
            return Pair(connA, connB)
        }
    }
}
