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
- **Decision**: Multi-socket parallel streams (2–4 connections) per transfer session.
- **Wire Control Additions**:
  - `ACK (0x05)`: Explicit chunk delivery confirmation.
  - `NACK (0x06)`: Signals damaged or missing chunk indices, triggering immediate priority retransmission.
  - `PING/PONG (0x0A)` with a reply marker: Senders automatically echo PING with PONG to maintain active socket liveness.
- **Synchronization**:
  - A single `ChunkBitmap` protected by concurrency primitives coordinates parallel TCP worker routines without chunk collision or duplicate downloads.

## [T8] Strict Session Timeouts & Terminal State Resource Cleanup
- **Decision**: Active timeout timers for each protocol transition and deterministic cleanup hooks.
- **Timeouts**:
  - `OFFER_TIMEOUT`: 60 seconds (advertising expires if no CLAIM received).
  - Receiver CLAIM window: 10 seconds from OFFER_SEEN; accept prompt: 60 seconds.
  - `LINKING_TIMEOUT`: 20 seconds (establishing transport link).
  - `SOCKET_IDLE_TIMEOUT`: 15 seconds.
  - `PING_INTERVAL`: 5 seconds.
- **Cleanup Policy**:
  - Every transition to `Failed`, `Cancelled`, or `Done` invokes `cleanup()`, closing all sockets, cancelling background jobs, and stopping transports.


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
- Global timeout configuration remains for Step 6.
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
- Periodic PING, verification-time liveness and resumable connection loss
  remain Step 7 work; API 29 crypto verification remains pending.