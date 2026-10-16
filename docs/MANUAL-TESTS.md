# Physical-device acceptance

Do not infer any physical Android result from JVM tests or an APK build.

## Step 12: BLE discovery, tap CLAIM and verification code

Status: **PENDING - not executed on physical devices.**

| Field | Sender phone | Receiver phone |
| --- | --- | --- |
| Manufacturer/model | Not recorded | Not recorded |
| Android version / API | Not recorded | Not recorded |
| YoDrop commit / APK | Not recorded | Not recorded |
| Date/tester | Not recorded | Not recorded |

Install the same new debug APK on two physical Android phones (API 29+). Old
untyped BLE builds cannot interoperate with the new message codec. Enable Bluetooth
and grant the requested Bluetooth permissions (location for scanning on API 29/30).

1. Sender: tap Send File and choose a file of at least 64 KiB with a recognizable
   name. Confirm the sender advertises. Receiver: tap Receive.
2. Receiver must show the public OFFER sender name, file count and byte count
   received over GATT. As of A4, no filename is shown before CLAIM.
3. Within the ten-second claim window, tap Receive this file. Confirm both phones
   show the **same four decimal digits** and the receiver shows the filename from
   authenticated OFFER_DETAILS. Record each displayed code below. Merely
   seeing the receiver prompt is insufficient; sender must also show its code.
4. Compare the codes before tapping Accept. Both phones should reach the linking
   screen. Pairing/code agreement alone is not proof of a file transfer. Step 14+
   wires actual LAN setup after encrypted LINK_INFO; use its separate file/hash
   checklist below. Wi-Fi Direct is wired as of Step 16; hotspot remains Step 17.
5. Reset both apps and repeat, choosing Deny at the receiver prompt. Receiver
   should show cancellation; sender should show receiver-denied failure.
6. Repeat Cancel/Back to Home and discover again. Record any missing OFFER,
   mismatched code, stuck prompt, permission error or Bluetooth operation failure.

| Observation | Result |
| --- | --- |
| Public GATT OFFER totals and post-CLAIM decrypted filename matched selected file | PENDING |
| Sender displayed code | Not recorded |
| Receiver displayed code | Not recorded |
| Both four-digit codes matched | PENDING |
| Explicit Accept reached linking on both phones | PENDING |
| Deny reached both terminal outcomes | PENDING |
| Cancel/reset allowed a new handshake | PENDING |

Step 13 now implements server CCCD subscription, callback-paced operation queues,
negotiated MTU and per-peer fragment state. This checklist must still be executed
on physical phones. If the OFFER/codes do not arrive, record FAIL/BLOCKED and the
observed behavior; do not infer pairing PASS from build/tests. The Step 12
physical-phone acceptance criterion remains pending until model/version and the
matching-code result are recorded.

## Step 13: exact 1 KiB request/reply over GATT

Status: **PENDING - no physical-device output recorded.** Use two physical phones
and the same debug APK. This is a GATT diagnostic, not a bulk file transfer.
The harness is opt-in and ignored by release builds. It sends an exact 1024-byte
typed request and verifies an exact 1024-byte reply with the same token. Its dummy
endpoint never enters the session machines. No credentials or payload are logged.

Run `adb devices`, replace both serial placeholders, then execute in PowerShell:

```powershell
$senderPhone = 'SENDER_SERIAL'
$receiverPhone = 'RECEIVER_SERIAL'
foreach ($phone in @($senderPhone, $receiverPhone)) {
    adb -s $phone install -r .\android\build\outputs\apk\debug\android-debug.apk
    adb -s $phone shell am force-stop com.yodrop.android
    adb -s $phone logcat -c
    adb -s $phone shell am start -n com.yodrop.android/.MainActivity `
        --ei yodrop_ble_mtu 23 --ez yodrop_gatt_probe true
}
```

1. Grant permissions and enable Bluetooth on both phones. Sender chooses a file
   (at least 64 KiB); receiver taps Receive, then Receive this file to CLAIM. The client sends the probe
   automatically after subscription/HELLO on this Claim reconnect. Do not count the normal CLAIM or small HELLO as a 1 KiB
   probe. Keep both apps foreground.
2. Capture receiver logs and require **GATT_PROBE PASS requestBytes=1024
   replyBytes=1024 actualMtu=23**, plus CCCD confirmation:

```powershell
adb -s $receiverPhone logcat -d -s 'YoDropBLE:D' '*:S'
adb -s $senderPhone logcat -d -s 'YoDropBLE:D' '*:S'
```

3. Force-stop both apps, clear logs and repeat the launch script with
   `--ei yodrop_ble_mtu 247`. Repeat file selection/Receive/Receive this file. Require a new PASS line
   and record actual MTU. Android 14+ can choose **517** for the first MTU request;
   a request for 247 does not prove MTU 247. Record 517 separately and leave exact
   247 pending unless a device pair actually negotiates 247.
4. Disable diagnostics by force-stopping both apps and launching without either
   extra. Repeat Step 12's full OFFER / tap CLAIM / matching codes / ACCEPT / DENY
   checklist. Reset/retry after a BLE disconnect; do not expect automatic adoption
   of a different advertising phone.
5. With three phones available, advertise from two senders and verify that receiver
   remains pinned to its first selected peer/session and does not mix messages.
   Record selected peer names and observations; this is supplementary to the
   deterministic per-peer isolation unit test.

| Device test | Models / Android versions | Actual MTU | Result / log |
| --- | --- | --- | --- |
| 1024-byte request + reply, requested MTU 23 | Not recorded | Not recorded | PENDING |
| 1024-byte request + reply, requested MTU 247 | Not recorded | Not recorded | PENDING |
| Optional Android 14+ MTU 517 result | Not recorded | Not recorded | PENDING |
| Step 12 matching codes after GATT fixes | Not recorded | Not applicable | PENDING |
| Disconnect/reset then new handshake | Not recorded | Not recorded | PENDING |

Record manufacturer/model and Android/API using Settings or:

```powershell
adb -s $senderPhone shell getprop ro.product.model
adb -s $senderPhone shell getprop ro.build.version.release
adb -s $senderPhone shell getprop ro.build.version.sdk
```

Repeat for receiver. A missing PASS line, timeout, mismatched bytes or wrong actual
MTU is not a pass. Save the observed error and rerun after correction.

## Other pending physical checks

### Step 14: authenticated sender endpoint on the same LAN

Status: **PENDING - no physical Android transfer result recorded.**

1. Install the same Step 14+ debug APK on both phones. Launch normally without
   diagnostic extras. Connect both to the same Wi-Fi/LAN, with client isolation
   disabled where possible. Enable Bluetooth and grant requested permissions.
2. Send a recognizable file of at least 1 MiB, tap Receive this file and compare
   both four-digit codes before Accept. The sender should choose LAN from the
   exchanged current IPv4 metadata and its active interface prefix.
3. After Accept, sender creates its listener and encrypted LINK_INFO; receiver
   decrypts before TCP connection. Confirm both reach Done and the received file
   appears in Downloads/YoDrop. Compare file SHA-256 with the original (copy both
   to the PC and use Get-FileHash if needed). Record bytes and results below.
4. Repeat Deny and confirm no file/endpoint setup occurs. Reset both apps between
   attempts. Authentication/tamper rejection is covered by JVM tests; do not claim
   a physical tamper exercise without a controlled BLE capture/injection setup.
5. Repeat the earlier GATT probe/matching-code checks on the updated APKs. Probe
   now uses reserved diagnostic tag 7F, not plaintext LINK_INFO tag 06; its debug
   launch flags and exact 1024-byte PASS output remain unchanged.

| Observation | Sender / receiver models, Android versions, result |
| --- | --- |
| Same-LAN encrypted endpoint + successful file transfer | PENDING |
| Original and received file SHA-256 matched | PENDING |
| Deny prevented endpoint/data setup | PENDING |
| Matching-code/GATT probe regression on updated APKs | PENDING |

LAN and Android Wi-Fi Direct endpoint setup are now wired. Hotspot still awaits
Step 17, and small BLE payload data is later work. A pairing/code
match on different networks does not establish those data links. No hotspot
credentials should be printed in logcat, error messages or state dumps.

- Step 3: X25519 instrumentation on a real API 29 device.
- Step 13: 1 KiB GATT round trip at default MTU and negotiated MTU 247.
- Later transport steps: real Wi-Fi Direct/hotspot lifecycle, throughput and resume.


## Step 15 / A4: UUID-only discovery and read/disconnect/reconnect

Status: **PENDING — no scanner capture or physical-device result recorded.**
Install the same Step 15+ APK on both phones; old APK UUIDs/schema are incompatible.
Keep apps foreground. Use a BLE scanner app on a separate phone where available.

1. With sender Idle, scanner must not see the YoDrop service. Select a file to
   enter Grabbed; filter scanner by service UUID
   `965ff8a1-0a45-47ab-aca5-46d565d63e84`. Inspect raw AD: Flags plus one complete
   128-bit service UUID, 21 bytes total; no filename, sessionId, local name or
   service/manufacturer data. Empty scan response. Record the raw capture.
2. Leave an offer unclaimed. It must stop by the original 60-second deadline.
   Repeat Cancel and Done/Deny paths: no remaining advertisement/connection.
3. Receiver taps Receive: it connects, requests MTU 247 (record actual MTU), reads
   the full public OFFER via GATT notifications, caches it, and disconnects before
   waiting for Claim. UI shows sender name/count/bytes, without filename or Accept.
   Confirm disconnection using scanner/Android Bluetooth diagnostics.
4. Tap Receive this file within the receiver's claim window. Confirm reconnect to
   the same sender, matching verification codes, and filename appearing only in
   the accept prompt after encrypted tag 07. Deny and confirm sender receives it.
   Repeat Accept on the same LAN and the Step 14 file/hash checks.
5. With a third phone, attempt another connection while a read or claim connection
   is active. It must be rejected; no mixed offers/keys. An unclaimed read followed
   by disconnection must allow another reader, without failing the sender.
6. Repeated scan callbacks must not create parallel reads. Reset clears cached
   offers; TTL is 60 seconds. A peer's new session is not identifiable from its
   service-only advertisement until reread (TTL/reset).
7. Repeat the 1 KiB diagnostic at MTU 23 and requested 247 using the updated steps
   above. Android 14+ may report 517; record actual 247 only if negotiated.

| Check | Models / OS / date / observed result |
| --- | --- |
| Raw UUID-only advertisement <=31 bytes, empty scan response | PENDING |
| Idle absent; Grabbed present; original 60s expiry and terminal stop | PENDING |
| Read/cache/disconnect, explicit Claim reconnect to same peer | PENDING |
| No filename before CLAIM; decrypted filename and matching codes after | PENDING |
| One active GATT connection; discovery disconnect permits next reader | PENDING |
| Actual MTU and fragmented probe | PENDING |
| Updated same-LAN transfer and SHA-256 | PENDING |

No iOS physical discovery or background advertising result is claimed.

## Step 16: physical Android Wi-Fi Direct (P4 transport acceptance)

Status: **PENDING — no physical Wi-Fi Direct result recorded.**
Use the same Step 16+ APK on two physical Wi-Fi Direct phones. Keep both foreground
(the foreground-service work is Step 18). Enable Bluetooth, Wi-Fi and Location
Mode; grant Nearby Wi-Fi on API 33+, or fine location on API 29–32. Do not turn off
Wi-Fi to avoid LAN selection: disconnect both phones from infrastructure Wi-Fi,
or place them on different subnets, while leaving Wi-Fi radios enabled.

1. Select a real file >=64 KiB (small BLE payload data is later work). Receive,
   Claim, compare codes, then Accept. No WFD group should exist before Accept.
   Capture logs on each phone:

   ```powershell
   adb -s SENDER_SERIAL logcat -d -s 'YoDropWFD:D' '*:S'
   adb -s RECEIVER_SERIAL logcat -d -s 'YoDropWFD:D' '*:S'
   ```

   Require P2P_READY owner=true on sender and owner=false on receiver. On 5 GHz
   hardware, record frequencyMHz in the 5 GHz range; an AUTO/2.4 GHz result is not
   proof of the 5 GHz test. Settings/adb dumpsys wifip2p can corroborate role/group;
   do not publish raw dumps containing private credentials.
2. Transfer a **1 GiB file**, time the data transfer and final verification, record
   exact bytes and seconds, and compare original/received SHA-256 on PC:

   ```powershell
   Get-FileHash -Algorithm SHA256 'D:\test-data\original.bin'
   Get-FileHash -Algorithm SHA256 'D:\test-data\received.bin'
   # Substitute measured duration; decimal MB/s = bytes / 1,000,000 / seconds.
   $elapsedSeconds = 70.0
   (Get-Item 'D:\test-data\original.bin').Length / 1000000 / $elapsedSeconds
   ```

   Require >=15 MB/s on the recorded 5 GHz pair. Record failure/band/duration if
   below target; JVM loopback speed is not an Android throughput result.
3. Repeat with a new 1 GiB session. Around 40% progress, briefly toggle receiver
   Wi-Fi off/on in Settings; keep Bluetooth/apps foreground. Restore it within the
   existing reconnect budget. Expect Reconnecting then resume on the same session,
   without selecting the file or Accept again; final SHA-256 must match. Repeat
   toggling sender Wi-Fi, verifying sender remains owner after group recreation.
   If the owner IP changes or the deadline expires, record that failure and reset;
   the app must not guess a new endpoint or reuse credentials for a new peer.
4. Repeat Cancel while creating a group, Cancel while joining, Cancel mid-transfer,
   Deny before setup, and normal Done. Require no active group/discovery after each
   terminal path and P2P_CLEANUP confirmed=true for a group that was started. A
   'Group removal not confirmed' warning is a failed/unconfirmed cleanup result.
5. Disable Wi-Fi/Location or deny the relevant runtime permission. Expect a defined
   cancellation/error and no data transfer or residual group. Re-enable/grant and
   reset before retry. Do not expect the app to enable Wi-Fi itself.
6. Try with a pre-existing unrelated P2P group. Setup must reject it without removing
   or adopting it. Do not measure background/screen-off endurance in this step.

| Check | Sender/receiver model, API, date, evidence/result |
| --- | --- |
| API 29/32 permissions and sender owner / receiver client | PENDING |
| API 33/34+ Nearby Wi-Fi permission and actual group | PENDING |
| Actual 5 GHz frequency | PENDING |
| 1 GiB exact bytes, duration, MB/s >=15, SHA-256 | PENDING |
| Receiver Wi-Fi toggle: same session resumed, SHA-256 | PENDING |
| Sender Wi-Fi toggle: recreated owner group, resumed, SHA-256 | PENDING |
| Cancel/create/join/transfer, Deny, Done: group cleanup | PENDING |
| Denied permission / Wi-Fi off / Location off | PENDING |
| Unrelated existing group preserved | PENDING |
