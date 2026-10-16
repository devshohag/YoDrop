# P6 — Android gestures

Implementation based on `0ad7b94`. Code and local checks are ready for the owner
build. **P6 physical acceptance is PENDING.** Earlier Step 20 physical checks
remain pending too. A build or synthetic landmark test cannot establish the
architecture's >=95% normal-light detection criterion.

## Implemented behavior

- **Send Files** starts the existing tap flow; it never asks for camera.
- **Send with gesture** selects/prepares documents, then stages them. Show a
  whole open palm, hold briefly, then close into a fist. Only this stable edge
  starts Grabbed/advertising. **Send now** and Cancel remain available.
- **Receive -> Use hand gesture** opens the camera. Once an OFFER arrives, hold
  a fist then open the palm within the existing ten-second window to CLAIM.
  Scanning gestures do nothing. **Receive this file** remains the tap fallback.
- After CLAIM/ECDH, encrypted preview and matching codes lead to the original
  **Accept/Deny** prompt. Gestures never accept a transfer or create an endpoint.
- **Try gestures (one phone)** only shows detection and Grab/Release counters.
  No peer, file, BLE permission, Bluetooth, Wi-Fi or Internet is required.
- Camera permission is optional. Denial, permanent denial, no camera, unsupported
  native code and model/camera failure leave tap controls available.
- Camera/model stop on pause, navigation away and disposal. A fresh sequence is
  required on return. Staged files are released on Cancel/activity destruction;
  rotate before Grab and reselect. After Grab, runtime transfer ownership survives
  Activity recreation. No camera is held by the transfer service.
- Grab/Claim vibrate; Done adds double haptic and a short notification tone.
  Haptic disabled, silent/vibrate mode, muted notification audio and DND are
  respected. Feedback failures cannot fail the transfer.

## Build on Windows

Place `YoDrop-P6.patch` in the project root. The first model preparation needs
Internet on the **PC**; the installed app uses its packaged model offline.
The model is 7.5 MiB and is intentionally excluded from the patch/git. Its
SHA-256 is checked during preparation and again by Gradle before packaging.
`clean` preserves both the asset and its cache outside build/.

```powershell
& {
    $ErrorActionPreference = "Stop"
    git apply --check .\YoDrop-P6.patch
    if ($LASTEXITCODE -ne 0) { throw "Patch check failed; do not force apply" }
    git apply .\YoDrop-P6.patch
    if ($LASTEXITCODE -ne 0) { throw "Patch apply failed" }
    New-Item -ItemType Directory -Path D:\Temp -Force | Out-Null
    $env:TEMP = "D:\Temp"
    $env:TMP = "D:\Temp"
    & .\scripts\prepare-p6-model.ps1
    & .\scripts\check-common-main.ps1
    & .\scripts\check-android-manifest.ps1
    .\gradlew.bat --stop
    if ($LASTEXITCODE -ne 0) { throw "Gradle stop failed" }
    .\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}
```

For subsequent builds, omit both git apply commands. The existing Step 20
validation script also works **after model preparation**. Require the build's
`MediaPipe local-only logger verified; remote metrics classes removed` message.
The AAR transform is applied before compile/package for debug and release; it
does not depend on reflection or on disabling logging after initialization.

## Local evidence (2026-10-16)

| Check | Result and limit |
| --- | --- |
| Kotlin 2.2.0 compile: core common/JVM/Android actuals and tests | PASS, repository compiler and serialization plugin |
| JUnit, 256 MiB heap | PASS: 32 suites / 272 tests, including 25 P6 tests |
| Existing real TCP tests | PASS: four-socket 1 GiB/hash and 256 MiB reconnect; loopback evidence only |
| Android/Compose source compilation | PASS against API 35, pinned CameraX/MediaPipe and repository AndroidX/Compose versions, including transformed SDK |
| Exact privacy transform + verification task | PASS using Gradle 8.14.3, isolated built-in-plugin project and official cached AAR; this is not an APK build |
| Transformed SDK factory on JVM (`-Xverify:all`) | PASS: returns TasksStatsDummyLogger; session/input/end operations run without Android Context/network |
| Source portability/manifest and patch checks | PASS by local equivalent source checks; PowerShell scripts still require owner execution |
| Full Gradle JVM + APK build | BLOCKED here: wrapper distribution fetch reports `java.net.SocketException: Network is unreachable`; owner build required |
| Physical camera accuracy, lifecycle and feedback | PENDING; no phone/emulator is connected here |

P6 tests exercise pose geometry for both hands/3D rotation/scale, incomplete and
nonfinite data, partial fingers/thumbs-up, debounce, jitter, gaps, ambiguity,
timestamps, once-per-token commands, context/role changes, cooldown, pause and
real session/controller consent/expiry guards. They do not measure ML accuracy.

## One-phone acceptance

Install `android/build/outputs/apk/debug/android-debug.apk`. Record model, Android
version/API, tested commit/APK hash, lighting, date and actual counts below.
Use ordinary room/daylight with the whole hand visible, initially about 30–60 cm
from the front camera. Keep the hand separate from faces/objects. Hold each pose
for about one second; practice commands have a 700 ms cooldown. Only one hand
may be visible. A phone without a front camera uses its back camera.

1. Fresh install: use Send Files/Receive and confirm they never request camera.
   Try gestures -> deny camera; tap controls must remain usable. Retry, grant
   camera, then test Settings revocation/permanent denial and return.
2. Turn on airplane mode with Bluetooth/Wi-Fi off. Try gestures must still open
   and increment counters without radio prompts or network. This tests gestures
   only, not same-phone transfer.
3. For each hand, make 20 palm->fist sequences and 20 fist->palm sequences. Count
   intended edges and emitted events separately; require at least 19/20 correct
   for **each direction and hand**. Confirm Hand text matches each held pose.
   Repeat at another ordinary lighting/distance. Log failures; do not average
   away a failing hand/direction. This is a smoke threshold, not population proof.
4. Observe two minutes with no hand, normal unrelated motion, pointing, V sign
   and two hands. Require zero unintended Grab/Release events in this smoke test.
   Lose tracking between the start and end poses: no command may bridge the gap.
5. Hold a palm, press Home/lock, return showing only a fist: no Grab. Repeat with
   rotation, rapid practice close/reopen and another app using the camera. Camera
   indicator should turn off when YoDrop pauses/closes practice; errors must
   leave the buttons usable. Rotation may recreate/reset the practice counters.
6. Send with gesture -> choose a file >=64 KiB. No advertising before Grab or
   Send now. Deny camera and use Send now; Cancel before Grab must discard the
   staged batch. Rotate before Grab: reselect, with no unexpected advertisement.
   After Grab, repeat the ordinary Step 18 lifecycle checks when a peer exists.
7. Verify Grab/practice haptic with system haptics enabled and disabled. A real
   Done tone/haptic requires the two-phone test below; practice is not fake Done.

| One-phone check | Model/API, date, APK, actual count/result |
| --- | --- |
| Tap without camera; denial/permanent denial/revocation | PENDING |
| Practice with airplane mode, radios off | PENDING |
| Left: Grab /20 and Release /20, light/distance | PENDING |
| Right: Grab /20 and Release /20, light/distance | PENDING |
| Second normal-light/distance result | PENDING |
| Unintended events during two-minute negative exercise | PENDING |
| Pause/lock/rotate/close/reopen: no camera leak/stale action | PENDING |
| Staged-file Cancel/rotation, Send now fallback, Grab haptic | PENDING |

## Two-phone acceptance when available

Install the same new APK on both phones; use a >=1 MiB file. Sender uses Send with
gesture; receiver taps Receive and enables hand gesture while scanning. Grab
must advertise once. Release must CLAIM only the currently displayed offer and
within ten seconds. Compare codes; wait without tapping Accept: no endpoint/data
may start. Then tap Accept, require Done on both, matching file hash and a download
in Downloads/YoDrop. Confirm Done feedback once, with no duplicate after rotation.
Repeat Deny, cancellation, claim timeout, tap-only operation and camera denial.
Record normal/silent/DND feedback behavior and the existing radio/background tests
in MANUAL-TESTS.md. One phone cannot satisfy these rows.

| Two-phone check | Sender/receiver model/API, evidence/result |
| --- | --- |
| Gesture Grab -> one advertisement; Release -> current CLAIM | PENDING |
| Explicit Accept/Deny and ten-second expiry preserved | PENDING |
| File Done/Downloads/hash plus Done haptic/tone once | PENDING |
| Camera denied/tap fallback and existing transfer regressions | PENDING |

Do not mark P6 done solely from the build. Commit this phase after the owner
build is green; retain all unexecuted physical results as PENDING.
