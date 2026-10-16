# YoDrop — Architecture & Build Spec (for coding agent)

Gesture-based (grab ✊ / release 🖐) offline file transfer. Android ↔ Android, iPhone ↔ iPhone, Android → iPhone. Name "YoDrop" is a working name.

## 1. Rules for the agent
- Stack is fixed (Section 2). Do not swap frameworks.
- No third-party libraries except: MediaPipe Tasks (hand landmarks), kotlinx.coroutines, kotlinx.serialization. Everything else = platform APIs or our own code.
- Transfer protocol is our own (Section 5). Do not use Google Nearby Connections.
- Work phase by phase (Section 9). Each phase: build passes, tests pass, then commit to main.
- Shared logic goes in `core/` (KMP). Platform folders contain only platform glue + UI.

## 2. Stack
| Layer | Android | iOS |
|---|---|---|
| Shared core | Kotlin Multiplatform (`core/`) | same, as XCFramework |
| UI | Jetpack Compose | SwiftUI |
| Gesture | CameraX + MediaPipe Hand Landmarker | AVFoundation + Vision `VNDetectHumanHandPoseRequest` |
| Discovery/handshake | BLE (`BluetoothLeAdvertiser`/Scanner + GATT) | CoreBluetooth |
| Data link | Wi-Fi Direct (`WifiP2pManager`), LocalOnlyHotspot, same-LAN | Same-LAN, `NEHotspotConfiguration`, MultipeerConnectivity (iOS↔iOS) |
| Sockets | `java.net` via expect/actual | `Network.framework` via expect/actual |
| Crypto | JCA (X25519, HKDF, AES-GCM) via expect/actual | CryptoKit via expect/actual |
| Background | Foreground service (type `connectedDevice`) | Stay foreground, `isIdleTimerDisabled = true` |

Min versions: Android 10 (API 29), iOS 16 (Wi-Fi Aware later, iOS 26+).

## 3. Repo structure
```
YoDrop/
  core/                    # KMP: commonMain, androidMain, iosMain, jvmTest
    protocol/              # frames, messages, codec
    session/               # state machines (sender/receiver)
    transfer/              # chunking, resume, checksum, parallel streams
    crypto/                # expect/actual: X25519, HKDF, AES-GCM, SHA-256
    transport/             # Transport interface + selector
    platform/              # expect/actual: sockets, file IO, clock
  android/                 # Compose app, BLE, Wi-Fi Direct, hotspot, CameraX, service
  ios/                     # SwiftUI app, CoreBluetooth, NEHotspot, Vision
  docs/
```

## 4. Flow
```
Sender: select file → open camera → ✋ palm → ✊ fist = GRABBED (haptic) → BLE advertise service UUID (read OFFER over GATT)
Known receiver: select trusted sender → 60s foreground window → automatic CLAIM/identity verification
→ encrypted preview → fresh fist→palm Catch (or Catch files tap) = consent
New receiver: Receive from a new device → review OFFER/CLAIM within 10s
→ explicit Accept prompt (sender name + file summary + 4-digit verify code)
→ link setup → encrypted TCP transfer → verify hash → DONE (haptic + sound)
```
- Tap fallback ("Send" / "Receive" buttons) must always exist alongside gesture.
- Approved A5: Android selects one or multiple files through the system document
  picker, streams URI content, and saves verified received files automatically to
  `Downloads/YoDrop` through MediaStore. No receive-folder prompt or broad storage
  permission is required. Private `.part` staging precedes verified commit and
  pending-row publication; unfinished files are not shown as completed downloads.
- Multiple receivers: first CLAIM wins for single send; Group mode accepts all CLAIMs in the window.

### Android redesign R1-R3 (approved by owner, 2026-10-16)

Complete Android before P7-P10: R1 authenticated trusted pairing and a bounded
receive window; R2 preview/grab/catch UI; R3 two-phone acceptance and fixes.
R1 retained explicit Accept for every transfer. R2 treats a deliberate Catch
as consent only for a cryptographically verified pinned device, the selected
target and a live foreground receive window. Unrecognized devices retain explicit
Accept/code comparison. Group mode remains P10, outside this redesign scope.

R1 uses a nonexportable Android Keystore P-256 signing identity, separate from
ephemeral X25519 transfer keys. Public identities are exchanged only after CLAIM,
encrypted under fresh session keys. Each signs the role, session ID and both
ephemeral keys. Local explicit code-match confirmation pins its SHA-256 public-key
fingerprint; names, HELLO IDs and Bluetooth addresses never establish trust.
Initial trust compares the full 96-bit pairing code (six groups of four hex digits),
binding both identity keys and the ephemeral transcript in canonical role order.
The existing four-digit transfer code remains; it alone is not trust enrollment.
Both owners confirm separately. Forget/reset removes local authorization; a
reinstalled/changed identity requires pairing again. Trusted metadata is bounded,
encrypted with Keystore AES-GCM and atomically stored in no-backup app storage.

HELLO capability bit 6 negotiates R1. New peers use encrypted pairing-control
tag 08 (identity proof and consent), with separate directional keys/nonces and
replay counters; plaintext ACCEPT/DENY is rejected when R1 is negotiated. Legacy
peers keep manual Accept and cannot gain trusted status. OFFER remains the same
five public fields and discovery remains UUID-only, one GATT connection at a time.

Trusted receive is explicitly armed for one selected pinned fingerprint for 60 s,
using a monotonic clock. CLAIM binds it to one session; authentication must match
the selected fingerprint, and consent must occur before expiry. Cancel, leaving
the foreground, reset, revocation or terminal state closes it. It is never restored
after process/activity recreation. Expiry/background closes only waiting consent,
not a transfer that already obtained valid consent. R1 did not auto-accept data.

R2 has one sending entry: document selection always stages a file preview before
Grab. Palm->fist or the Grab files tap hands the existing streaming sources to
the runtime and advertises. Discard/Activity destruction before Grab closes the
sources; preview selections are not restored. Optional provider thumbnails are
bounded to 360x240 and loaded off the UI thread; unsupported types keep metadata.

Selecting a trusted sender explicitly arms the R1 window. Android automatically
reads/reconnects/CLAIMs the first discovered offer to authenticate it; CLAIM alone
grants no data consent. A different identity safely refuses the session (retry is
required; there is still one GATT connection and no public identity advertisement).
Only the matching pinned proof plus authenticated encrypted preview produces a
Catch ticket bound to window token, session token and fingerprint. A **fresh**
stable fist->palm sequence after that ticket appears, or Catch files tap, submits
encrypted consent and consumes the window once. Pre-authentication gestures cannot
be queued; a context change resets the gate. No separate Accept tap is needed for
this selected trusted flow. An ordinary Receive, newly saved pin, legacy peer or
unselected trusted peer retains manual Accept. Closed trusted windows never fall
back to a manual Accept screen for that session.

Camera preview is a small, optional 112x144 view and closes after Grab/Catch,
on pause and on terminal states. Permission prompts resolve before arming a trusted
window; denial keeps the tap path. The runtime rechecks the foreground lease after
registry/discovery suspension; Catch rechecks proof/pin/session/window/deadline
under the controller mutex. Decorative waiting/progress/completion animation never
authorizes anything. Camera accuracy, visual layout and two-phone radio behavior
remain R3 physical acceptance work.

## 5. Protocol

### 5.1 BLE phase (control, small)
GATT service UUID: random 128-bit UUID generated once, stored in `core/protocol/Uuids.kt`. Approved A4: discover by this service UUID only; no sessionId, filename, local name or service data in advertisements/scan responses. One write characteristic + one notify characteristic. Messages are fragmented to MTU (header: `msgId u16, frag u8, total u8`).

| Msg | Dir | Fields |
|---|---|---|
| HELLO | both | deviceId (16B), name, platform, caps bitmask (WIFI_DIRECT, HOTSPOT_HOST, LAN, MPC, AWARE) |
| OFFER | S→R (plain GATT) | sessionId (16B), senderPubKey (X25519 32B), senderName, fileCount, totalBytes |
| CLAIM | R→S | sessionId, receiverPubKey (32B) |
| OFFER_DETAILS (0x07) | S→R (encrypted after CLAIM/ECDH) | previewName; outer envelope counter + ciphertext |
| ACCEPT / DENY | R→S | sessionId, reason (legacy manual flow only when R1 is not negotiated) |
| PAIRING_CONTROL (0x08) | both | role, counter, ciphertext: signed identity proof or authenticated consent |
| LINK_INFO | S→R (encrypted) | linkType, ssid?, passphrase?, ip, port |

Approved A4 discovery lifecycle:
- Advertise only while sender is `GRABBED` (including tap fallback); stop on CLAIM,
  every terminal state and the 60-second OFFER deadline. `Grabbed(offer)` replaces
  the former transient Grabbed + Advertising states.
- A single service-only legacy advertisement fits 21 bytes including Flags; scan
  response is empty. This intentionally replaces Step 15's former sessionId hint.
- One active GATT connection at a time. On discovery, obtain the full public OFFER
  over the existing HELLO/write -> OFFER/notify exchange, with fragmentation.
  Cache bounded public offers by peer/session; disconnect after reading without
  claiming. An explicit tap reconnects the same peer/session for CLAIM.
- Android requests MTU 247 after connection, retaining fragmentation using the
  actual negotiated MTU (default 23 fallback). Debug-only MTU probes can request 23.
- Receiver stays CLAIMED until authenticated OFFER_DETAILS supplies previewName;
  only then show the accept/Catch prompt. The 60-second post-CLAIM timer bounds waiting
  for details and acceptance. LINK_INFO follows explicit Accept or valid trusted Catch consent.
- Future iOS sender advertises only this service UUID while foreground; no service
  data support is required. This does not implement iOS or background discovery.

Verify code = first 4 digits of SHA-256(sharedSecret) shown on both screens.

### 5.2 TCP phase (bulk, encrypted)
Frame: `[u32 length][u8 type][payload]`, big-endian. Control payloads = kotlinx.serialization CBOR. Data payload = raw bytes.

| Type | Msg | Dir | Fields |
|---|---|---|---|
| 0x01 | AUTH | both | sessionId, HMAC(sessionKey, "auth"+role) |
| 0x02 | MANIFEST | S→R | files[{id, name, size, mime, sha256}], chunkSize |
| 0x03 | CHUNK_REQ | R→S | fileId, chunkIndex[] (batch) |
| 0x04 | CHUNK_DATA | S→R | fileId, chunkIndex, ciphertext (AES-GCM, tag inside) |
| 0x05 | ACK | R→S | fileId, chunkIndex |
| 0x06 | NACK | R→S | fileId, chunkIndex, reason |
| 0x07 | FILE_DONE | R→S | fileId, ok |
| 0x08 | CANCEL | both | reason |
| 0x09 | DONE | S→R | — |
| 0x0A | PING/PONG | both | ts |

- Pull model: receiver drives CHUNK_REQ → enables resume + parallelism.
- chunkSize = 4 MiB. Stream from disk; never load whole file.
- Parallel: 2–4 TCP connections per session, receiver assigns chunk ranges.
- Resume: receiver persists `{sessionId, fileId, bitmap of received chunks}`; on reconnect re-AUTH and request missing chunks only.
- Integrity: AES-GCM tag per chunk + final file SHA-256 vs manifest.

## 6. Security
- X25519 ECDH (keys from OFFER/CLAIM) → HKDF-SHA256(salt=sessionId) → `sessionKey` (32B) + `nonceBase` (8B).
- AES-256-GCM, nonce = nonceBase(8B) ‖ u32 counter (unique per message per direction; separate key per direction via HKDF info "s2r"/"r2s").
- Nothing transfers before explicit Accept or deliberate Catch in the authenticated selected trusted window. Session expires 60s after OFFER if unclaimed.
- Camera frames processed on-device only, never stored/uploaded.

## 7. Transport selector (priority)
1. Same LAN (both report LAN cap + same subnet) → TCP on LAN.
2. Android↔Android → Wi-Fi Direct group (sender = group owner).
3. iOS↔iOS → MultipeerConnectivity (wrap as Transport stream).
4. Android→iOS → Android LocalOnlyHotspot; SSID/pass via LINK_INFO; iOS joins with NEHotspotConfiguration (one user prompt).
5. Payload < 64 KB (contact/link/text) → BLE only, skip data link.
6. Future: Wi-Fi Aware when both support it.

`Transport` interface (commonMain): `connect(linkInfo): Connection`, `listen(): Connection`, `Connection.read/write/close`.

## 8. State machines
Sender: `IDLE → GRABBED (advertising) → CLAIMED → AWAIT_ACCEPT → LINKING → AUTH → TRANSFERRING → DONE | FAILED | CANCELLED`
Receiver: `IDLE → SCANNING → OFFER_SEEN → CLAIMED → ACCEPT_PROMPT → LINKING → AUTH → RECEIVING → VERIFYING → DONE | FAILED | CANCELLED`
Timeouts: OFFER 60s, CLAIM window 10s, LINKING 20s, idle socket 15s (PING every 5s). Every failure → clean up link (remove WFD group / stop hotspot).

## 9. Phases (each = commit when green)
| Phase | Scope | Done when |
|---|---|---|
| P1 | `core`: codec, frames, crypto expect/actual, chunking, resume bitmap | JVM tests: encode/decode round-trip, encrypt/decrypt, 1 GB loopback transfer with forced disconnect resumes correctly |
| P2 | `core`: session state machines + Transport interface + in-memory/loopback transport | JVM tests cover all states, timeouts, cancel |
| P3 | Android: BLE HELLO/OFFER/CLAIM/ACCEPT, tap UI | Two Android phones pair over BLE, verify code matches |
| P4 | Android: Wi-Fi Direct transport + foreground service | 1 GB file A→A, ≥15 MB/s on 5 GHz devices, resume after toggling Wi-Fi |
| P5 | Android: LAN transport + selector | Same Wi-Fi auto-picks LAN |
| P6 | Android: gesture (CameraX + MediaPipe), haptics, sound | Fist/palm detected ≥95% in normal light, false trigger rare; tap fallback still works |
| P7 | iOS: core XCFramework, CoreBluetooth, SwiftUI tap UI, MPC transport | iOS↔iOS 1 GB transfer |
| P8 | Android→iOS hotspot transport | Transfer works with single iOS join prompt |
| P9 | iOS gesture (Vision) | Same criteria as P6 |
| P10 | Share Sheet (Android share intent, iOS Share Extension), group mode, small-payload BLE (contact/link/text) | End-to-end from Gallery share |

## 10. Permissions
Android: `CAMERA`, `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES` (33+), `ACCESS_FINE_LOCATION` (≤32 for Wi-Fi Direct), `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`. Must ask user to turn on Bluetooth/Wi-Fi (app cannot toggle).
iOS Info.plist: `NSCameraUsageDescription`, `NSBluetoothAlwaysUsageDescription`, `NSLocalNetworkUsageDescription`, `NSBonjourServices`; Hotspot Configuration capability.

## 11. Store notes
- Do not use "AirDrop" or "Quick Share" in name/metadata.
- Privacy policy: camera used only for on-device gesture detection; no data collected.


## Approved A1 exception — 2026-10-15
Owner approved bundled BouncyCastle for X25519 on JVM/Android, keeping
Android minSdk 29. It is the sole additional crypto dependency permitted
by this decision; other cryptographic operations remain platform-backed.
The JVM/Android CryptoEngine actual lives in jvmAndAndroidMain.

## Approved A3 clarification — 2026-10-15
Receiver CLAIM window is 10 seconds from OFFER_SEEN.
The human verification/accept prompt has a separate 60-second timeout on
both sides. LINKING remains 20 seconds and socket read idle remains 15 seconds.

## Approved A4 — 2026-10-16
Owner approved service-UUID-only discovery and full OFFER over GATT, with the four
conditions implemented in §5.1: Grabbed-only advertising with 60-second/session-end
stop, public OFFER limited to five fields and encrypted post-CLAIM previewName,
one GATT connection with per-session cache/read-disconnect, and Android MTU 247
request with fragmentation. Both APKs must update together for the new UUIDs and
OFFER/OFFER_DETAILS wire schema.

## Approved A5 — 2026-10-16
Owner selected automatic `Downloads/YoDrop` storage instead of a SAF-picked receive
folder. Android API 29+ uses MediaStore.Downloads with RELATIVE_PATH and IS_PENDING;
only fully copied, verified files are made visible. Sender SAF URI selection is
independent of that destination decision. File names may receive a collision suffix
from MediaStore; an existing download is never selected for overwrite.
