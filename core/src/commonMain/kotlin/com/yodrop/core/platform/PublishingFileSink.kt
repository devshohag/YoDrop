package com.yodrop.core.platform

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Verify is driven by the real receiver. Publish only after staging's .part commit succeeds. */
class PublishingFileSink(
    private val staging: FileSink,
    private val publish: suspend () -> Unit,
    private val removeStaging: suspend () -> Unit
) : FileSink by staging {
    private val mutex = Mutex()
    private var published = false
    private var discarded = false
    override suspend fun commit() = mutex.withLock {
        check(!discarded) { "Receive file was discarded" }
        if (!published) {
            staging.commit()
            publish() // A failed publish never acknowledges FILE_DONE.
            published = true
            removeStaging()
        }
    }
    override suspend fun discard() = mutex.withLock {
        if (!published && !discarded) {
            discarded = true
            try { staging.discard() } finally { removeStaging() }
        }
    }
}
