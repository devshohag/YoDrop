# Step 9 validation

Base: main commit c25a292 (Step 8, including the Windows correction).

## Executed

Compiled all common/JVM production and test sources using Kotlin 2.2.0, its
serialization compiler plugin and the exact repository dependency versions.
Ran all 20 JVM suites using JUnit 4.13.2 and -Xmx512m:

```text
Suites: 20
JUnit version 4.13.2
Step8 TCP: 256 MiB; dropped at 26/64 (40.625%); reconnect chunks=38; bytes=159383552; SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=retry; NACK=1; Done once; both SHA-256 matched
Step9 TCP: 4 authenticated sockets; 2 files / 48 MiB; mode=exhaust; NACK=4; Done once; both SHA-256 matched
Time: 9.707

OK (100 tests)
```

The replacement T7 suite drives the real SenderStateMachine and
ReceiverStateMachine, injecting faults only in transport wrappers:

- Four authenticated real TCP sockets all carry chunks for two disk files.
- One damaged ciphertext triggers a real receiver NACK and a sender retry with
  a different attempt/nonce counter; no duplicate initial chunk assignments.
- Persistent corruption on one socket exhausts its retry budget; its leased
  chunks are requested on healthy sockets and the session still reaches Done.
- Both final files match manifest SHA-256 and each machine emits Done once.
- A held chunk proves the request window refills before all outstanding chunks
  arrive. This test uses the same real machines on loopback connections.
- Both machines reject/close a fifth concurrent connection.

All prior resume, timeout, event-guard, crypto, cleanup and protocol tests passed.
commonMain portability regex returned no matches. git diff --check passed.

## Full Gradle dual build pending

Attempted clean :core:jvmTest :android:assembleDebug with Gradle 8.14.3, overriding
only the repository's Windows Java-home setting for Linux. Resolution failed
before compilation:

```text
Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.2.0', apply: false]
was not found ... could not resolve plugin artifact ...
BUILD FAILED in 15s
```

No full Android build success, Step 9 commit or remote push is claimed. Run on
the owner's configured PC:

```powershell
& .\scripts\check-common-main.ps1
& .\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain
```

After the dual build is green, commit as:
`step9: add parallel receiver windows and resilient NACK retries`.

API 29 crypto instrumentation is still pending from Step 3. This 512 MiB heap
run does not claim Step 10's 1 GiB / 256 MiB heap acceptance criterion.

## Owner dual-build verification

After applying Step 9, the owner ran the combined clean JVM-test and Android
assemble command successfully on Windows:

```text
BUILD SUCCESSFUL in 46s
61 actionable tasks: 61 executed
```

This resolves the Step 9 dual-build pending item above. The owner pushed Step 9
as c8fe3a7 before Step 10 began.
