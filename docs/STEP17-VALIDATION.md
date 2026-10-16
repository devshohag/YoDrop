# Step 17 validation

Base: main **3c7465d** (Step 16).

## Implemented

API 30+ reservation SoftApConfiguration; API 29 nullable WifiConfiguration only.
No fabricated SSID, gateway or port. Actual new downstream IPv4/prefix is selected
conservatively from before/after interface snapshots, excluding upstream/ineligible
addresses and rejecting ambiguity. Shared callback lease lifecycle handles null
config, timeout, cancel, stopped/failed callbacks and immediate late-lease disposal.

Sender HOTSPOT setup is consent-gated and uses its real ephemeral TCP listener.
Actual credentials/address go through encrypted LINK_INFO; the existing terminal
cleanup hook stops its reservation and closes resources. Unexpected hotspot stop
cancels the sender. Debug-only start/stop/join/reachability probe supports manual
API 29/31/34 checks without implementing an iOS receiver. No credentials in logs.

## Executed locally

- Kotlin/serialization 2.2.0 compiled common/JVM production/tests with exact repo
  dependencies. **28 suites / 209 tests passed under -Xmx256m.**
- 18 new Step17HotspotTest cases cover actual address/port propagation, upstream
  exclusion, ambiguity, invalid addresses, null/invalid configuration, cancel/close/
  timeout before callback, late and duplicate reservation disposal, delayed address
  assignment, missing address timeout, stop/failure notification, single start,
  invalid port, encrypted credentials/redaction, and real sender terminal cleanup
  hooks on cancel/reset and linking deadline (virtual time).
- Android/Compose sources compiled against official API 35, Kotlin/Compose 2.2.0
  and repository-version AndroidX. Guarded legacy API deprecation warnings remain.
- git diff --check and commonMain portability scan passed. Production hotspot code
  has no 192.168.43.1 gateway, fallback SSID or RequiresApi(O) annotation.

Complete standalone JUnit output:

```text
JUnit version 4.13.2
................Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=211; SHA-256 matched; seconds=10
..............................................Step14: authenticated BLE LINK_INFO -> real TCP 1 MiB; sender-selected ephemeral port; SHA-256 matched
........................................Step16: encrypted WFD metadata -> real TCP 4 sockets / 8 MiB; SHA-256 matched; both terminal hooks closed groups (platform port stub, not radio evidence)
...............................................................Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
.................Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
.Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
..........................
Time: 20.088

OK (209 tests)


```

## Full Gradle/APK result pending owner output

Attempted clean :core:jvmTest :android:assembleDebug using the Gradle wrapper and
local Java 17 override. Download failed before compilation in this environment:

```text
Downloading https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
Exception in thread "main" java.net.SocketException: Network is unreachable
```

This is not a successful Gradle/APK build. Retain the owner's clean Windows
build/test tail and BUILD SUCCESSFUL before committing.

## Device evidence pending and limits

No physical API 29/31/34 hotspot start/config/address/reachability or cleanup result
has been recorded. Use MANUAL-TESTS.md's explicit debug probe and record models,
OS versions, nonsensitive logs and outcomes. Do not publish join credentials.

The public LOHS reservation does not expose its downstream interface name; the
unique new-address inference is conservative and fails for ambiguous changes or
pre-existing shared hotspots rather than guessing. Real OEM behavior still needs
verification. Reservation.close releases this app's lease, not other apps' leases.
The hidden pending-request cancellation API is not used; a late onStarted closes
its reservation immediately. iOS joining and end-to-end Android-to-iOS acceptance
remain later work, and probe TCP reachability is not an encrypted file transfer.

## Primary API references

- https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotReservation
- https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotCallback
- https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspot(android.net.wifi.WifiManager.LocalOnlyHotspotCallback,android.os.Handler)
- https://android.googlesource.com/platform/packages/modules/Wifi/+/5a2af55aae06c7e10789fa639d4b312118777215/framework/java/android/net/wifi/WifiManager.java
