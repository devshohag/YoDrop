package com.yodrop.core.session

import com.yodrop.core.protocol.RawFrame
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*

internal suspend fun readFrameWithIdleTimeout(
    connection: Connection,
    timeoutMs: Long
): RawFrame = supervisorScope {
    val expired = CompletableDeferred<Unit>()

    val reader = async(start = CoroutineStart.UNDISPATCHED) {
        connection.readFrame()
    }

    val watchdog = launch {
        delay(timeoutMs)
        expired.complete(Unit)

        // Closing the platform connection unblocks its pending read.
        withContext(NonCancellable) {
            runCatching { connection.close() }
        }
        reader.cancel()
    }

    try {
        reader.await()
    } catch (failure: Throwable) {
        // Preserve cancellation of the owning session/scope.
        if (failure is CancellationException &&
            !currentCoroutineContext().isActive) {
            withContext(NonCancellable) {
                runCatching { connection.close() }
            }
            throw failure
        }

        if (expired.isCompleted) {
            throw ConnectionLostException(
                "Socket idle timeout after ${timeoutMs}ms",
                failure
            )
        }
        if (failure is CancellationException || failure is IllegalArgumentException) {
            throw failure
        }
        throw ConnectionLostException("Socket read failed: ${failure.message}", failure)
    } finally {
        watchdog.cancel()
    }
}
