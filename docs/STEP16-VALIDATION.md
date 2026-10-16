# Step 16 validation

Base: main **78ac655** (Step 15).

## Implemented

Actual Android WifiP2pManager channel/broadcast/peer discovery, autonomous sender
owner-group creation, receiver SSID/passphrase-based joining, queried owner IPv4
and group metadata, and private credentials carried only in encrypted LINK_INFO.
Group setup starts after explicit ACCEPT. The group is nonpersistent; owner/peer
roles and group credentials are checked. Sender prefers 5 GHz where supported with
AUTO fallback for a platform ERROR; diagnostic frequency records actual operation.

Shared WifiDirectSession provides the resource lifecycle, 12-second setup timeout,
credential/role/address validation, cancellation and idempotent terminal close.
Android binds client sockets to the actual P2P interface and creates four real TCP
workers using the existing adapter/protocol. Recoverable loss retains session keys
and resume state, retries the same group and reconnects within the existing
20-second state-machine budget. The owner listener remains available until a
terminal state. Changed owner IP is rejected; a fresh session is then required.

Both sender/receiver cleanup hooks await resource teardown; receiver cleanup is
now suspend. Runtime Nearby Wi-Fi/fine-location requests and prerequisite checks
are wired. Cleanup stops discovery, cancels connecting, removes/rechecks owned
groups, unregisters its receiver and closes its channel. An unconfirmed platform
removal logs a fixed warning and is not counted as success. Existing unrelated
P2P groups are rejected rather than adopted/removed. No dependencies/minSdk changed.

## Verification executed here

- All common/JVM sources and tests compiled with Kotlin/serialization plugin 2.2.0
  and exact repository dependency versions.
- **27 suites / 191 tests passed under -Xmx256m**, including 16 new Step16 tests.
- New tests exercise real WifiDirectSession against a stub platform callback port:
  credential generation/bounds/redaction, actual endpoint propagation, sender-owner
  and receiver-client roles, existing/foreign/wrong-role groups, cancellation while
  creating, virtual setup deadline, concurrent setup rejection, same-group recovery,
  changed-IP rejection, authenticated encrypted credentials, and real state-machine
  terminal/cancel/linking-timeout cleanup with suspend cleanup awaited by reset.
- New integration uses real sender/receiver, authenticated encrypted WFD LINK_INFO
  and four real TCP sockets for 8 MiB; SHA-256 and both terminal cleanup hooks pass.
  Only the radio/group platform port is stubbed. This is not a real radio transfer.
- Existing 1 GiB / 256 MiB resume / multi-socket / crypto / BLE tests pass unchanged.
- Changed Android/Compose sources compiled against official API 35, Kotlin/Compose
  compiler 2.2.0 and repository-version AndroidX. Older guarded BLE APIs still warn.
- git diff --check and the commonMain portability scan passed.

Standalone complete JUnit output:

```text
JUnit version 4.13.2
................Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=209; SHA-256 matched; seconds=14
..............................................Step14: authenticated BLE LINK_INFO -> real TCP 1 MiB; sender-selected ephemeral port; SHA-256 matched
........................................Step16: encrypted WFD metadata -> real TCP 4 sockets / 8 MiB; SHA-256 matched; both terminal hooks closed groups (platform port stub, not radio evidence)
.............................................Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
.................Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
.Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
..........................
Time: 19.457

OK (191 tests)


```

## Full Gradle build still requires owner output

Attempted:

```bash
bash gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64 clean :core:jvmTest :android:assembleDebug --console=plain --no-daemon
```

The wrapper failed downloading Gradle in this environment:

```text
Downloading https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
Exception in thread "main" java.net.SocketException: Network is unreachable
```

This is not a successful Gradle/APK build. Run the clean Windows dual build and
retain its actual tests/BUILD SUCCESSFUL output before commit.

## Device acceptance pending

No physical Wi-Fi Direct group/role/cleanup test, API 29/32/33+ permission result,
actual 5 GHz >=15 MB/s 1 GiB transfer, or sender/receiver Wi-Fi-toggle resume has
been recorded. MANUAL-TESTS.md provides reproducible steps and pending result rows.
The app never enables Wi-Fi itself; manual recovery must restore the radio within
the existing deadline. Background/screen-off endurance belongs to Step 18.
P4 physical acceptance must not be marked complete based on JVM tests.

## Primary API references

- https://developer.android.com/develop/connectivity/wifi/wifi-direct
- https://developer.android.com/develop/connectivity/wifi/wifip2p
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pGroup
