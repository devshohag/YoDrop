# Step 11 validation

Base: main 1320128 (`step10: verify 1 GiB TCP transfer with 256 MiB heap`).

## Implemented

Sender-side capability-aware selection with explicit BleOnly/DataLink/Unavailable
results, strict <64 KiB threshold, validated IPv4 CIDR comparison and active Android
network prefix lookup. Shared TCP adapter used by LAN connect/accept and Android's
Wi-Fi Direct TCP layer. Removed the placeholder README and cross-platform stubs.

`SenderStateMachine.selectTransport` uses the offered size and the received peer
HELLO. The Android entry point is `AndroidSenderTransportSelector.selectTransport`.
Its caller supplies local advertised capabilities, the peer HELLO and any known
peer IP; the adapter supplies the sender's active LAN address and prefix itself.
Unknown peer IP/prefix skips LAN; it never assumes /24.

Step 12 wires the BLE/MainActivity caller; Step 14 constructs encrypted LINK_INFO;
Step 16 implements the actual Wi-Fi Direct group. No device transfer or BLE-only
payload implementation is claimed by this selection change.

## Executed locally

Compiled common/JVM sources and all test sources with Kotlin 2.2.0, the repository
serialization compiler plugin and exact repository dependency versions. Ran all
22 JUnit suites in a JVM with **-Xmx256m**:

```text
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=248; SHA-256 matched; seconds=8
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
Time: 20.667
OK (111 tests)
```

Step11TransportTest covers /16 and /23 across /24 boundaries, different subnets,
missing metadata, malformed octets/prefixes, both peers' capabilities, Android to
iOS hotspot direction, iOS MPC/LAN priority, zero/65535/65536/65537-byte boundaries,
no-common-link, actual sender offered size/peer HELLO and unchanged sender state.
Its real TCP round-trip checks the shared adapter in both LAN connect and accept,
bidirectional frame integrity and local closure. The final connect/accept fixture
was also rerun with Phase5SelectorTests after replacing its raw server with the
production LanTransport listener.

The two changed/new Android transport glue sources separately compiled against
the official API 35 android.jar and compiled core classes. This is a type check,
not an APK build or a runtime test on Android. The metadata API references are:

- https://developer.android.com/reference/android/net/ConnectivityManager
- https://developer.android.com/reference/android/net/LinkProperties
- https://developer.android.com/reference/android/net/LinkAddress

commonMain portability scan and git diff --check passed. No new dependency.

## Windows dual build required

Attempted Gradle 8.14.3 clean :core:jvmTest :android:assembleDebug locally. Plugin
resolution fails before compilation (Kotlin multiplatform 2.2.0 unavailable from
configured repositories in this environment). No repository dependency or Java
home was changed. The standalone results do not establish APK build success.

The owner confirmed Step 10's Windows combined build succeeded after moving TEMP
to D:. Keep that session setting or set it again before Step 11 validation:

```powershell
New-Item -ItemType Directory -Path 'D:\Temp' -Force | Out-Null
$env:TEMP = 'D:\Temp'
$env:TMP = 'D:\Temp'
.\gradlew.bat --stop
& .\scripts\check-common-main.ps1
.\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain --no-daemon
```

The existing Step 10 test still needs roughly 2 GiB of free temporary disk. These
TEMP/TMP variables affect this PowerShell session and child processes only.

Step 11 remains pending Windows dual-build verification. No commit/push made.
After green, commit as `step11: select sender transport using peer capabilities and real subnet`.
Step 3 API 29 crypto instrumentation and later physical-device acceptance remain
pending and are not covered by these JVM/type checks.
