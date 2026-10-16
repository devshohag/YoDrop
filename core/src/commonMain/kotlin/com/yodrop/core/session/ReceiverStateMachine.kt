package com.yodrop.core.session

import com.yodrop.core.crypto.CryptoEngine
import com.yodrop.core.crypto.KeyPair
import com.yodrop.core.crypto.NonceUtils
import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.crypto.LinkInfoCipher
import com.yodrop.core.crypto.OfferDetailsCipher
import com.yodrop.core.platform.FileSink
import com.yodrop.core.protocol.*
import com.yodrop.core.transfer.ChunkBitmap
import com.yodrop.core.transfer.ChunkPlanner
import com.yodrop.core.transfer.InMemoryResumeStore
import com.yodrop.core.transfer.ResumeStore
import com.yodrop.core.transport.Connection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReceiverStateMachine(
    private val scope: CoroutineScope,
    private val resumeStore: ResumeStore = InMemoryResumeStore(),
    private val onCleanup: (suspend () -> Unit)? = null,
    private val sinkFactory: (FileMeta) -> FileSink = { meta ->
        throw NotImplementedError("sinkFactory must be provided for receiving files")
    },
    private val timeouts: Timeouts = Timeouts(),
    private val maxConnections: Int = 4,
    private val requestWindow: Int = 4
) {
    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val eventMutex = Mutex()
    private val _transitions = MutableSharedFlow<ReceiverState>(
        replay = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transitions = _transitions.asSharedFlow()

    init {
        require(maxConnections in 1..4) { "Connection count must be 1..4" }
        require(requestWindow in 1..16) { "Request window must be 1..16" }
        _transitions.tryEmit(ReceiverState.Idle)
    }

    private var myKeyPair: KeyPair? = null
    private var sessionKeys: SessionKeys? = null
    private var activeOffer: OfferMessage? = null
    private var lastLinkInfoCounter = -1L
    private val activeConnections = mutableSetOf<SessionConnection>()
    private val workers = mutableListOf<Job>()
    private var transfer: ReceiveCoordinator? = null
    private var cleanupJob: Job? = null
    private var linksClosed = CompletableDeferred<Unit>()
    private var cleanupStarted = false
    private var lifetimeJob: Job? = null
    private var reconnectJob: Job? = null
    private var claimWindowJob: Job? = null
    private var acceptTimeoutJob: Job? = null
    private var linkingTimeoutJob: Job? = null

    private fun watchLifetime() {
        lifetimeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { this@ReceiverStateMachine.cancel("Receiver scope cancelled") }
            }
        }
    }

    private fun copyOffer(offer: OfferMessage): OfferMessage = offer.copy(
        sessionId = offer.sessionId.copyOf(),
        senderPubKey = offer.senderPubKey.copyOf()
    )

    private fun publishLocked(next: ReceiverState) {
        _state.value = next
        _transitions.tryEmit(next)
    }

    private fun failLocked(reason: String) {
        if (_state.value is ReceiverState.Idle ||
            SessionPolicy.receiverTerminal(_state.value)) return
        publishLocked(ReceiverState.Failed(reason))
        cleanup()
    }

    private fun startTimer(
        durationMs: Long,
        message: String,
        stillWaiting: () -> Boolean
    ): Job = scope.launch {
        delay(durationMs)
        eventMutex.withLock {
            if (stillWaiting()) {
                failLocked(message)
            }
        }
    }

    private suspend fun setState(next: ReceiverState): Boolean =
        eventMutex.withLock {
            val current = _state.value
            val allowed = when (next) {
                is ReceiverState.Receiving ->
                    current is ReceiverState.Auth ||
                    current is ReceiverState.Receiving ||
                    current is ReceiverState.Verifying
                is ReceiverState.Verifying ->
                    current is ReceiverState.Auth ||
                    current is ReceiverState.Receiving ||
                    current is ReceiverState.Verifying
                ReceiverState.Done -> current is ReceiverState.Verifying ||
                    current is ReceiverState.Receiving
                else -> false
            }
            if (!allowed) return@withLock false
            publishLocked(next)
            true
        }

    suspend fun startScanning(): Boolean = eventMutex.withLock {
        if (!scope.isActive ||
            !SessionPolicy.receiver(_state.value, SessionEvent.SCAN)) {
            return@withLock false
        }
        watchLifetime()
        publishLocked(ReceiverState.Scanning)
        true
    }

    suspend fun onOfferDiscovered(offer: OfferMessage): Boolean =
        eventMutex.withLock {
            if (!scope.isActive ||
                !SessionPolicy.receiver(_state.value, SessionEvent.OFFER)) {
                return@withLock false
            }
            if (offer.sessionId.size != 16 ||
                offer.senderPubKey.size != 32 ||
                offer.fileCount !in 1..1000 ||
                offer.totalBytes < 0 || offer.senderName.encodeToByteArray().size !in 1..256) return@withLock false

            activeOffer = copyOffer(offer)
            publishLocked(ReceiverState.OfferSeen(copyOffer(offer)))
            claimWindowJob = startTimer(
                timeouts.claimWindowMs,
                "Receiver claim window expired"
            ) { _state.value is ReceiverState.OfferSeen }
            true
        }

    suspend fun claimOffer(): ClaimMessage = eventMutex.withLock {
        check(scope.isActive &&
            SessionPolicy.receiver(_state.value, SessionEvent.CLAIM)) {
            "CLAIM is not valid in the current state"
        }
        val offer = checkNotNull(activeOffer)
        claimWindowJob?.cancel()
        val keyPair = CryptoEngine.generateX25519KeyPair()
        val sharedSecret = try {
            CryptoEngine.computeSharedSecret(
                keyPair.privateKey, offer.senderPubKey
            )
        } catch (e: IllegalArgumentException) {
            failLocked("Invalid sender public key")
            throw e
        }
        val keys = CryptoEngine.deriveSessionKeys(sharedSecret, offer.sessionId)
        myKeyPair = keyPair
        sessionKeys = keys
        publishLocked(ReceiverState.Claimed)

        acceptTimeoutJob = startTimer(timeouts.acceptPromptMs, "Receiver accept prompt timed out") {
            _state.value is ReceiverState.Claimed || _state.value is ReceiverState.AcceptPrompt
        }
        ClaimMessage(offer.sessionId.copyOf(), keyPair.publicKey.copyOf())
    }

    suspend fun onEncryptedOfferDetailsReceived(message: EncryptedOfferDetailsMessage): Boolean = eventMutex.withLock {
        if (!scope.isActive || _state.value !is ReceiverState.Claimed) return@withLock false
        val offer = checkNotNull(activeOffer)
        val details = try {
            OfferDetailsCipher.open(checkNotNull(sessionKeys), offer.sessionId, message)
        } catch (_: Exception) {
            failLocked("OFFER_DETAILS authentication failed")
            return@withLock false
        }
        publishLocked(ReceiverState.AcceptPrompt(copyOffer(offer), checkNotNull(sessionKeys).verifyCode, details.previewName))
        true
    }

    suspend fun acceptTransfer(linkInfo: LinkInfoMessage? = null): DecisionMessage =
        eventMutex.withLock {
            check(scope.isActive &&
                SessionPolicy.receiver(_state.value, SessionEvent.ACCEPT)) {
                "ACCEPT is not valid in the current state"
            }
            val offer = checkNotNull(activeOffer)
            acceptTimeoutJob?.cancel()
            transfer = ReceiveCoordinator(offer.sessionId.copyOf(), resumeStore, sinkFactory)
            publishLocked(ReceiverState.Linking(linkInfo))
            linkingTimeoutJob = startTimer(
                timeouts.linkingMs,
                "Receiver transport linking timed out"
            ) { _state.value is ReceiverState.Linking }
            DecisionMessage(offer.sessionId.copyOf(), true)
        }

    /** The controller binds the BLE peer; keys/AAD and consent are checked here. */
    suspend fun onEncryptedLinkInfoReceived(message: EncryptedLinkInfoMessage): LinkInfoMessage? = eventMutex.withLock {
        val current = _state.value as? ReceiverState.Linking ?: return@withLock null
        if (!scope.isActive || current.linkInfo != null || message.counter <= lastLinkInfoCounter) return@withLock null
        val link = try {
            LinkInfoCipher.open(checkNotNull(sessionKeys), checkNotNull(activeOffer).sessionId, message)
        } catch (_: Exception) {
            failLocked("LINK_INFO authentication failed")
            return@withLock null
        }
        lastLinkInfoCounter = message.counter
        publishLocked(ReceiverState.Linking(link))
        link
    }

    suspend fun denyTransfer(
        reason: String = "User rejected"
    ): DecisionMessage = eventMutex.withLock {
        check(SessionPolicy.receiver(_state.value, SessionEvent.DENY)) {
            "DENY is not valid in the current state"
        }
        val offer = checkNotNull(activeOffer)
        publishLocked(ReceiverState.Cancelled(reason))
        cleanup()
        DecisionMessage(offer.sessionId.copyOf(), false, reason)
    }

    suspend fun attachConnection(connection: Connection): Boolean =
        eventMutex.withLock {
            if (!scope.isActive ||
                !SessionPolicy.receiver(_state.value, SessionEvent.ATTACH) ||
                activeConnections.size >= maxConnections) {
                withContext(NonCancellable) {
                    runCatching { connection.close() }
                }
                return@withLock false
            }

            linkingTimeoutJob?.cancel()
            reconnectJob?.cancel()
            val wire = SessionConnection(connection, timeouts)
            activeConnections.add(wire)
            if (_state.value is ReceiverState.Linking || _state.value is ReceiverState.Failed) {
                publishLocked(ReceiverState.Auth)
            }
            val job = scope.launch {
                try {
                    wire.run { runReceiveLoop(it) }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { this@ReceiverStateMachine.cancel("Receiver scope cancelled") }
                    throw e
                } catch (e: PeerCancelledException) {
                    cancel(e.reason, notifyPeer = false)
                } catch (e: ConnectionLostException) {
                    connectionLost(wire, e)
                } catch (e: Exception) {
                    fail("Receive failed: ${e.message}")
                } finally {
                    withContext(NonCancellable) {
                        runCatching { wire.close() }
                        transfer?.release(wire)
                        eventMutex.withLock { activeConnections.remove(wire) }
                    }
                }
            }
            workers.add(job)
            true
        }
    private suspend fun runReceiveLoop(connection: SessionConnection) {
        val keys = sessionKeys ?: throw IllegalStateException("No session keys")
        val offer = activeOffer ?: throw IllegalStateException("No active offer")
        val sid = offer.sessionId

        val peerAuthFrame = readProtocolFrame(connection)
        if (peerAuthFrame.type != FrameType.AUTH) {
            fail("Expected AUTH frame, got ${peerAuthFrame.type}")
            return
        }

        val peerAuthMsg = ProtocolCodec.decodePayload<AuthMessage>(peerAuthFrame)
        if (!CryptoEngine.constantTimeEquals(peerAuthMsg.sessionId, sid)) {
            fail("SessionId mismatch in AUTH")
            return
        }

        val expectedSenderAuthPayload = "authsender".encodeToByteArray() + sid
        val expectedSenderTag = CryptoEngine.computeHmacSha256(keys.senderToReceiverKey, expectedSenderAuthPayload)
        if (!CryptoEngine.constantTimeEquals(peerAuthMsg.authTag, expectedSenderTag)) {
            fail("AUTH tag verification failed")
            return
        }

        val myAuthPayload = "authreceiver".encodeToByteArray() + sid
        val myAuthTag = CryptoEngine.computeHmacSha256(keys.receiverToSenderKey, myAuthPayload)
        val myAuthMsg = AuthMessage(sessionId = sid, authTag = myAuthTag)
        connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.AUTH, myAuthMsg))

        val manifestFrame = readProtocolFrame(connection)
        require(manifestFrame.type == FrameType.MANIFEST) { "Expected MANIFEST frame" }
        val manifest = ProtocolCodec.decodePayload<ManifestMessage>(manifestFrame)
        
        val shared = checkNotNull(transfer)
        shared.prepare(manifest)
        if (!setState(ReceiverState.Receiving(0f, manifest.files.first().name))) return
        val reported = mutableSetOf<String>()
        while (currentCoroutineContext().isActive) {
            val observed = shared.version.value
            val work = shared.schedule(connection, requestWindow)
            for (id in work.committed) if (reported.add(id)) {
                connection.writeFrame(ProtocolCodec.encodeToFrame(
                    FrameType.FILE_DONE, FileDoneMessage(id, true)))
            }
            if (work.verify != null) {
                if (!setState(ReceiverState.Verifying(work.verify.meta.id))) return
                try {
                    shared.verify(work.verify)
                } catch (e: ReceiveCoordinator.ChecksumMismatch) {
                    try {
                        connection.writeFrame(ProtocolCodec.encodeToFrame(
                            FrameType.FILE_DONE, FileDoneMessage(e.fileId, false)))
                    } finally {
                        withContext(NonCancellable) { fail(checkNotNull(e.message)) }
                    }
                    return
                }
                continue
            }
            for (request in work.requests) {
                connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.CHUNK_REQ, request))
            }
            val frame = coroutineScope {
                val changed = async(start = CoroutineStart.UNDISPATCHED) {
                    shared.version.first { it != observed }
                }
                try { connection.readFrameOrSignal(changed) }
                finally { changed.cancel() }
            } ?: continue
            when (frame.type) {
                FrameType.CHUNK_DATA -> {
                    val delivery = shared.receive(connection, frame, keys) ?: continue
                    if (!setState(ReceiverState.Receiving(delivery.progress, delivery.name))) return
                    connection.writeFrame(ProtocolCodec.encodeToFrame(FrameType.ACK,
                        AckMessage(delivery.fileId, delivery.index)))
                }
                FrameType.DONE -> {
                    require(shared.allCommitted()) { "DONE received before all files were verified" }
                    eventMutex.withLock {
                        if (!SessionPolicy.receiverTerminal(_state.value)) {
                            publishLocked(ReceiverState.Done)
                            cleanup()
                        }
                    }
                    return
                }
                else -> throw IllegalArgumentException("Unexpected receiver frame ${frame.type}")
            }
        }
    }

    private suspend fun readProtocolFrame(connection: Connection): RawFrame = connection.readFrame()

    private suspend fun fail(reason: String) {
        eventMutex.withLock {
            failLocked(reason)
        }
        withContext(NonCancellable) { linksClosed.await() }
    }

    suspend fun cancel(reason: String = "User cancelled"): Boolean =
        cancel(reason, notifyPeer = true)

    private suspend fun cancel(reason: String, notifyPeer: Boolean): Boolean =
        eventMutex.withLock {
            if (!SessionPolicy.receiver(_state.value, SessionEvent.CANCEL)) {
                return@withLock false
            }
            publishLocked(ReceiverState.Cancelled(reason))
            cleanup(if (notifyPeer) reason else null)
            true
        }

    private suspend fun connectionLost(wire: SessionConnection, failure: Throwable) {
        eventMutex.withLock {
            transfer?.release(wire)
            activeConnections.remove(wire)
            if (cleanupStarted || SessionPolicy.receiverTerminal(_state.value) ||
                activeConnections.isNotEmpty()) return@withLock
            publishLocked(ReceiverState.Failed(failure.message ?: "Connection lost", canResume = true))
            reconnectJob?.cancel()
            reconnectJob = startTimer(timeouts.linkingMs, "Reconnect deadline expired") {
                (_state.value as? ReceiverState.Failed)?.canResume == true
            }
        }
    }

    suspend fun reset(): Boolean {
        val pending = eventMutex.withLock {
            if (!SessionPolicy.receiver(_state.value, SessionEvent.RESET)) {
                return false
            }
            Pair(workers.toList(), cleanupJob)
        }
        pending.first.forEach { it.cancelAndJoin() }
        pending.second?.join()

        return eventMutex.withLock {
            if (!SessionPolicy.receiver(_state.value, SessionEvent.RESET)) {
                return@withLock false
            }
            workers.clear()
            cleanupJob = null
            activeConnections.clear()
            activeOffer = null
            lastLinkInfoCounter = -1L
            sessionKeys = null
            myKeyPair = null
            linksClosed = CompletableDeferred()
            cleanupStarted = false
            lifetimeJob = null
            reconnectJob = null
            transfer = null
            publishLocked(ReceiverState.Idle)
            true
        }
    }

    // All partial state is retained on link loss, discarded on terminal cleanup.
    private fun cleanup(reason: String? = (_state.value as? ReceiverState.Failed)?.error) {
        if (cleanupStarted) return
        cleanupStarted = true
        claimWindowJob?.cancel()
        acceptTimeoutJob?.cancel()
        linkingTimeoutJob?.cancel()
        reconnectJob?.cancel()
        lifetimeJob?.cancel()
        val connections = activeConnections.toList()
        activeConnections.clear()
        val oldWorkers = workers.toList()
        val shared = transfer

        cleanupJob = scope.launch(NonCancellable) {
            try {
                connections.forEach { runCatching { it.cancelAndClose(reason) } }
                linksClosed.complete(Unit)
                oldWorkers.forEach { it.cancel() }
                oldWorkers.joinAll()
                lifetimeJob?.join()
                shared?.cleanup()
            } finally {
                linksClosed.complete(Unit)
                runCatching { onCleanup?.invoke() }
            }
        }
    }
}
