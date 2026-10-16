# Android redesign R1 — Trusted pairing

Base: `52c0b6a` (P6). R1 implements signed identities, owner-confirmed trust,
Forget/Reset and 60-second receiving from one selected trusted sender. R2 adds
preview/grab/catch consent UI; R3 is physical acceptance/fixes. Group remains P10.
**Every transfer still requires explicit Accept in R1.**

## Build

Place YoDrop-R1.patch in D:\YoDrop, then run PowerShell:

```powershell
cd D:\YoDrop
& {
    $ErrorActionPreference = "Stop"
    git apply --check .\YoDrop-R1.patch
    if ($LASTEXITCODE -ne 0) { throw "Patch check failed; do not force apply" }
    git apply .\YoDrop-R1.patch
    if ($LASTEXITCODE -ne 0) { throw "Patch apply failed" }
    & .\scripts\validate-r1.ps1 -TempDirectory D:\Temp
}
```

For later builds omit both git apply commands. The R1 script prepares/verifies
P6's cached model, runs the existing clean JVM/app/instrumentation-APK validation,
requires both new R1 test suites and the R1 real-TCP marker, and writes
build/reports/r1/OWNER-VALIDATION.md. It never marks physical tests passed.

## Local evidence — 2026-10-16

- Kotlin 2.2.0 core/common/JVM/Android-actual/test compilation: PASS.
- JUnit at 256 MiB heap: 34 suites / 313 tests, including 41 new R1 tests.
- Actual P-256 proof/role/transcript checks, strong pairing-code binding,
  tamper/replay/counter bounds, MTU 23/247 fragmentation, registry failure/restart,
  window expiry/revocation/reconnect races and legacy manual behavior: PASS.
- New authenticated identities + encrypted consent + LINK_INFO -> real TCP
  1 MiB with matching bytes/hash and no endpoint before Accept: PASS (loopback).
- Existing 1 GiB/four-socket, reconnect, streaming and consent regressions: PASS.
- Android/Compose source compile against API 35 and repository dependencies: PASS.
- Full Gradle/APK build here: BLOCKED by wrapper network fetch
  (`java.net.SocketException: Network is unreachable`). Owner build required.
- Android Keystore, physical BLE, lifecycle, persistent trust and device transfer:
  PENDING. JVM fixtures use JCA keys and test storage, not Android Keystore.

No dependency, plugin, SDK-level or transfer nonce version changes. R1 identities
use nonexportable Android Keystore signing keys; hardware backing depends on the
device. Registry data is encrypted/atomic/no-backup. See ARCHITECTURE and DECISIONS
for the exact signature, channel and first-pair code rules.

## First pairing on two phones

1. Install the new APK on both phones. Select a real file >=1 MiB on sender;
   receiver uses Receive -> Receive this file. Compare the ordinary transfer code.
2. Each screen shows an identity and a **Pairing code** of six groups of four
   hexadecimal characters. Compare the **entire pairing code on both phones**.
   The short four-digit transfer code alone is insufficient for trust enrollment.
3. On each phone choose Compare codes and trust device, check the full code in
   the confirmation and confirm only if it matches. Each owner pins separately.
   A name, HELLO ID or Bluetooth address never grants trust. A saved pin survives
   Deny; Accept is a separate decision. Trusting does not silently accept data.
4. Accept to test the transfer or Deny if only enrolling. Return Home, open
   Trusted devices, close/relaunch the app and require the same pinned device/key.
5. Receiver: Trusted devices -> selected sender -> Receive for 60s. Sender sends
   again. Claim within the normal ten-second OFFER window. Require verified
   trusted identity, then explicit Accept. Verify Done, Downloads/YoDrop and hash.

Identity is encrypted after CLAIM. An unrelated peer discovered first can cause
safe refusal/retry; R1 does not solve multi-peer selection or implement Group.
Both phones must support R1 for trusted behavior. Old peers retain manual Accept
and cannot use a trusted window or gain trusted status.

## Physical checks (all PENDING)

Record actual models/APIs, tested commit/APK hash, date and nonsensitive results.
Do not publish private keys, raw trust files, camera frames or personal payloads.

| Check | Actual model/API, observation and result |
| --- | --- |
| Full pairing codes match; each owner explicitly pins; ordinary Accept/Deny | PENDING |
| App restart/phone restart preserves identity and pins | PENDING |
| Same-name phone with another signing key remains unknown | PENDING |
| Selected sender only; wrong sender rejected before data | PENDING |
| Window closes at 60s; Claim near expiry cannot extend it | PENDING |
| Home/lock/rotation during waiting closes window; stale callbacks cannot reopen | PENDING |
| Already accepted data continues with existing foreground service | PENDING |
| Forget prevents trusted receiving; Reset requires pairing again; downloads kept | PENDING |
| Legacy peer manual transfer works, with no trust enrollment | PENDING |
| API 29 and another current API: Keystore sign/verify/storage and full transfer | PENDING |
| Existing P6 camera/tap/permission and Step 12-20 radio/background tests | PENDING |

Reset trusted pairing explicitly clears this app's identity and trust records,
not received Downloads. Missing/corrupt/inaccessible trust storage grants no trust;
normal manual flow may remain usable when signing keys still work. A permanently
invalid signing key requires explicit reset/re-pairing. Do not infer device PASS
from a successful build. Commit the phase after owner build is green and retain
pending physical checks for R3.
