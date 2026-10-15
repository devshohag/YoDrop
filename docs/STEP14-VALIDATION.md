# Step 14 validation

Base: main d9c4063 (Step 13).

## Implemented

Sender-owned LINK_INFO using a dedicated HKDF-expanded control key and nonce
domain/counter, AES-256-GCM and AAD=sessionId. Type 06 carries only counter and
ciphertext; credential-bearing inner CBOR never appears in the BLE plaintext.
Receiver peer/consent/authentication/replay checks precede endpoint publication.
Metadata/debug strings redact credentials, and plaintext tag-06 inputs are rejected.

Sender endpoint factories take actual addresses/ports/credentials. Android now
exchanges optional current LAN IPv4 discovery metadata, prepares a real sender
listener after ACCEPT and connects receiver TCP only after decrypting its endpoint.
The suspend cleanup hook closes/joins the LAN listener. No guessed receiver address,
port, hotspot SSID or password is used. Wi-Fi Direct/hotspot setup still awaits
Steps 16/17; unavailable providers remain pending under the existing linking timer.

The debug-only exact 1 KiB GATT probe uses reserved tag 7F with a strictly restricted
dummy schema. It cannot encode arbitrary plaintext LINK_INFO or credentials. Both
phone APKs must update together for these BLE wire changes.

## Executed locally

Kotlin 2.2.0 compilation of common/JVM production/tests with the repository's
serialization plugin and exact dependency versions. All 25 suites / 158 tests
passed under **-Xmx256m**, including 15 new Step14LinkInfoTest checks. The new suite
verifies plaintext credential absence from serialized BLE, complete decrypt round
trip, ciphertext/tag/counter/session-AAD/directional-key tampering, control vs TCP
key separation, counter bounds, metadata limits, old plaintext rejection, debug
probe restriction, redaction, explicit consent, peer binding, retry/replay,
concurrent nonce allocation, reset monotonicity and real TCP endpoint integration.

The integration test exchanges encoded typed control messages through both real
handshake controllers, accepts encrypted LINK_INFO, connects a real TCP socket to
the sender's ephemeral listener and transfers 1 MiB through the actual machines
with matching SHA-256. It is a JVM loopback test, not physical Android BLE/LAN.
Existing 1 GiB/256 MiB-heap, resume and parallel TCP tests remain included.

Changed Android sources and glue compiled separately against official API 35,
Kotlin/Compose compiler 2.2.0 and repository-version AndroidX/Compose dependencies.
commonMain portability and git diff --check passed. The patch applies to a clean
Step 13 checkout and changed-file bytes match the validated source. No dependency
or TCP frame numbering changed.

## Windows and physical validation required

Attempted Gradle 8.14.3 clean :core:jvmTest :android:assembleDebug. Root Kotlin
multiplatform plugin 2.2.0 still cannot resolve here, before compilation. Standalone
compilation/tests do not establish APK packaging success. No repository Java-home
or dependency configuration changed; no commit/push was made.

```powershell
New-Item -ItemType Directory -Path 'D:\Temp' -Force | Out-Null
$env:TEMP = 'D:\Temp'
$env:TMP = 'D:\Temp'
.\gradlew.bat --stop
& .\scripts\check-common-main.ps1
.\gradlew.bat clean :core:jvmTest :android:assembleDebug --console=plain --no-daemon
```

TEMP/TMP affect only this PowerShell session and children. The existing large test
still requires roughly 2 GiB temporary disk. After software dual-build verification:
`step14: encrypt sender-owned BLE link info and authenticate receiver endpoints`.

Physical same-LAN transfer, matching-code and GATT probe checks remain PENDING in
docs/MANUAL-TESTS.md. Step 3's API 29 crypto instrumentation is also still pending.
No Wi-Fi Direct/hotspot/iOS/throughput acceptance is claimed by this patch.

Design references (these do not constitute a security audit of this application):
- https://www.rfc-editor.org/rfc/rfc5869 (one-block HKDF Expand/context separation)
- https://csrc.nist.gov/pubs/sp/800/38/d/final (GCM nonce uniqueness and AAD)
- https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-cbor/kotlinx.serialization.cbor/-byte-string/
