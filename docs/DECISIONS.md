# Architectural Decisions & Technical Log

## [T1] Random Session ID Generation
- **Decision**: Session IDs are now generated using `CryptoEngine.randomBytes(16)`.
- **Implementation**: Cryptographically secure random bytes via `java.security.SecureRandom` on JVM/Android.
- **Security**: Prevents identical nonce/salt derivation across multiple transfer sessions.

## [T2] Mutual AUTH Verification & Binding
- **Decision**: Explicit mutual authentication over TCP connections.
- **Protocol**:
  - Sender sends `AuthMessage(sessionId, HMAC(senderToReceiverKey, "authsender" || sessionId))`
  - Receiver sends `AuthMessage(sessionId, HMAC(receiverToSenderKey, "authreceiver" || sessionId))`
- **Verification**:
  - Both sides verify that `sessionId` matches the active handshake session.
  - Constant-time verification is enforced using constant-time byte comparison (`MessageDigest.isEqual`).
  - Any tag mismatch, sessionId mismatch, or role misdirection immediately sends `CancelMessage("AUTH_FAILED")`, closes the socket, and transitions to `State.Failed("AUTH verification failed")`.

## [T3] Nonce Derivation & AAD Authentication Scheme
- **Decision**: Deterministic wire-bound nonce derivation and cryptographically bound chunk headers.
- **Binary CHUNK_DATA Payload Layout**:
  - `fileIdLen (u8)`: 1 byte
  - `fileId`: UTF-8 bytes
  - `chunkIndex (u32)`: 4 bytes big-endian
  - `attemptCounter (u32)`: 4 bytes big-endian
  - `ciphertext`: AES-256-GCM encrypted bytes + 16-byte authentication tag
- **Nonce Scheme**:
  - Nonce is 12 bytes: `nonceBase[0..3] || u32(chunkIndex) || u32(attemptCounter)`.
  - Guarantees uniqueness even across retries, retransmissions, or parallel streams.
- **Associated Authenticated Data (AAD)**:
  - `AAD = sessionId (16B) || fileId (UTF-8) || u32(chunkIndex)`.
  - Prevents chunk swapping, replay from different files, or tampering with the plain chunkIndex header.

## [T4] Untrusted Length Bounding & Manifest Sanitization
- **Decision**: Strict upper and lower bounds on all network frame allocations.
- **Frame Caps**:
  - `MAX_FRAME_SIZE = 4 * 1024 * 1024 + 1024` (4 MiB payload + 1 KiB overhead).
  - Any frame with `length < 1` or `length > MAX_FRAME_SIZE` immediately raises an error and terminates the socket connection.
- **Zero-Copy Frame Assembly**:
  - Network bytes are read directly into the final `payload` buffer without concatenating `lenBuf + frameBuf`.
- **Manifest Sanitization**:
  - Manifest file sizes must be strictly non-negative (`size >= 0`).
  - File count capped at 1,000 files per manifest.
  - Chunk size must be between 64 KiB and 4 MiB.
  - File names are sanitized against path traversal (`..`, `/`, `\`) and ASCII control characters.

## [T5] Streaming File IO & OOM Prevention
- **Decision**: Zero in-memory whole-file storage.
- **Architecture**:
  - `FileSource`: Random-access streaming abstraction reading exact `[offset, len)` slices from disk via `RandomAccessFile`/`FileChannel`.
  - `FileSink`: Random-access streaming sink writing chunks directly into temporary `.part` files on disk, calculating incremental or sequential post-transfer SHA-256 without loading files into RAM.
  - On verification success, atomic rename from `.part` to final destination file.

## [T6] Persistent Resume Store & Partial Transfer Recovery
- **Decision**: Chunk progress is persisted to disk using `ResumeStore`.
- **Mechanism**:
  - Bitmap state is stored per `{sessionId, fileId}`.
  - Upon reconnection, receiver loads the existing bitmap and requests only unreceived chunks.
  - State file is deleted once file integrity is verified and committed.

## [T7] Parallel TCP Pipelining, NACK, & Keep-Alive Handlers
- Sender and receiver accept multiple sockets; Step 9 implements real parallel scheduling.
- **Wire Control Additions**:
  - `ACK (0x05)`: Explicit chunk delivery confirmation.
  - `NACK (0x06)`: Signals damaged or missing chunk indices, triggering immediate priority retransmission.
  - `PING/PONG (0x0A)` with a reply marker: Senders automatically echo PING with PONG to maintain active socket liveness.
- Sender completion IDs are shared across reconnects. Step 9 adds real receiver
  workers with shared bitmap/lease scheduling.

## [T8] Strict Session Timeouts & Terminal State Resource Cleanup
- **Decision**: Active timeout timers for each protocol transition and deterministic cleanup hooks.
- **Timeouts**:
  - `OFFER_TIMEOUT`: 60 seconds (advertising expires if no CLAIM received).
  - Receiver CLAIM window: 10 seconds from OFFER_SEEN; accept prompt: 60 seconds.
  - `LINKING_TIMEOUT`: 20 seconds (establishing transport link).
  - `SOCKET_IDLE_TIMEOUT`: 15 seconds.
  - `PING_INTERVAL`: 5 seconds.
- **Cleanup Policy**:
  - Terminal failures, cancel and done invoke cleanup. Connection loss publishes
    `Failed(canResume = true)` and retains session resources until reconnect or expiry.


## [Step 2] Remove JVM-only dependencies from commonMain
- AUTH comparisons use CryptoEngine.constantTimeEquals with JVM/Android
  actual implementations delegated to MessageDigest.isEqual.
- Sender connection bookkeeping and attempt allocation use coroutine Mutex.
- The attempt counter stays monotonic over a SenderStateMachine lifetime;
  exhaustion fails before integer overflow.
- MemoryFileSink uses a ByteArray growth buffer, honours offsets and serializes
  writes/snapshots using Mutex. Its toByteArray helper is now suspend.
- Memory IO stays in commonMain because CrossPlatformTransferManager currently
  uses MemoryFileSource in production.
- scripts/check-common-main.ps1 rejects Java references, JVM monitor calls and
  blocking coroutine bridges in commonMain.
- This step does not claim iOS compilation or completion of later lifecycle work.

## [Step 3 / A1] Owner-approved BouncyCastle X25519
- Owner approved Option 3 on 2026-10-15.
- Only X25519 uses org.bouncycastle:bcprov-jdk15to18:1.86.
- This is a specific exception to the no-new-third-party-library rule.
- minSdk remains 29.
- CryptoEngine actual implementation is shared by JVM and Android through
  jvmAndAndroidMain.
- Custom BigInteger ladder, XDH provider switching and broad fallback removed.
- Incoming public keys are length-checked and bit 255 is masked on a copy.
- Agreement rejects low-order inputs that produce an all-zero shared secret.
- AES-GCM, HMAC and SHA-256 continue to use platform JCA.
- RFC vectors and negative checks share one suite between JVM and Android.
- Android instrumentation execution is pending until device output is recorded.
- This does not claim constant execution time across all JVM/ART environments.

## [Step 4 / A2] Protocol validation and approved spec numbering
- Owner approved restoring spec frame numbering on 2026-10-15.
- ACK=05, NACK=06, FILE_DONE=07, CANCEL=08, DONE=09, PING/PONG=0A.
- Shared PING/PONG CBOR payload has ts and reply fields; replies are not echoed.
- NACK includes a reason; existing source calls default to RETRY_REQUESTED.
- Manifest chunk size is 64 KiB..4 MiB. Total chunks are capped at 1,048,576
  to bound bitmap memory; individual file size is capped at 4 TiB.
- Chunk counts use division/remainder instead of overflow-prone addition.
- Filenames reject paths, leading dots, reserved names and unsafe characters;
  a..b.txt remains valid.
- Sender checks known file IDs, request count and index bounds before reading.
- Receiver checks requested file/index, header bounds and exact chunk lengths
  before writing. Successful FILE_DONE is sent only after commit.
- Disk commits retain existing files and select name (1).ext, etc.
- Verify code uses SHA-256(sharedSecret), interpreted as an unsigned decimal
  integer, taking its first four digits and padding small values to four digits.
- Existing wire peers must be updated together.
- API 29 crypto verification remains pending from Step 3.

## [Step 5] Session event guards and encapsulation
- Restored sender Grabbed and Claimed states; singleton states use data object.
- Public events are suspend functions serialized with an event Mutex.
- Invalid Unit-style events return false; invalid receiver methods that must
  return a protocol message throw IllegalStateException without changing state.
- First valid matching CLAIM wins in single-send mode. Group mode is P10.
- Foreign-session claims/decisions are ignored.
- Session IDs, derived keys and active offers are private.
- Read-only transitions streams retain the latest 64 emitted states.
- Receiver Claimed remains current until a scheduled prompt transition.
- Worker state updates cannot overwrite terminal states.
- Terminal sessions need explicit reset; reset waits for old workers/cleanup.
- AUTH tests use real handshakes; resume fixtures use the advertised session ID.
- API 29 crypto execution remains pending from Step 3.

## [Step 6 / A3] Injected timeout configuration
- Owner approved a 60-second accept-prompt timeout on 2026-10-15.
- Mutable global SessionTimeouts removed; both machines receive immutable Timeouts.
- Sender OFFER: 60 seconds; sender await-accept: 60 seconds.
- Receiver CLAIM window: 10 seconds from OFFER_SEEN.
- Receiver accept prompt: 60 seconds from prompt publication.
- LINKING: 20 seconds on each side, cancelled when a connection is attached.
- Each frame read has a 15-second idle deadline, including AUTH reads.
- PING/PONG reads reset the receiver deadline; sender ACK/PING processing
  starts a fresh deadline for the next read.
- Read watchdog closes connections to unblock blocking platform socket reads.
- Platform socket close no longer flushes before closing; writeFrame flushes.
- Timeout tests use runTest/virtual time with real state machines.
- Step 7 adds periodic PING, verification-time liveness and resumable connection loss.
  API 29 crypto verification remains pending.

## [Step 7] Connection lifecycle, liveness and terminal cleanup
- Both state machines wrap each socket with SessionConnection: one reader and a
  separate 5-second PING coroutine; writes are serialized so frames cannot interleave.
- PING/PONG are consumed independently of chunk IO and final SHA-256 verification.
  Any inbound frame resets the 15-second read deadline. Replies are not echoed.
- EOF, transport IO errors and idle timeout close the connection and publish
  Failed(canResume = true) when no usable sender sockets remain / receiver disconnects.
  Keys, sender sources, receiver sinks, bitmaps and attempt counter remain valid.
- Reattachment uses the existing LINKING window (20 seconds by default). Each
  new connection re-AUTHs and resends the manifest; changed metadata is rejected.
  No total deadline is imposed on an actively progressing large transfer.
- Reconnect expiry, cancel, deny, authentication/protocol/checksum failure and
  scope cancellation are terminal. Terminal Failed has canResume = false.
- Failed(canResume = true) permits ATTACH/CANCEL; terminal states require reset.
- Completed file IDs survive reconnect, preventing duplicate completion counting
  and preventing already committed files from being recreated during reattachment.
- Terminal cleanup is idempotent and runs with NonCancellable. It sends CANCEL
  with a bounded best-effort reason before closing, cancels/joins workers, closes
  sources and sinks, discards uncommitted partial files and clears resume state.
  Peer CANCEL is consumed without echo; transport cleanup hook runs once.
- A lifetime watcher handles owner-scope cancellation before a worker starts.
  Closing the socket before joining unblocks pending blocking platform reads.
- FileSink.discard is wired through Android's scoped-storage wrapper and both
  disk implementations. It deletes .part data and retains committed destinations.
- Step 8 still owns atomic/forced bitmap persistence, crash recovery validation
  and the >=256 MiB real-TCP kill-at-40-percent acceptance test. Step 9 owns the
  real parallel receiver; this step does not claim either acceptance criterion.

## [Step 8] Durable atomic resume and real TCP recovery
- Receiver now calls FileSink.force() before marking a chunk received and saving
  its bitmap. JVM/Android disk sinks use FileChannel.force(true).
- DiskResumeStore writes a unique temporary file in the destination directory,
  forces it, then replaces the bitmap with ATOMIC_MOVE + REPLACE_EXISTING.
  Unsupported atomic replacement fails; it never falls back to an in-place write.
  Temporary files from completed/failed saves are removed. Load/save/clear calls
  on a store instance are serialized with a Mutex; records retain their raw format.
- Directory metadata is forced where supported. Directory force is best effort
  on filesystems/platforms that cannot open/force directories, including Windows;
  bitmap file force and atomic replacement remain mandatory.
- Bitmap loads reject unexpected lengths before reading, bounding allocation and
  rejecting torn/oversized records rather than trusting their received bits.
- Before using stored bits, receiver checks that the sink contains bytes through
  the end of the highest marked chunk. Missing/truncated partial data resets all
  bits and clears the persisted record before requesting chunks again.
- FileSink.hasDataThrough conservatively rejects nonempty stored progress by
  default. Memory and disk sinks implement actual length checks; Android's scoped
  storage wrapper forwards force and resume validation to its disk sink.
- Disk sinks close/reopen stale handles after partial-file loss and do not create
  a new .part while closing an unused/already committed sink.
- Checksum mismatch discards partial data and clears bitmap state before peer
  notification; notification failure still leaves a terminal failure.
- Step8ResumeTest streams 256 MiB over real TCP, drops both sockets after chunk
  26/64 is forced and persisted (40.625%), reattaches the same machines, verifies
  both peers re-AUTH and that exactly 38 missing chunks / 159,383,552 plaintext
  bytes cross the second connection, and compares the final SHA-256.
- Separate real-TCP crash fixtures seed received bits with truncated/missing
  .part files, verify both chunks are requested again, and compare final SHA-256.
- This restores a live session after connection loss; process-restart restoration
  of session keys/user consent is not implemented. Real parallel receiver is implemented in
  Step 9; the 1 GiB / 256 MiB heap acceptance test remains Step 10.


### Step 8 Windows concurrent-reader correction
- Owner's Windows run passed the 256 MiB kill/reconnect test but failed the
  concurrent snapshot test while its raw FileInputStream reader opened a path
  during replacement (FileNotFoundException). Step 8 is not yet dual-build green.
- All DiskResumeStore instances for one canonical directory now share a Mutex
  within the process. Application readers, writers and clear operations use it.
- Bitmap loading uses one NIO FileChannel for both size validation and bounded
  reads; missing records return an empty bitmap via NoSuchFileException.
- The concurrent test uses the production loader through a separate store instance
  and an equivalent directory spelling, retaining the exact old-or-new byte assertion.
  Both valid snapshots are nonempty so a missing-record fallback cannot pass.
  No retry, sleep, ignored IO failure or non-atomic write fallback was introduced.
- This process-local gate does not coordinate independent external writer processes.

## [Step 9] Real parallel receiver and session completion
- Receiver accepts up to four authenticated connections (one-connection use stays
  supported). Each socket runs a real receive worker; there is one shared
  ReceiveCoordinator for the accepted session and one ChunkBitmap/sink per file.
- The coordinator's Mutex guards chunk leases, received bits, retry counts,
  byte-based progress and verification ownership. A chunk belongs to only one
  socket at a time; failed workers release leases for healthy sockets to acquire.
- Each connection has a bounded sliding request window (default 4; configurable
  1..16). A received chunk immediately frees a slot, without waiting for the rest
  of a batch. Idle workers wait on a shared version signal or socket input using
  channel select, without polling or dropping frames during waiter cancellation.
- Every socket re-AUTHs and validates the same manifest. More sockets may attach
  during AUTH/RECEIVING/VERIFYING. A fifth connection is rejected and closed on
  both machines. Event-policy matrix tests cover the newly allowed attachments.
- AES-GCM decryption failures send NACK with CHUNK_AUTHENTICATION_FAILED while
  retaining the chunk lease. Sender retransmits using its session-wide monotonic
  attempt counter (and hence a fresh chunk nonce). After three failures for a
  chunk on one worker, that socket is closed and its work is reassigned. Healthy
  sockets can finish without turning the session into Failed.
- Header/request bounds errors, invalid AUTH, manifest mismatch, checksum failure
  and peer cancellation remain terminal. Normal connection loss still retains
  resume data and starts the Step 7 reconnect deadline only if no sockets remain.
- Verification is claimed once per file, performed outside the coordinator lock,
  and committed bookkeeping is protected from cancellation. PING/PONG continues
  on each socket during verification. Received bytes are forced before bitmap
  publication, retaining the Step 8 persistence ordering.
- Verified file IDs are reported on surviving sockets so a lost notification
  does not strand completion. Sender deduplicates FILE_DONE IDs session-wide,
  transitions to Done once after all IDs are verified, broadcasts bounded DONE
  messages to authenticated peers, then cleans up. Premature receiver DONE is
  rejected. Receiver also transitions/cleans up once after all files are committed.
- Sender ACK traffic is consumed by its socket reader so ACKs cannot block its
  PING/EOF processing while a chunk write is in progress.
- Cleanup and reset wait for every worker, release leases and close shared sinks
  once the workers stop. Completed sinks are retained until terminal cleanup.
- T7PipeliningAndStreamsTest no longer contains a hand-written receiver. It uses
  both real machines with four real TCP sockets for two disk files totaling
  48 MiB, injects corrupted ciphertext, proves fresh retry attempts, verifies
  both final SHA-256 values and asserts one Done transition on each side.
- Separate tests exhaust one worker's retry budget while the other three finish,
  prove sliding-window refill before a held chunk arrives, and reject a fifth
  connection. The existing 256 MiB real TCP disconnect/reconnect test still passes.
- Real Android transport wiring remains later remediation work. Step 10's
  1 GiB / 256 MiB heap acceptance criterion is not claimed here.

## [Step 10] 1 GiB transfer under a 256 MiB JVM test heap
- core:jvmTest now sets maxHeapSize = "256m" and maxParallelForks = 1. The full
  core suite, including real parallel/reconnect tests, runs inside that limit.
- Step10LargeTransferTest verifies Runtime.maxMemory is at most 256 MiB before
  starting, so running it with a larger heap cannot produce a false memory proof.
- The test creates exactly 1,073,741,824 source bytes with a reusable 1 MiB buffer
  and streaming SHA-256. Block-specific prefixes expose reordered/duplicated data.
- The real sender reads a DiskFileSource; the real receiver writes a DiskFileSink
  and persists bitmaps through DiskResumeStore. Four authenticated real TCP
  connections carry the file with the production default request window.
- The test asserts all four sockets carry data, exactly 256 unique chunk indices
  and 1 GiB of plaintext cross them, the destination has the exact length and
  streaming SHA-256, the source closes once, both machines emit Done once, all
  sockets close and partial/resume files are removed after successful completion.
- No explicit GC, skipped test or larger-heap override is used. The existing
  256 MiB disconnect/reconnect and four-socket corruption/retry tests also run
  under the same test-process heap limit.
- Heap usage sampled during frame IO is diagnostic, not an exact peak measure.
  This acceptance proves the JVM heap cap; it is not a native/RSS memory or
  Android-device memory/performance measurement.
- About 2 GiB of temporary disk is required for source plus destination. Temporary
  files are removed in finally; the acceptance test has a five-minute deadline.


## [Step 11] Sender-owned transport selection and shared TCP adapter
- SenderStateMachine.selectTransport is a query allowed during AwaitAccept, after
  a valid CLAIM. It uses the sender's actual offered file sizes and the peer's
  HelloMessage platform/capabilities; it does not advance state or start a link.
- Selection returns BleOnly, DataLink(type), or Unavailable. No-common-link and
  BLE-only are distinct, and no fallback invents LAN or sender-only Wi-Fi Direct.
- Small means strictly below 64 * 1024 bytes, including an empty payload. Both
  peers must advertise CAP_BLE; otherwise normal data-link selection is attempted.
  Exactly 64 KiB uses a data link. Negative sizes are rejected.
- Large-payload priorities remain same LAN, Android/Android Wi-Fi Direct,
  iOS/iOS MPC, then Android-sender/iOS-receiver hotspot. Each option checks the
  relevant capability on both peers. Hotspot requires sender HOST and peer CLIENT;
  reverse iOS-sender hotspot orchestration is outside the architecture's scope.
- LAN requires both LAN caps, both IPv4 addresses and an explicit sender interface
  prefix. Missing metadata skips LAN. IPv4 octets and prefixes 0..32 are validated;
  /16 and /23 are supported without any /24 assumption. IPv6 selection is deferred.
- AndroidSenderTransportSelector reads ConnectivityManager.activeNetwork and that
  network's LinkProperties LinkAddress/prefixLength on every query. Only active
  Wi-Fi/Ethernet IPv4 metadata is used; VPN/cellular/no network yields no LAN address.
  The existing ACCESS_NETWORK_STATE permission is sufficient for this metadata.
- Android glue delegates policy to the core sender. BLE HELLO delivery and the
  MainActivity caller remain Step 12; peerIp must be supplied by known peer network
  metadata, never guessed from an SSID, a default gateway or a hardcoded address.
  LINK_INFO creation/encryption remains Step 14. BLE-only selection does not claim
  that the later small-payload BLE transfer implementation is complete.
- LanTransport connect/accept share TcpConnection. Failed/cancelled connects close
  their socket. WifiDirectTransport delegates TCP operations to LanTransport;
  actual WifiP2pManager group ownership/lifecycle remains Step 16.
- Removed six empty README.kt files and unused CrossPlatformTransferManager and
  CrossPlatformBridge stubs, including their fabricated hotspot join credentials.
  The credential data used by AndroidHotspotManager stays in HotspotCredentials.kt
  with explicit values. The earlier Step 2 note about the removed manager is historical.
- No dependency, BLE wire message, frame numbering or session timeout changed.
