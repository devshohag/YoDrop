# YoDrop

Offline file-transfer project. Current implementation is Android tap-based BLE
pairing with encrypted TCP over LAN/Wi-Fi Direct and automatic received downloads
in **Downloads/YoDrop**. Physical radio, throughput and background acceptance is
still pending; a successful build is not proof of a working two-phone transfer.

Gesture control, the iOS app, Android-to-iOS transfer, Share Sheet/group mode and
BLE-only small-payload data are future P6-P10 work. `ios/` is a placeholder, and
core currently configures JVM/Android targets only. Batches below 64 KiB select
the planned BLE-only path; that data path is not implemented yet.

See [architecture](docs/ARCHITECTURE.md), [current decisions](docs/DECISIONS.md),
[Step 20 audit](docs/STEP20-AUDIT.md), and [device checklist](docs/MANUAL-TESTS.md).

Windows validation (JDK 17 + Android SDK required):

```powershell
& .\scripts\validate-step20.ps1 -TempDirectory D:\Temp
```

This runs source/merged manifest checks, a clean core test + Android debug build
and assembles the API 29 crypto instrumentation APK. Logs, test totals and APK
hashes are written under `build/reports/step20/`. It does not claim device tests.
Install the new app APK on **both peers**: Step 20 restores the architecture's
8-byte-prefix TCP chunk nonce layout and is incompatible with older chunk crypto.

The tracked gradle.properties currently contains the owner's Windows JDK path;
other machines must supply a valid local JDK path/JAVA_HOME. No Gradle/dependency
versions were upgraded during remediation. Historical commits claiming P6-P9
completion do not establish those implementations.
