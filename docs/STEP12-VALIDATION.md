# Step 12 validation

Base: main c9006c9 (Step 11).

## Implemented

Typed, bounded BLE CBOR codec with a one-byte discriminator; required 16-byte HELLO
deviceId; both-way HELLO and full fragmented GATT OFFER. Shared peer-bound handshake
controller and MainActivity advertising/scanning/message collectors. Tap-to-claim,
matching verification-code prompts, ACCEPT/DENY and explicit pending link setup.
The fake receiver localhost endpoint is removed. Step 11 sender-side selection
runs after CLAIM using the actual peer HELLO and sender offered size.

## Executed locally

Kotlin 2.2.0 compilation of all common/JVM and test sources, with repository
serialization plugin and exact dependency versions. All **23 suites / 124 tests**
passed under **-Xmx256m**, including the 13 new Step12BleTest checks:

```text
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=231; SHA-256 matched; seconds=9
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
Time: 22.916
OK (124 tests)
```

New tests cover all six wire tags/round trips, HELLO identity validation, wrong
types/decision flags, malformed/unknown/oversized messages, fragmented full Unicode
OFFER and integrated discovery/claim/accept/deny through the real core machines.
The integration fixture serializes and fragments every exchanged control message,
checks matching four-digit codes, no implicit CLAIM/ACCEPT, first-peer ownership,
wrong-peer rejection and LINK_INFO allowed exactly once after consent. It uses an
in-memory BLE carrier; it does not simulate Android GATT success.

All changed Android sources (MainActivity, MainScreen, AndroidBleManager) plus the
selector glue separately compiled with Kotlin/Compose compiler 2.2.0, official
API 35 android.jar and the repository-version AndroidX/Compose dependencies. The
Compose BOM resolved to 1.7.5 UI/runtime and 1.3.1 Material3. No dependency versions
were changed in the repository. This type/compiler check is not an APK build.

commonMain portability scan and git diff --check passed. Clean-base patch apply
and byte comparison with the validated working tree passed.

## Full Gradle and physical acceptance pending

Attempted Gradle 8.14.3 clean :core:jvmTest :android:assembleDebug. This environment
still fails resolving the root Kotlin multiplatform 2.2.0 plugin before compilation.
Standalone compilation/tests do not establish Gradle/Android packaging success.
No commit or push was made.

```powershell
New-Item -ItemType Directory -Path 'D:\Temp' -Force | Out-Null
$env:TEMP = 'D:\Temp'
$env:TMP = 'D:\Temp'
.\gradlew.bat --stop
& .\scripts\check-common-main.ps1
.\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain --no-daemon
```

Physical-phone matching-code acceptance is **not executed**. Record both models,
Android versions and actual outcomes in docs/MANUAL-TESTS.md. Step 13's known CCCD,
GATT queue, MTU and fragment-isolation work remains necessary for reliable device
notification delivery; rerun Step 12's checklist after those fixes. A successful
Windows build alone must not be reported as physical BLE pairing success.

Step 14 still supplies encrypted, sender-owned LINK_INFO and endpoint creation;
Step 16/17 actual data-link setup remains pending. ACCEPT currently enters pending
link setup and can reach the unchanged 20-second timeout. This patch does not claim
Android bulk transfer, BLE-only small-payload data, hotspot credential encryption
or throughput. Both phone APKs must update together for the new BLE framing.

After software dual-build verification, commit as:
`step12: frame BLE control messages and wire tap handshake flow`.
