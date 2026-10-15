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
