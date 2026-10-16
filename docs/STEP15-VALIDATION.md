# Step 15 / A4 validation

Base: main **52342ea** (Step 14). Owner approved A4 on 2026-10-16.

## Implemented

Service-UUID-only discovery; fixed random 128-bit service/characteristic UUIDs;
21-byte legacy advertising reference packet including Flags, empty scan response.
Grabbed-only advertising with state rechecks and 60-second timeout/session cleanup.
Plain OFFER has exactly five public fields. New encrypted tag 07 OFFER_DETAILS
supplies previewName only after CLAIM/ECDH; verified metadata gates AcceptPrompt.
A separate HKDF-expanded AES-GCM key/nonce domain prevents control/TCP nonce reuse.
Android reads via the existing fragmented HELLO/write -> OFFER/notify exchange,
caches peer/session offers, disconnects without CLAIM, and reconnects only for an
explicit Claim. Client/server accept one GATT connection; MTU 247 is requested and
actual callback MTU controls fragmentation. See DECISIONS.md A4 for details.

Both phone APKs must update together: old UUIDs and plaintext filename OFFERs are
incompatible. No iOS implementation or physical interoperability is claimed.

## Local verification actually executed

- Kotlin 2.2.0 + repository-version dependencies/serialization plugin compiled all
  common/JVM production/test sources, including commonTest and X25519 tests.
- **26 suites / 175 tests passed under -Xmx256m.** Includes 17 new Step15DiscoveryTest
  cases, all previous transfer/protocol/crypto tests, RFC 7748 vectors, and the
  authenticated BLE-endpoint -> real-TCP integration.
- New tests cover UUID/AD size/layout, five-field plain OFFER/no filename, rejection
  of legacy/extra plaintext fields, encrypted filename fragmentation at MTU 23/247,
  authentication/session/counter/key tampering, key-domain separation, length/counter
  bounds, missing-metadata and original 60-second offer timeout, claim/cancel states,
  counter monotonicity across reset, bounded/cache expiry/copies, one pinned peer,
  read-disconnect/explicit-reconnect controller callbacks and DENY-before-close order.
- All changed Android/Compose sources compiled against official API 35 android.jar,
  Kotlin/Compose compiler 2.2.0 and the repository-version AndroidX dependencies.
  Guarded pre-33 Bluetooth API deprecation warnings remain.
- git diff --check passed; the commonMain JVM-only API scan returned no matches.

Final complete standalone JUnit output:

```text
JUnit version 4.13.2
................Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=224; SHA-256 matched; seconds=13
..............................................Step14: authenticated BLE LINK_INFO -> real TCP 1 MiB; sender-selected ephemeral port; SHA-256 matched
.....................................................................Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
.................Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
.Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
..........................
Time: 18.797

OK (175 tests)


```

## Full Gradle / Android APK build

Attempted from this worktree:

```bash
bash gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64 clean :core:jvmTest :android:assembleDebug --console=plain --no-daemon
```

The wrapper could not download Gradle in this environment:

```text
Downloading https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
Exception in thread "main" java.net.SocketException: Network is unreachable
```

This is not a successful Gradle dual build or APK packaging result. Run the clean
Windows dual-build command on the owner's machine and retain its actual tail
(test results plus BUILD SUCCESSFUL) before committing. Do not report the step as
fully accepted from standalone compilation alone.

## Physical evidence still pending

No scanner advertisement capture, two-phone read/disconnect/reconnect, concurrent
connection rejection, negotiated-MTU result, code/filename agreement or Android
LAN file/hash result has been recorded. Follow MANUAL-TESTS.md and record models,
OS versions, date, capture/logs and results. iOS foreground service-only advertising
is a compatible discovery design, not an executed iOS test.

## Primary API references

- Apple CBPeripheralManager.startAdvertising supports service UUID/local-name keys:
  https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)
- Android legacy advertising limit (31 bytes):
  https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser
- Android MTU callback and Android 14+ 517-byte negotiation behavior:
  https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int)
