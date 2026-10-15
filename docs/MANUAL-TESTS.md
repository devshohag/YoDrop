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
2. Receiver must show the sender's HELLO name and the full OFFER filename, file
   count and byte count received over GATT, not a decoded/truncated advertisement.
3. Within the ten-second claim window, tap Receive this file. Confirm both phones
   show the **same four decimal digits**. Record each displayed code below. Merely
   seeing the receiver prompt is insufficient; sender must also show its code.
4. Compare the codes before tapping Accept. Both phones should reach the linking
   screen. This step does not create a bulk data connection; endpoint/encrypted
   LINK_INFO and actual data-link setup are later work. Do not label this a file
   transfer success; the existing linking deadline still applies.
5. Reset both apps and repeat, choosing Deny at the receiver prompt. Receiver
   should show cancellation; sender should show receiver-denied failure.
6. Repeat Cancel/Back to Home and discover again. Record any missing OFFER,
   mismatched code, stuck prompt, permission error or Bluetooth operation failure.

| Observation | Result |
| --- | --- |
| Full GATT OFFER metadata matched selected file | PENDING |
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
   (at least 64 KiB); receiver taps Receive. The client sends the probe automatically
   after subscription/HELLO. Do not count the normal CLAIM or small HELLO as a 1 KiB
   probe. Keep both apps foreground.
2. Capture receiver logs and require **GATT_PROBE PASS requestBytes=1024
   replyBytes=1024 actualMtu=23**, plus CCCD confirmation:

```powershell
adb -s $receiverPhone logcat -d -s 'YoDropBLE:D' '*:S'
adb -s $senderPhone logcat -d -s 'YoDropBLE:D' '*:S'
```

3. Force-stop both apps, clear logs and repeat the launch script with
   `--ei yodrop_ble_mtu 247`. Repeat file selection/Receive. Require a new PASS line
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

- Step 3: X25519 instrumentation on a real API 29 device.
- Step 13: 1 KiB GATT round trip at default MTU and negotiated MTU 247.
- Later transport steps: real Wi-Fi Direct/hotspot lifecycle, throughput and resume.
