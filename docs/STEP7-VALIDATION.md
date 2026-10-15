# Step 7 validation

Base: main commit 3d18f95b7cbf553fe1ecf387f8deb6fbcb96dcdb.

Implemented remediation Step 7 (session lifecycle), not architecture P7 (iOS).

## Executed

- Compiled commonMain + jvmAndAndroidMain + jvmMain and all commonTest,
  jvmTest and x25519Test sources with Kotlin 2.2.0, its serialization plugin,
  and the exact dependency versions in gradle/libs.versions.toml.
- Ran all 19 JVM test suites with JUnit 4.13.2:

```text
Suites: 19
JUnit version 4.13.2
..........................................................................................
Time: 2.289

OK (90 tests)
```

- The 14 new Step7LifecycleTest tests exercise real sender/receiver machines:
  40-second virtual-time verification with PING from both sides; actual drop and
  reattachment requesting only the missing chunk; cancel reasons both directions;
  cancellation while verifying; idempotent hooks; scope cancellation before/after
  worker startup; idle loss plus expiry; partial/bitmap deletion on terminal cancel;
  terminal checksum/auth/protocol errors; resumable event policy; disk discard;
  and cancellation unblocking a real TCP socket read.
- commonMain portability regex returned no matches.
- git diff --check passed.

## Full Gradle dual build remains pending

Attempted clean :core:jvmTest :android:assembleDebug with Gradle 8.14.3.
The repository's Windows-only org.gradle.java.home was overridden for the Linux
verification command without changing gradle.properties. Dependency resolution
then failed before project compilation:

```text
Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.2.0', apply: false]
was not found ... could not resolve plugin artifact ...
BUILD FAILED
```

Standalone JVM tests are not evidence of an Android assembleDebug success.
No Step 7 commit or remote push was made. Run on the owner's configured Windows PC:

```powershell
& .\scripts\check-common-main.ps1
& .\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain
```

Only after that command is green, commit this step as:
`step7: preserve reconnectable sessions and add liveness cleanup`.

API 29 crypto instrumentation is still pending from Step 3. Atomic disk resume,
large kill/reconnect acceptance and real parallel receiver remain Steps 8 and 9.


## Owner verification received after the Step 7 patch

The owner ran the combined clean JVM-test + Android-assemble command on Windows:

```text
BUILD SUCCESSFUL in 1m 30s
61 actionable tasks: 61 executed
```

This resolves the Step 7 dual-build pending item above. The owner then pushed
Step 7 as cbd514134c469ec456b3dbc550a9233972058dec before Step 8 began.
