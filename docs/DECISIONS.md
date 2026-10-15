# Architectural Decisions & Technical Log

## [T1] Random Session ID Generation
- **Decision**: Session IDs are now generated using `CryptoEngine.randomBytes(16)`.
- **Implementation**: Cryptographically secure random bytes via `java.security.SecureRandom` on JVM/Android.
- **Security**: Prevents identical nonce/salt derivation across multiple transfer sessions.
