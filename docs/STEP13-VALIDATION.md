# Step 13 validation

Base: main 69c6863 (Step 12).

## Implemented

Server CCCD subscription/read/write responses, connection/MTU/notification callbacks,
callback-paced GATT queue, service-ready advertising, subscription-before-HELLO,
per-peer actual MTU and reassembly. Removed fixed write/notify throttling. First
advertising peer/session is pinned; old scan/GATT callback generations cannot
acknowledge the replacement link. Disconnect retires control links and resets
activeGattClient for an explicit user retry.

Bounded fragments validate header fields, sizes, duplicate consistency and absolute
ten-second expiry. Debug intent options support an exact 1 KiB request/reply probe
and requested MTU 23/247. Probe data never becomes session LINK_INFO.

## Executed locally

Compiled all common/JVM production/test sources with Kotlin 2.2.0 and repository
serialization compiler/dependency versions. All **24 suites / 143 tests** passed
in one JVM with **-Xmx256m**, including 19 new GATT tests:

```text
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=241; SHA-256 matched; seconds=9
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
Time: 18.791
OK (143 tests)
```

Step13GattTest covers single-operation pacing, exact callback matching, callback
during start, immediate rejection, timeout, disconnect, cancellation before/after
start and retired queues. It also covers exact 1 KiB fragmentation at ATT MTU
23/247/517, value and fragment limits, invalid header fields, conflicting totals,
duplicates/out-of-order packets, bounded/expired partials, interleaved peers with
identical message IDs, disconnect state reset, discovery pinning and exact probe
request/reply sizes. Existing typed handshake and real TCP acceptance still pass.

Changed Android sources, existing MainScreen and selector glue compiled separately
with Kotlin/Compose compiler 2.2.0, official API 35 android.jar and repository-version
AndroidX/Compose dependencies. The old API 29-32 value APIs remain guarded branches;
API 33+ uses array-based calls. This is compilation, not an Android runtime result.
commonMain portability check, git diff --check and clean-base patch application
with changed-file byte comparison passed. No dependency/version changed.

## Required Windows/device validation

Gradle 8.14.3 clean :core:jvmTest :android:assembleDebug was attempted locally. Root
Kotlin multiplatform plugin 2.2.0 resolution still fails before compilation in this
environment. No repository Java home or dependency configuration was altered.

```powershell
New-Item -ItemType Directory -Path 'D:\Temp' -Force | Out-Null
$env:TEMP = 'D:\Temp'
$env:TMP = 'D:\Temp'
.\gradlew.bat --stop
& .\scripts\check-common-main.ps1
.\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain --no-daemon
```

TEMP/TMP affect this session/child processes only; Step 10 still needs about 2 GiB
temporary disk. No commit or push was made. After software dual-build verification:
`step13: pace GATT operations with callbacks and isolate peer fragments`.

Physical tests in docs/MANUAL-TESTS.md remain **PENDING**: exact 1 KiB request/reply
at actual MTU 23 and 247, device models/Android versions, and Step 12 matching codes.
The opt-in debug harness makes these checks executable; no physical PASS is claimed.
If Android 14+ negotiates 517, record it separately rather than treating it as 247.

Step 14 still owns encrypted, sender-created LINK_INFO and endpoint setup; later
steps own real Wi-Fi Direct/hotspot data, service lifecycle, throughput and iOS.
This change does not claim any of those acceptances. API 29 crypto execution from
Step 3 is still separately pending.

Platform API references used for callback/MTU behavior:
- https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback
- https://developer.android.com/reference/android/bluetooth/BluetoothGatt
