# Step 19 validation

Base: main **8a55f02** (Step 18). Owner approved A5 on 2026-10-16:
automatic **Downloads/YoDrop**, accessible from Files.

## Implemented

- One/multiple SAF document selection, streaming size/SHA-256 preparation, valid
  basenames, UUID IDs and direct URI-backed RangeFileSource; no sender cache copy.
- Shared chunk positioning/bounds for seekable and nonseekable providers, bounded
  reads, backward reopen, cancellation/close cleanup and owned URI grant release.
- Approved A5 destination: unique private DiskFileSink .part -> verified staging
  commit -> pending MediaStore Downloads/YoDrop copy -> visible published entry.
  Publication failure propagates without successful FILE_DONE/Done; terminal
  cleanup removes owned staging. No broad storage permission or legacy API branch.
- Existing consent-gated sender LAN selection uses fresh HELLO caps/IP and actual
  prefix. Android discovers eligible offline Wi-Fi even when mobile data is the
  default, binds the receiver socket to that network and retains bounded LAN
  reconnect. Fixed LAN-selection/publication logs support manual verification.

## Executed locally

- Kotlin/serialization 2.2.0 compiled common/JVM production/tests with exact repo
  dependencies. **30 suites / 242 tests passed under -Xmx256m.**
- **18 new Step19FilesTest cases**, including short/out-of-order reads, sequential
  pipe reads without re-reading prefixes, bounded skips/backward reopen, Long
  offsets above 2 GiB, range/allocation limits, zero-length files, truncation,
  zero-progress provider failure, real seek errors, cancellation/close/release,
  concurrent reads, protocol-safe names, real disk .part commit/publish ordering,
  idempotence, publication and staging failures, cancellation and cleanup.
- Real BleHandshakeController and sender/receiver machines exchange encoded HELLO,
  CLAIM, encrypted details and LINK_INFO, then auto-select LAN ahead of WFD and
  move three RangeFileSource files over real TCP (16 MiB + 3 bytes, empty, 3 bytes;
  identical display names). Hashes match and all owned source releases occur.
  A second real-machine run injects a publication-port failure and proves terminal
  receiver failure, no successful FILE_DONE, no sender Done and staging cleanup.
  Document/provider and publication ports are test adapters: **not Android SAF,
  MediaStore, radio, actual route-binding or two-phone evidence**.
- Android/Compose sources compiled against official API 35 with Kotlin/Compose
  2.2.0 and repository-version AndroidX, including the changed UI, storage and LAN
  adapters. Existing guarded deprecation warnings remain. No dependency changed.
- commonMain portability scan and git diff --check passed. Sender production code
  has no source cache copy/readBytes/readAllBytes path. No broad storage permission
  was added; allowBackup/connectedDevice declarations remain as Step 18.
- Full Gradle command attempted: wrapper cannot download Gradle 8.14.3 because
  java.net.SocketException: Network is unreachable. **APK/Gradle pass pending owner.**

Full standalone JUnit tail (progress dots omitted):

```text
JUnit version 4.13.2
Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=249; SHA-256 matched; seconds=10
Step14: authenticated BLE LINK_INFO -> real TCP 1 MiB; sender-selected ephemeral port; SHA-256 matched
Step16: encrypted WFD metadata -> real TCP 4 sockets / 8 MiB; SHA-256 matched; both terminal hooks closed groups (platform port stub, not radio evidence)
Step19: URI-style RangeFileSource -> auto-selected LAN / real TCP / 3 files including empty and duplicate names; publishFailure=false; grants released=3 (provider/publication ports, not Android device evidence)
Step19: URI-style RangeFileSource -> auto-selected LAN / real TCP / 3 files including empty and duplicate names; publishFailure=true; grants released=3 (provider/publication ports, not Android device evidence)
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
Time: 20.742
OK (242 tests)
```

## Pending acceptance

Owner clean :core:jvmTest + :android:assembleDebug, actual API 29/34 document
providers, pending/visible MediaStore entries, physical 1 GiB video and hash,
two phones automatically using LAN, offline Wi-Fi with mobile default, duplicate
names, error/cancel and LAN resume/service cleanup. See MANUAL-TESTS.md Step 19;
all device rows remain **PENDING**. Preparing hashes is Activity-scoped and may be
cancelled by recreation; transfer after source handoff is runtime/service-owned.
Temporary-only provider grants may become unavailable if their task disappears.
Process-death persistence/orphan sweeps, BLE-only <64 KiB data, share intents,
gestures and iOS remain outside this step.

Platform references:

- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/reference/android/content/res/AssetFileDescriptor
- https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
