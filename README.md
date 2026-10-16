# YoDrop

Offline file-transfer project. Current implementation is Android tap/gesture BLE
pairing with encrypted TCP over LAN/Wi-Fi Direct and automatic received downloads
in **Downloads/YoDrop**. Physical radio, throughput and background acceptance is
still pending; a successful build is not proof of a working two-phone transfer.

P6 adds Android camera gestures, optional camera permission, tap fallback, haptic/
sound feedback and a one-phone practice screen. Its physical detection/false-trigger
acceptance is still pending; see [P6 validation](docs/P6-VALIDATION.md).

Android redesign **R1** adds signed device identities, owner-confirmed trust and
60-second receiving from one selected trusted sender. Initial trust compares a
full pairing code on both phones; every transfer still needs Accept in R1.
See [R1 validation](docs/R1-VALIDATION.md). The preview/grab/catch consent UI is R2,
and two-phone acceptance/fixes are R3. Group mode remains P10.

The iOS app, Android-to-iOS transfer, Share Sheet/group mode and BLE-only small-
payload data are future P7-P10 work. `ios/` is a placeholder, and
core currently configures JVM/Android targets only. Batches below 64 KiB select
the planned BLE-only path; that data path is not implemented yet.

See [architecture](docs/ARCHITECTURE.md), [current decisions](docs/DECISIONS.md),
[Step 20 audit](docs/STEP20-AUDIT.md), and [device checklist](docs/MANUAL-TESTS.md).

Windows validation (JDK 17 + Android SDK required):

```powershell
& .\scripts\validate-r1.ps1 -TempDirectory D:\Temp
```

Model preparation downloads the pinned 7.5 MiB official hand model once, verifies
SHA-256 and caches it outside build/. Gradle verifies it again before packaging.
The installed app performs gestures offline. The build also binds MediaPipe to
its bundled no-op logger, strips remote logging classes and excludes their upload
dependencies; details and modifications are in [third-party notes](docs/P6-THIRD-PARTY.md).

The validation script runs source/merged manifest checks, a clean core test + Android debug build
and assembles the API 29 crypto instrumentation APK. Logs, test totals and APK
hashes are retained under `build/reports/step20/`; the R1 summary is under
`build/reports/r1/`. Neither report claims device tests.
Install the new app APK on **both peers**: Step 20 restores the architecture's
8-byte-prefix TCP chunk nonce layout and is incompatible with older chunk crypto.

The tracked gradle.properties currently contains the owner's Windows JDK path;
other machines must supply a valid local JDK path/JAVA_HOME. P6 adds pinned CameraX 1.4.2 and MediaPipe Tasks 0.10.32; existing
Gradle/dependency versions and SDK levels stay unchanged. Historical commits
claiming P6-P9 completion do not establish their device acceptance.
