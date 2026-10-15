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
Sender: select file → open camera → ✋ palm → ✊ fist = GRABBED (haptic) → BLE advertise OFFER
Receiver: app open, scanning → sees OFFER → 🖐 open palm (fist→palm) within 10s = CLAIM
→ Accept prompt (sender name + file summary + 4-digit verify code)
→ link setup → encrypted TCP transfer → verify hash → DONE (haptic + sound)
```
- Tap fallback ("Send" / "Receive" buttons) must always exist alongside gesture.
- Multiple receivers: first CLAIM wins for single send; Group mode accepts all CLAIMs in the window.

## 5. Protocol

### 5.1 BLE phase (control, small)
GATT service UUID: generate once, store in `core/protocol/Uuids.kt`. One write characteristic + one notify characteristic. Messages are fragmented to MTU (header: `msgId u16, frag u8, total u8`).

| Msg | Dir | Fields |
|---|---|---|
| HELLO | both | deviceId (16B), name, platform, caps bitmask (WIFI_DIRECT, HOTSPOT_HOST, LAN, MPC, AWARE) |
| OFFER | S→R | sessionId (16B), senderPubKey (X25519 32B), fileCount, totalBytes, previewName |
| CLAIM | R→S | sessionId, receiverPubKey (32B) |
| ACCEPT / DENY | R→S | sessionId, reason |
| LINK_INFO | S→R (encrypted) | linkType, ssid?, passphrase?, ip, port |

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
- Nothing transfers before explicit ACCEPT. Session expires 60s after OFFER if unclaimed.
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
Sender: `IDLE → GRABBED → ADVERTISING → CLAIMED → AWAIT_ACCEPT → LINKING → AUTH → TRANSFERRING → DONE | FAILED | CANCELLED`
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