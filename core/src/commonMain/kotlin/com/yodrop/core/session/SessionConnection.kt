package com.yodrop.core.session

import com.yodrop.core.protocol.*
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ConnectionLostException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal class PeerCancelledException(val reason: String) : Exception(reason)

/** One socket reader and serialized writes, independent of file IO/verification. */
internal class SessionConnection(
    private val transport: Connection,
    private val timeouts: Timeouts,
    private val consumeAcks: Boolean = false
) : Connection {
    private val writes = Mutex()
    private val frames = Channel<RawFrame>(Channel.RENDEZVOUS)
    override val isClosed: Boolean get() = transport.isClosed

    suspend fun run(block: suspend (SessionConnection) -> Unit) = supervisorScope {
        val lost = CompletableDeferred<Throwable>()
        val reader = launch {
            try {
                while (true) {
                    val frame = readFrameWithIdleTimeout(transport, timeouts.socketIdleMs)
                    when (frame.type) {
                        FrameType.PING -> {
                            val ping = ProtocolCodec.decodePayload<PingPongMessage>(frame)
                            if (!ping.reply) writeFrame(ProtocolCodec.encodeToFrame(
                                FrameType.PING, ping.copy(reply = true)
                            ))
                        }
                        FrameType.ACK -> {
                            if (consumeAcks) ProtocolCodec.decodePayload<AckMessage>(frame)
                            else frames.send(frame)
                        }
                        FrameType.CANCEL -> throw PeerCancelledException(
                            ProtocolCodec.decodePayload<CancelMessage>(frame).reason
                        )
                        else -> {
                            frames.send(frame)
                            // DONE is the last protocol frame; normal peer EOF follows it.
                            if (frame.type == FrameType.DONE) return@launch
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lost.complete(e)
            }
        }
        val pinger = launch {
            var elapsed = 0L
            try {
                while (isActive) {
                    delay(timeouts.pingIntervalMs)
                    elapsed += timeouts.pingIntervalMs
                    writeFrame(ProtocolCodec.encodeToFrame(
                        FrameType.PING, PingPongMessage(elapsed)
                    ))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lost.complete(e)
            }
        }
        val operation = async { block(this@SessionConnection) }
        try {
            select<Unit> {
                operation.onAwait { }
                lost.onAwait { throw it }
            }
        } catch (e: Exception) {
            if (e !is CancellationException && e !is ConnectionLostException &&
                e !is PeerCancelledException) {
                withContext(NonCancellable) { cancelAndClose(e.message ?: "Protocol failure") }
            }
            throw e
        } finally {
            // Close before joining: cancellation alone cannot unblock platform IO.
            withContext(NonCancellable) {
                reader.cancel()
                pinger.cancel()
                operation.cancel()
                runCatching { transport.close() }
                joinAll(reader, pinger, operation)
                frames.cancel()
            }
        }
    }

    override suspend fun readFrame(): RawFrame = frames.receive()

    suspend fun readFrameOrSignal(signal: Deferred<*>): RawFrame? = select {
        frames.onReceive { it }
        signal.onAwait { null }
    }

    suspend fun completeAndClose() = sendBeforeClose(RawFrame(FrameType.DONE, ByteArray(0)))

    override suspend fun writeFrame(frame: RawFrame) {
        writes.withLock {
            try {
                transport.writeFrame(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e // Invalid protocol output is terminal, not a lost link.
            } catch (e: Exception) {
                throw ConnectionLostException("Socket write failed: ${e.message}", e)
            }
        }
    }

    override suspend fun close() = transport.close()

    suspend fun cancelAndClose(reason: String?) = sendBeforeClose(reason?.let {
        ProtocolCodec.encodeToFrame(FrameType.CANCEL, CancelMessage(it.take(1024)))
    })

    private suspend fun sendBeforeClose(frame: RawFrame?) = supervisorScope {
        val watchdog = launch {
            delay(250)
            runCatching { transport.close() }
        }
        try {
            if (frame != null && !isClosed) {
                withTimeoutOrNull(250) { runCatching { writeFrame(frame) } }
            }
        } finally {
            runCatching { transport.close() }
            watchdog.cancelAndJoin()
        }
    }
}
