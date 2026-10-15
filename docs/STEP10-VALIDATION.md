# Step 10 validation

Base: main commit c8fe3a7 (Step 9).

## Executed

Compiled all common/JVM production and test sources with Kotlin 2.2.0 and the
serialization plugin, using exact repository dependency versions. Ran all 21
JUnit suites in one test JVM launched with **-Xmx256m**:

```text
Suites: 21
JUnit version 4.13.2
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Step10 TCP: 1 GiB; sockets=4; chunks=256; bytes=1073741824; heapMaxMiB=256; sampledHeapPeakMiB=248; SHA-256 matched; seconds=8
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
Time: 20.286

OK (101 tests)
```

The new test asserts the actual JVM maximum heap is <= 256 MiB, uses the real
sender/receiver on four real TCP connections and disk sources/sinks, streams
exactly 1 GiB, checks every chunk index/byte count and compares the final SHA-256.
No OutOfMemoryError occurred. Native/process RSS memory is outside this heap cap.
Sampled heap usage is diagnostic; the actual JVM maximum is the enforced limit.

All earlier tests passed under the same -Xmx256m. git diff --check passed.
The commonMain portability check found no matches (production common code did
not change in Step 10). No dependency, protocol or platform implementation changed.

## Full Gradle dual build pending

Attempted clean :core:jvmTest :android:assembleDebug with Gradle 8.14.3 and the
repository's Windows Java-home setting overridden only for this Linux command.
Plugin resolution fails before compilation in this environment. A Windows dual
build result is required; standalone JVM results do not establish Android success.

```powershell
& .\scripts\check-common-main.ps1
& .\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain
```

core:jvmTest now enforces maxHeapSize=256m for that command. The test needs about
2 GiB of temporary disk, cleaned afterward. The new large transfer has a five-minute
deadline for slower disks. No Step 10 commit or remote push was made.

After dual-build verification, commit as:
`step10: verify 1 GiB TCP streaming with a 256 MiB test heap`.

Step 3's API 29 crypto instrumentation and later real-device transport acceptance
remain pending. This step does not claim Android/iOS transfer throughput or the
later gesture/iOS architecture phases.

## Owner Windows failure under investigation

The first Windows dual-build run executed 101 tests: 100 passed and the new
1 GiB test failed. The assertion observed sender Failed(canResume=true), with a
socket write aborted message. That sender-side message alone does not establish
why the peer closed or which socket failed first.

The acceptance test now records both machine states, transferred chunk/byte
counters, read/write exceptions and each socket's first close call stack. On
failure it includes that bounded diagnostic information in the assertion/XML
report. The heap cap, four sockets, 1 GiB length, all chunk/hash assertions and
production timeouts are unchanged. No production fix is claimed yet; a focused
Windows run is needed to identify the first failure. The instrumented 1 GiB test
passes locally with -Xmx256m.
