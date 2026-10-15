package com.yodrop.core.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GattOperationKey(val peerId: String, val operation: String)

/** One platform operation in flight; callbacks must match both connection and operation. */
class GattOperationQueue(private val timeoutMs: Long = 10_000) {
    private val gate = Mutex()
    private val state = Mutex()
    private val closed = CompletableDeferred<Throwable>()
    private class Pending(val key: GattOperationKey, val result: CompletableDeferred<Int>)
    private var pending: Pending? = null

    init { require(timeoutMs > 0) }

    /** null means the platform rejected start, so no callback is expected. */
    suspend fun execute(key: GattOperationKey, start: () -> Boolean): Int? = gate.withLock {
        if (closed.isCompleted) throw closed.await()
        val operation = Pending(key, CompletableDeferred())
        state.withLock { pending = operation }
        try {
            currentCoroutineContext().ensureActive()
            if (!start()) return@withLock null
            withTimeout(timeoutMs) {
                select {
                    operation.result.onAwait { it }
                    closed.onAwait { throw it }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) {
                close(timeout)
                throw timeout
            }
            val failure = IllegalStateException("GATT callback timed out: ${key.operation}", timeout)
            close(failure)
            throw failure
        } catch (failure: Exception) {
            // An abandoned in-flight operation cannot safely share callbacks with a new one.
            close(failure)
            throw failure
        } finally {
            withContext(NonCancellable) {
                state.withLock { if (pending === operation) pending = null }
            }
        }
    }

    suspend fun complete(key: GattOperationKey, status: Int): Boolean = state.withLock {
        val operation = pending ?: return@withLock false
        if (closed.isCompleted || operation.key != key) return@withLock false
        operation.result.complete(status)
    }

    /** Synchronous shutdown also unblocks callers waiting for a callback. */
    fun close(cause: Throwable = IllegalStateException("GATT connection closed")) {
        closed.complete(cause)
    }
}
