# Android redesign R2 — Preview, Grab and Catch

Base: `c732663` (R1). Apply `YoDrop-R2.patch` once to that checkout. Both phones
should install the rebuilt app. This is Phase 2 of the three Android redesign
phases, before P7-P10; group receiving is still P10.

## Owner build

Save the patch in `D:\YoDrop`, then run:

```powershell
cd D:\YoDrop
& {
    $ErrorActionPreference = "Stop"
    git apply --check .\YoDrop-R2.patch
    if ($LASTEXITCODE -ne 0) { throw "Patch check failed" }
    git apply .\YoDrop-R2.patch
    if ($LASTEXITCODE -ne 0) { throw "Patch apply failed" }
    & .\scripts\validate-r2.ps1 -TempDirectory D:\Temp
}
```

The script reuses all R1/Step20 checks: pinned hand model, source portability,
clean JVM tests, Android debug + crypto instrumentation APK, merged manifest audit,
test counts, logs and APK hashes. It additionally requires all 18 R2 tests and
the Catch real-TCP marker. Report: `build/reports/r2/OWNER-VALIDATION.md`.
Only the current PowerShell session's TEMP/TMP changes to D:\Temp.

APK: `android/build/outputs/apk/debug/android-debug.apk`. A build PASS is separate
from a device PASS. The report does not mark any camera/radio/UI test as complete.

## User flow

First pairing (manual consent):

1. Sender: Select files -> inspect preview -> palm then fist, or tap Grab files.
2. Receiver: Receive from a new device -> Review this offer (or gesture to review).
3. Both: compare the full pairing code in the identity panel and confirm trust
   separately on each phone. The four-digit transfer code alone is not enrollment.
4. Receiver: Accept files after comparing transfer codes. Saving trust does not
   replace this first manual acceptance; Deny can also finish enrollment without data.

Returning trusted Catch:

1. Sender: Select files -> preview -> Grab.
2. Receiver: choose that sender's **Get ready to Catch** on the home screen.
   Resolve optional camera and required radio prompts; this arms a foreground
   receive window for 60 seconds. Camera denial still permits the button flow.
3. App reads/reconnects/CLAIMs automatically to check the sender's pinned key and
   decrypt file details. This alone cannot create a data endpoint or accept files.
4. At **Ready to Catch**, check the sender and file preview. Hold a fist briefly,
   then open the palm, or tap Catch files. This is explicit consent; no extra Accept.
5. Transfer progress -> hash verification -> Caught and saved in Downloads/YoDrop.

Unselected trusted peers, unknown devices and legacy peers keep manual Accept.
A same-name device with a changed signing key never inherits trust. A wrong device
discovered first causes safe refusal/retry, since public UUID-only discovery does
not reveal identities and one GATT connection remains the limit.

## Local evidence

- Kotlin 2.2 source compilation of common/JVM code and all JVM test sources: PASS.
- Full JVM regression run at 256 MiB heap: **331 tests, 35 suites**, including 18
  R2 tests; no failures. The modified storage-failure case was rerun after refinement.
- R2 real TCP 1 MiB: signed selected identity -> fresh Catch -> encrypted consent ->
  encrypted LINK_INFO -> TCP -> final matching SHA-256, with no endpoint before Catch.
- Existing real TCP 1 GiB/four-socket, 256 MiB reconnect/resume and R1 crypto tests pass.
- All changed Android/Compose sources compiled against API 35 and existing pinned
  AndroidX/CameraX/MediaPipe dependencies: PASS. No dependency/version change.
- Gradle wrapper attempt blocked downloading its distribution (`Network is unreachable`).
  Full Gradle/APK validation must be run by the owner; it is not claimed locally.
- Patch application and common-source portability are checked before delivery.

## R3 physical acceptance — all pending

| Check | Expected behavior | Result |
| --- | --- | --- |
| First pair on two phones | Same full pairing code; trust separately; manual Accept | PENDING |
| Returning trusted Catch | Exact selected identity, encrypted preview, one deliberate Catch starts data | PENDING |
| No camera permission / camera unavailable | All Grab, review, Accept, Catch and cancel buttons work | PENDING |
| Camera on/off and hand movement | Compact preview, fresh stable fist->palm only after Ready; no false acceptance | PENDING |
| Poses before authentication / multiple hands | Cannot prequeue or trigger Catch | PENDING |
| 60 s expiry / close / app background | Waiting window closes; queued Catch rejected; new window required | PENDING |
| Permission dialogs / delayed discovery | No window reopened while paused; permissions resolved before arming | PENDING |
| Rotate / recreate before Grab or Catch | Staged selection released; no restored receive window/gesture consent | PENDING |
| Already accepted transfer and rotation/background | Existing service/resume ownership continues correctly | PENDING |
| Wrong sender / same name / changed key / Forget | No selected Catch authorization or endpoint | PENDING |
| Cancel / Deny / retry | No leaked source grants, camera or BLE connections; fresh session needed | PENDING |
| Large font / small screen / landscape | Scrollable layout; names/code readable; controls reachable | PENDING |
| Images/video/documents/multi-file preview | Provider thumbnail when supported, metadata fallback, no pre-Grab advertisement | PENDING |
| Real Wi-Fi Direct and same-LAN transfer | Verified downloads, progress, terminal feedback, reconnect as previously specified | PENDING |

Use files/batches >=64 KiB for current transport testing. The existing smaller-
payload BLE data path is still P10; R2 does not implement it. Sender thumbnails are
local only; the receiver reads only the existing encrypted filename/batch summary
before consent. The new art/animation conveys waiting/progress/completion and
does not create a spatial beam, pointing detection or camera-to-camera channel.
