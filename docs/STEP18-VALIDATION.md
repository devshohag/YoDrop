# Step 18 validation

Base: main **7aad03b** (Step 17).

## Implemented

Foreground connected-device service starts from visible Send/Receive actions,
uses START_NOT_STICKY and stops on terminal/Idle states. Runtime-owned real state
machines, handshake, BLE and transport scopes survive Activity recreation. A
notification opens the current session or cancels it; data/reconnect states hold
CPU/Wi-Fi locks and release them on terminal/service destruction. Existing receive
storage adapter uses application context. Process death is not resume persistence.

SDK-specific required permissions and optional notifications are separate. API
31/32 location requests include fine+coarse together. Notification denial does not
block transfer. System Bluetooth enable/Wi-Fi panel requests never toggle radios;
radio loss alerts/cancels pairing or follows existing data reconnect semantics.
allowBackup=false and an XML manifest audit script are included. No new dependency.

## Executed locally

- Kotlin/serialization 2.2.0 compiled common/JVM production and tests with exact
  repository dependencies. **29 suites / 224 tests passed under -Xmx256m.**
- **15 new Step18ForegroundTest cases**: all sender/receiver service/power states,
  active-other-role behavior, API 29-35 permission cutovers, required denial,
  optional notification denial, idempotent start/stop, rejected start retry and no
  state-observer background start. Real state machines cover offer/claim expiry,
  cancel/reset/restart, encrypted handshake and transfer completion, checksum
  failure, recoverable socket loss through reconnect expiry and UI observer
  cancellation without cancelling the runtime owner. The service port is a stub;
  these tests do not execute Android services/radios/locks.
- Changed Android/Compose sources compiled against official API 35 with Kotlin/
  Compose 2.2.0 and repository-version AndroidX. Existing guarded deprecations remain.
- commonMain portability regex and git diff --check passed. Source manifest XML
  independently checked for all audit assertions. PowerShell is unavailable here;
  the actual check-android-manifest.ps1 must run on the owner's Windows machine.
- Full Gradle command attempted, but wrapper download of Gradle 8.14.3 fails with
  java.net.SocketException: Network is unreachable. **No APK/Gradle pass claimed.**

Standalone full JUnit output:

```text
JUnit version 4.13.2
Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=216; SHA-256 matched; seconds=13
Step14: authenticated BLE LINK_INFO -> real TCP 1 MiB; sender-selected ephemeral port; SHA-256 matched
Step16: encrypted WFD metadata -> real TCP 4 sockets / 8 MiB; SHA-256 matched; both terminal hooks closed groups (platform port stub, not radio evidence)
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
Time: 23.611
OK (224 tests)
```

## Owner validation still required

Run source manifest audit and commonMain check, then clean :core:jvmTest and
:android:assembleDebug. Audit the merged manifest as well; the script accepts
-ManifestPath (see MANUAL-TESTS.md). Follow the Step 18 physical table on API
29/31/34 for five-minute screen-off transfers, Activity recreation, notification
cancel/denial, required permission denial, radio enable/cancel and terminal service/
lock cleanup. All those physical rows are **PENDING**. No hardware throughput,
doze, five-minute background or service-lifecycle claim follows from JUnit.

Platform references:

- https://developer.android.com/develop/background-work/services/fgs/launch
- https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://developer.android.com/develop/sensors-and-location/location/permissions/runtime
- https://developer.android.com/develop/background-work/background-tasks/awake
