# Step 20 final remediation audit

Base: main **4fb3bd4** (Step 19). Audit date: **2026-10-16**.

**Status: local code/test audit passed; final acceptance is PENDING.**
The owner reported Step 19 BUILD SUCCESSFUL in 2m17s / 61 tasks. That precedes
the fixes below and is not a Step 20 build. No physical-device result was supplied.
Do not mark P3/P4/P5 hardware acceptance or P6-P10 completion from this report.

## Confirmed findings and fixes

| Finding | Change / evidence |
| --- | --- |
| TCP nonce differed from architecture §6: derived 12-byte base and 4-byte prefix+chunkIndex+counter | Restored derived 8-byte prefix+u32 global counter; index stays in AAD. Five Step20AuditTest regressions include literal nonce vector, all eight prefix bytes, counter/index separation, bounds and portable resume keys. Full real transfer/resume/NACK suites pass. |
| Android runtime did not inject DiskResumeStore; default was in-memory | Runtime now supplies app-private files/resume DiskResumeStore. Existing forced/atomic disk implementation is reused; JVM durable-store tests pass. Android filesystem/device execution pending. |
| commonMain in-memory resume key used JVM-only String.format missed by regex | Portable nibble-to-hex conversion; portability script also rejects .format calls. No native target/build is claimed. |
| MemoryFileSource/Sink only had test callers after Step 11 stub removal | Moved definitions into commonTest; no Android/common production caller remains. Existing real-IO tests compile/pass. |
| DECISIONS contained stale intermediate/current contradictions | Replaced with one current entry per Step 1-20, A1-A5 table and explicit pending statements. Old notes remain in git at 4fb3bd4. |
| iOS README described an existing app/framework task that does not exist | README and ios/README now distinguish current Android tap flow from future P6-P10; no iOS target/app is claimed. |
| Required per-step git history was not actually one commit per step | Actual log --stat captured unchanged in STEP20-COMMIT-HISTORY.md; Step 1 has three commits and Steps 2-6 share 3d18f95. No history rewrite/force push. |
| Build-only evidence could hide missing tests or absent device results | Owner validation script rejects empty/skipped/missing acceptance suites, checks build markers and merged manifest, captures XML/logs/APK hashes; device ledger remains PENDING. |

The nonce change restores the existing approved specification, without editing
ARCHITECTURE or introducing a new crypto scheme. Sender still allocates one
lifetime-monotonic counter across all files/workers/retries and fails before
overflow; key separation/AAD remain. **Install this APK on both peers**. Old
chunk crypto cannot interoperate; there is no old-nonce fallback. BLE message
tags, UUIDs, frame headers, dependencies and SDK versions are unchanged.

## Executed local verification

- Kotlin/serialization 2.2.0 compiled common/JVM production and tests using exact
  repository dependencies. **31 suites / 247 JUnit tests passed under -Xmx256m.**
- Includes RFC 7748 checks (shared with the Android runner), real state-machine
  guards/deadlines/cancel, GATT queues/fragments, encrypted control, disk IO,
  disk resume/crash fixtures, real TCP parallel/NACK/resume and large transfer.
- **1 GiB / four real TCP sockets**: 256 chunks, 1073741824 bytes, SHA-256 matched,
  heapMax=256 MiB, sampled heap peak=246 MiB. This is a JVM heap proof only.
- **256 MiB real TCP loss/reconnect**: dropped at 26/64=40.625%; second connection
  carried exactly 38 missing chunks / 159383552 plaintext bytes; SHA-256 matched.
- Both four-socket 48 MiB NACK modes pass with fresh retries and once-only Done.
- Three-file RangeFileSource/TCP tests pass for publication success/failure,
  duplicate display names and an empty file; Android provider/publication ports
  are test adapters, not physical SAF/MediaStore evidence.
- Android/Compose platform sources compiled against official API 35 and exact
  repo AndroidX/Compose versions after the runtime change. Legacy guarded API
  deprecations remain. This is source compilation, not Android APK packaging.
- commonMain portability scan (including .format), production memory-helper
  absence, independent source-manifest XML assertions, one decision entry per
  step, 16 pending-only CSV rows and git diff --check pass.
- Full clean Gradle/app/instrumentation-APK command attempted; wrapper download
  fails with **java.net.SocketException: Network is unreachable**. No local
  BUILD SUCCESSFUL, Gradle XML or Android APK is fabricated. PowerShell and adb
  are unavailable here; Windows script execution and hardware checks are pending.

Full standalone JUnit output (only progress dots omitted):

```text
JUnit version 4.13.2
Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=246; SHA-256 matched; seconds=10
Step14: authenticated BLE LINK_INFO -> real TCP 1 MiB; sender-selected ephemeral port; SHA-256 matched
Step16: encrypted WFD metadata -> real TCP 4 sockets / 8 MiB; SHA-256 matched; both terminal hooks closed groups (platform port stub, not radio evidence)
Step19: URI-style RangeFileSource -> auto-selected LAN / real TCP / 3 files including empty and duplicate names; publishFailure=false; grants released=3 (provider/publication ports, not Android device evidence)
Step19: URI-style RangeFileSource -> auto-selected LAN / real TCP / 3 files including empty and duplicate names; publishFailure=true; grants released=3 (provider/publication ports, not Android device evidence)
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
Time: 16.29
OK (247 tests)
```

## Evidence by step

| Steps | Implemented/current evidence | Still required |
| --- | --- | --- |
| 1-2 | Android IO wiring, portable common source and test-only memory helpers | Owner Step 20 clean APK build and Receive smoke |
| 3-4 | Shared crypto/RFC checks, bounds, spec frames/nonce and verify code | API 29 instrumentation |
| 5-7 | Real machines, guarded events, injected deadlines, PING/loss/terminal cleanup | Real radio/error cleanup observations |
| 8-10 | Forced atomic disk bitmap, 40.625% real resume, four sockets/NACK and 1 GiB/256 MiB proof | Android filesystem/radio behavior |
| 11-15 | Actual selector, typed/encrypted consent-bound BLE, GATT/MTU/UUID/privacy policies | Two phones: codes, 1 KiB MTUs, advertising timeout |
| 16-17 | Actual WFD/hotspot adapters and lease/group cleanup, platform-port tests | WFD >=15 MB/s on 5 GHz + resume; hotspot API 29/31/34 |
| 18-19 | Runtime/service/permissions, SAF URI IO, verified Downloads and bound LAN | Five-minute screen-off; MediaStore/SAF; two-phone LAN 1 GiB/hash |
| 20 | Current decisions/status, fixes, history, evidence script/device ledger | Owner clean build report plus all outstanding device evidence |

## Owner build and final acceptance

```powershell
& .\scripts\validate-step20.ps1 -TempDirectory D:\Temp
Get-Content .\build\reports\step20\OWNER-VALIDATION.md
```

The script runs clean :core:jvmTest :android:assembleDebug plus
:core:assembleDebugAndroidTest, captures the complete build log and final tail,
checks source/merged manifests, totals XML results and requires the large-transfer,
resume, parallel, RFC and Step 20 suites. It copies XML, log, git status/history
and app/instrumentation APK SHA-256 into build/reports/step20. Logging is staged
outside build so clean does not erase/lock its log; TEMP/TMP change only this
PowerShell process, not Windows user/system configuration. Build failure remains
failure and still produces an evidence report. There is no skip-build/force-green.

Then execute MANUAL-TESTS.md and fill STEP20-DEVICE-RESULTS.csv with actual dates,
models/APIs, commit/APK hashes and observations. API 29 crypto may use an emulator;
BLE/WFD/LAN acceptance needs real phones. No device fields or PASS values have
been invented. A green build leaves these rows pending. Step 20 cannot be marked
fully accepted until the required results are recorded and reviewed.

## Remaining scope and limitations

- P6-P10 (gestures, iOS, cross-platform transfer, share/group/BLE-only payloads)
  remain unimplemented. core has JVM/Android targets only; historical claims in
  commit titles do not override current code. Below-64-KiB BLE-only selection
  lacks a data implementation and is not counted as a working transfer path.
- Live connection-loss resume retains session keys/consent; process-death restore
  is not implemented. Android disk bitmap wiring does not imply cold-start resume.
- Process crash can leave private staging or a persisted URI grant; orphan sweeps
  are not implemented. Graceful terminal paths perform cleanup. Temporary-only
  provider access may disappear, and nonseekable retries can be slower.
- Hotspot downstream address discovery is conservative interface-snapshot logic;
  ambiguous/existing shared reservations fail rather than guess. OEM behavior,
  Android power management and throughput require the pending physical tests.
- Tracked gradle.properties has the owner's absolute Windows JDK path. It was
  retained for the established owner environment; other machines must configure
  their own JDK. Gradle 9 deprecations were not addressed by a version upgrade.
- The one-step/one-commit rule was historically missed for Steps 1-6, as recorded
  in the actual history. This patch does not rewrite already pushed commits.
