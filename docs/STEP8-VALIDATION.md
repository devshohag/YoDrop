# Step 8 validation

Base: main commit cbd514134c469ec456b3dbc550a9233972058dec.

## Executed

Compiled all common/JVM production and test sources using Kotlin 2.2.0, its
serialization compiler plugin, and the repository's exact dependency versions.
Ran all 20 suites using JUnit 4.13.2 with -Xmx512m:

```text
Suites: 20
JUnit version 4.13.2
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Time: 11.374

OK (97 tests)
```

Seven new tests cover:

- Real TCP transfer of 256 MiB, socket kill at 40.625%, same-session re-AUTH,
  exact missing-chunk and plaintext-byte counters, and final disk SHA-256.
- Real TCP recovery from both truncated and missing .part files with stale bits.
- Concurrent readers seeing only complete old/new atomically replaced bitmaps.
- Rejection of truncated and oversized persisted bitmap records.
- FileSink.force completing before received bits reach ResumeStore.saveBitmap.
- Terminal checksum failure deleting disk state even when FILE_DONE send fails.
- Retained disk sink recovery after .part deletion; close after commit does not
  recreate a .part file.

commonMain portability regex returned no matches. git diff --check passed.

## Full Gradle dual build pending

Attempted clean :core:jvmTest :android:assembleDebug with Gradle 8.14.3, overriding
only the Windows Java-home setting for this Linux invocation. Plugin resolution
failed before compilation:

```text
Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.2.0', apply: false]
was not found ... could not resolve plugin artifact ...
BUILD FAILED in 14s
```

Standalone JVM execution does not prove Android assembleDebug success.
No Step 8 commit or remote push was made. Run on the owner's configured PC:

```powershell
& .\scripts\check-common-main.ps1
& .\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain
```

After the dual build is green, commit as:
`step8: persist resume state atomically and verify real TCP recovery`.

Step 3 API 29 instrumentation remains pending; Steps 9/10 acceptance is not
claimed. This run used a 512 MiB heap and does not claim the Step 10 memory proof.


## Windows follow-up

The owner's initial Gradle run executed 97 tests: 96 passed; the concurrent
raw-stream snapshot test failed with FileNotFoundException. The 256 MiB real-TCP
acceptance test passed on Windows. The correction shares a canonical-directory
reader/writer Mutex across store instances and loads snapshots through a single
bounded NIO channel. The concurrency test now calls that production loader from
an independent store instance and retains its exact old/new bytes assertion; both
valid snapshots have received bits, so a missing-record empty result fails.
A new Windows dual-build result is still required after applying the correction.


## Owner verification after the Windows correction

The owner ran the complete clean JVM-test + Android-assemble command on Windows.
All 97 JVM tests passed, including the concurrent production-loader test and the
256 MiB real TCP recovery test. Output:

```text
BUILD SUCCESSFUL in 56s
61 actionable tasks: 61 executed
```

This resolves the Step 8 dual-build pending item above. Step 8 was pushed as
c25a292 before Step 9 began.
