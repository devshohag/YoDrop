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
  - Chunk size must be between 64 KiB and 8 MiB.
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
