# Current implementation decisions

This file describes the current tree, audited at Step 20. Historical intermediate
notes remain available in git (`git show 4fb3bd4:docs/DECISIONS.md`) and the older
STEP*-VALIDATION reports; their pending-work statements are not current status.
Physical results are recorded separately in MANUAL-TESTS.md and the Step 20 device
ledger. Build success alone does not complete device acceptance.

## Owner decisions

| Decision | Approved choice / implementation |
| --- | --- |
| A1 | Bundled BouncyCastle X25519 only; keep Android minSdk 29. Other crypto uses JCA. |
| A2 | Restore architecture TCP frame numbers; no renumbered compatibility mode. |
| A3 | Human accept deadline 60 s; receiver CLAIM window 10 s. |
| A4 | UUID-only advertisements, public five-field OFFER read over GATT, encrypted post-CLAIM preview, one GATT connection/cache, request MTU 247. |
| A5 | Automatic MediaStore Downloads/YoDrop; no receive-folder prompt. |

No additional architecture deviation is approved here. Step 20 restores the
existing §6 nonce layout in code; it does not change the architecture.

## Step 1 — Android build and real IO wiring

UI and core use progress/currentFileName consistently. Android supplies real
selected-file sources and a non-placeholder scoped-storage sink factory. Step 19
replaced the interim source cache with URI streaming. Normal Receive cannot reach
the default test-only constructor's missing-sink error. Owner reported clean
Gradle builds through Step 19; these are separate from this Step 20 build.

## Step 2 — Portable common code

commonMain contains no Java imports, synchronized monitors, blocking coroutine
bridges or JVM String.format call. Coroutine Mutex protects shared IO state.
Step 20 replaces the last JVM-only format in in-memory resume keys with portable
hex encoding. MemoryFileSource/Sink now live in commonTest: no production caller
remained after the Step 11 stub removal. The portability script checks these
source restrictions, not native compilation; no iOS target exists yet.

## Step 3 — Shared X25519 implementation (A1)

CryptoEngine's JVM/Android actual lives in jvmAndAndroidMain. BouncyCastle
bcprov-jdk15to18 1.86 supplies X25519; input keys are length-checked, bit 255 of a
copy is masked, and low-order/all-zero agreements are rejected. AES-GCM, HMAC,
SHA-256 and randomness use JCA. Shared RFC 7748 checks run in JVM tests and are
packaged in a custom Android instrumentation runner. Execution on API 29 remains
pending; there is no claim of constant timing on every JVM/ART platform.

## Step 4 — Bounds, frame numbers and verification code (A2)

TCP AUTH=01, MANIFEST=02, CHUNK_REQ=03, CHUNK_DATA=04, ACK=05, NACK=06,
FILE_DONE=07, CANCEL=08, DONE=09, PING/PONG=0A. PING carries ts/reply and NACK a
reason. Frames are capped at 4 MiB+1 KiB; chunk size is 64 KiB..4 MiB, files at
1000, and total chunks at 1,048,576 (individual size at 4 TiB). IDs, names,
requests, chunk identity/index and exact payload lengths are validated before IO.
Safe names such as a..b.txt remain legal. Disk commit selects a unique destination
instead of replacing an existing file. VerifyCode takes the first four decimal
digits of unsigned SHA-256(sharedSecret), padding small values with zeroes.

## Step 5 — Guarded state machines

Both machines serialize events with a Mutex, expose read-only state/transitions,
keep session keys/IDs private, preserve the first valid CLAIM, reject foreign
sessions and prevent worker updates from overwriting terminal states. Receiver
Claimed remains until authenticated encrypted offer details arrive. Reset waits
for old workers/cleanup. Group claims are future P10 work.

## Step 6 — Injected deadlines (A3)

Immutable Timeouts defaults: sender OFFER 60 s; receiver CLAIM from OFFER_SEEN
10 s; post-CLAIM human acceptance 60 s; linking 20 s; read idle 15 s; periodic PING
5 s. Both machines own timers and tests use virtual time. Read watchdogs close
sockets to unblock blocking platform reads. Active progressing transfers have no
fixed total duration deadline.

## Step 7 — Loss, liveness and terminal cleanup

EOF/transport IO/read-idle loss retains keys, sources, sinks and bitmap for bounded
same-session reconnect. Failed(canResume=true) permits attach/cancel until expiry.
Done, deny, cancel, expiry, bad AUTH/protocol/checksum and scope cancellation are
terminal. NonCancellable idempotent cleanup closes sockets/workers/IO and awaits
platform hooks; best-effort CANCEL carries a reason. Separate readers/PING writers
keep slow verification alive. Peer CANCEL is not echoed.

## Step 8 — Durable resume

Receiver forces part-file bytes before saving received bits. DiskResumeStore forces
a unique temporary bitmap then atomically replaces it; unsupported atomic moves
fail. Directory force is best-effort where unsupported. Instances share a
canonical-directory Mutex within one process. Bounded loads reject torn/oversized
records; missing/truncated part data resets progress. Reattach re-AUTHs and asks
only for missing chunks. Step 20 wires Android runtime to the existing disk store.
This does not restore keys/consent or transfer sessions after process restart.

## Step 9 — Parallel receiver and session completion

Up to four authenticated sockets share ReceiveCoordinator, per-file bitmap/leases,
retry counters and verification ownership. Sliding windows default to four, capped
at sixteen requests per worker. Bad ciphertext triggers NACK; sender retransmits
with a fresh global counter. Exhausted worker retries release work to healthy
sockets. Completion is session-wide and once-only after all files verify/commit;
reported committed IDs survive reconnect. Invalid AUTH/protocol remains terminal.

## Step 10 — Large transfer memory acceptance

core:jvmTest uses maxHeapSize=256m and one fork. The real 1 GiB/four-TCP-socket test
asserts that heap cap, exact bytes/chunks, SHA-256, once-only Done and cleanup.
The real 256 MiB resume test drops at chunk 26/64 (40.625%) and asserts only 38
missing chunks / 159,383,552 plaintext bytes on the reconnect. These are JVM
acceptances, not Android throughput/RSS/radio measurements. Temporary disk needs
about 2 GiB for source+destination and is cleaned by the tests.

## Step 11 — Sender transport policy

Sender owns payload size/peer capabilities and selects BleOnly, DataLink or
Unavailable. Below 64 KiB chooses BLE only when both advertise BLE; that future
P10 data path is not implemented. Data priorities: actual same-subnet LAN,
Android/Android WFD, iOS/iOS MPC, then Android-sender/iOS-receiver hotspot. LAN uses
actual IPv4 prefix, never a guessed /24. Android's current network adapter is
described at Step 19. One shared TcpConnection wraps platform sockets; old empty
README.kt/cross-platform orchestrator stubs were removed. MPC/iOS adapters do not
exist despite historical commit titles.

## Step 12 — Typed BLE controller

One-byte tags plus concrete bounded CBOR replace trial-decoding. HELLO includes
16-byte persisted random device identity, name/platform/caps and optional LAN IP.
BleHandshakeController binds peer IDs to offers/claims/decisions and dispatches
actual state machines. Tap CLAIM/ACCEPT/DENY is wired. Public OFFER has exactly
sessionId, senderPubKey, senderName, fileCount, totalBytes. Filename arrives only
through authenticated post-CLAIM OFFER_DETAILS (Step 15).

## Step 13 — Callback-paced GATT

Server CCCD, notification callbacks and a single-in-flight operation queue replace
fixed delay throttling. Queue/callback keys include link generation; timeout or
cancel poisons a retired queue. Actual MTU controls fragments with per-peer bounded
reassembly/expiry and ACK pacing. Android requests 247; fallback is 23 and Android
may negotiate a different value. Opt-in debug 1 KiB diagnostics use tag 7F; the
strict dummy payload cannot become a session endpoint. Release builds ignore the
probe extras. Actual phone MTU/CCCD/round-trip results remain pending.

## Step 14 — Consent-gated encrypted LINK_INFO

Tag 06 is an encrypted counter/ciphertext envelope. Dedicated key expansion uses
YoDrop-BLE-S2R-LINK_INFO-v1, nonce ASCII LINKINFO + u32 counter, AAD sessionId.
Counters are lifetime-monotonic, consumed before encryption and not reset. Sender
creates actual ephemeral listener/group/reservation endpoints only after ACCEPT;
receiver authenticates once in its accepted session and rejects tampering/replay.
No plaintext LINK_INFO compatibility bypass or logged credentials exists. Hotspot
and LinkInfo debug strings redact secrets. Plaintext endpoints exist only in the
strict diagnostic port and injected core test fixtures.

## Step 15 — UUID discovery and encrypted preview (A4)

Fixed random 128-bit UUID 965ff8a1-0a45-47ab-aca5-46d565d63e84. Advertise only
GRABBED until CLAIM/terminal/60 s; flags+UUID use 21 legacy bytes and scan response
is empty. No session/name/file hint is broadcast. One GATT connection, bounded
16-session/60 s public offer cache, read/disconnect unless explicitly claiming.
Post-ECDH tag 07 encrypts previewName with a separately expanded key and nonce
OFRDTLS1 + u32 counter, AAD sessionId. Receiver stays Claimed until details verify.
This prepares future iOS interoperability; it does not implement an iOS sender.

## Step 16 — Android Wi-Fi Direct

Actual WifiP2pManager callback adapter discovers/connects/removes sender-owned
groups. Shared WifiDirectSession validates owner role, credentials/address and
12 s setup. Receiver binds up to four TCP workers to the actual P2P interface.
Bounded recovery preserves keys/bitmap/part and rejects an unexpected owner IP.
Terminal cleanup awaits group/listener/worker disposal; failed removal is logged
unconfirmed. Physical 5 GHz >=15 MB/s and radio-toggle resume remain pending.

## Step 17 — Local-only hotspot

API 30+ reads reservation SoftApConfiguration; API 29 alone uses nullable legacy
WifiConfiguration. No fabricated SSID/IP/port. Actual before/after interface
snapshots exclude upstream/ineligible addresses and require one new candidate;
ambiguous/shared-existing reservations fail conservatively. Shared HotspotSession
bounds startup, owns leases and immediately closes late reservations after cancel.
Terminal hooks close listener/reservation. The debug-only hotspot diagnostic
proves reachability only when run; iOS joining/transfer remains unimplemented.

## Step 18 — Foreground transfer and permissions

TransferRuntime uses application context and owns machines/BLE/link scope beyond
Activity lifecycle. Visible actions request connectedDevice foreground service;
state observers only stop it. START_NOT_STICKY avoids process-death restart.
Generic notification opens/cancels; CPU/Wi-Fi locks cover data/reconnect states
and release at terminal/destruction. Service generations guard old destroy events.
Runtime permissions respect BLE API31+, location fine+coarse <=32, Nearby Wi-Fi
API33+, and optional notifications. Notification denial does not block transfer.
System prompts/panel request Bluetooth/Wi-Fi enable; app never toggles radios.
Manifest backup is false and transfer service nonexported. Five-minute physical
screen-off/cleanup evidence remains pending.

## Step 19 — URI streaming and Downloads (A5)

OpenMultipleDocuments selects/deduplicates URI batches. Streaming 64 KiB scan
computes actual size/hash; RangeFileSource reads at most 4 MiB with seek or bounded
sequential/reopen handling for pipe/subsection providers. Owned persisted read
grants release at source close; pre-existing app grants are preserved. Temporary
provider grants can disappear; mutation/unavailability fails explicitly. Preparation
is cancellable/Activity-scoped; accepted source ownership hands off atomically to
the runtime. No whole-source cache copy remains.

Unique private paths prevent remote IDs/names forming staging paths. Real receiver
verifies DiskFileSink .part, PublishingFileSink commits then copies to a new pending
MediaStore Downloads/YoDrop row and publishes it. Failure propagates without false
successful FILE_DONE; pending-row deletion is best-effort and staging discarded.
Already published files survive later batch failure. Existing downloads are never
opened for overwrite. UI shows the approved destination.

Android LAN snapshots allow unique eligible offline Wi-Fi/Ethernet even with
mobile default, exclude active VPN/ambiguity, and bind only the receiver socket to
the selected Network/local IPv4. Fresh HELLO metadata and sender actual prefix
drive selection; encrypted endpoint is consent-gated. LAN recovery reuses the
bounded reconnect observer. Real two-phone 1 GiB/MediaStore acceptance is pending.

## Step 20 — Audit and evidence gate

Restored architecture §6 TCP nonce = 8-byte derived prefix + global u32 counter;
chunk index/file/session remain authenticated in AAD. Counter allocation remains
session-wide across files/sockets/retries and monotonic over machine lifetime,
failing before overflow. Both APKs must update: older chunk crypto is incompatible.
Removed JVM-only resume-key formatting, moved unused memory IO to commonTest,
and wired Android DiskResumeStore. No new dependency or architecture deviation.

README/iOS status, one current entry per step, real git history and an explicit
pending device ledger replace stale completion claims. validate-step20.ps1 runs
source/merged-manifest checks, clean JVM/app/instrumentation-APK builds, rejects
empty/skipped/missing acceptance suites and records logs/counts/APK hashes. It
never marks a physical test passed. STEP20-AUDIT.md records blockers/limitations;
final acceptance remains pending until actual build and device evidence exists.
