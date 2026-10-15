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

Known dependency: Step 13 has not yet implemented server CCCD subscription,
callback-paced operation queues, negotiated MTU or per-peer fragment state.
These can block notification delivery even when codec/controller unit tests pass.
If the OFFER/codes do not arrive, record BLOCKED and the observed behavior; do not
mark pairing PASS. Rerun this checklist after Step 13's GATT work. The Step 12
physical-phone acceptance criterion remains pending until model/version and the
matching-code result are recorded.

## Other pending physical checks

- Step 3: X25519 instrumentation on a real API 29 device.
- Step 13: 1 KiB GATT round trip at default MTU and negotiated MTU 247.
- Later transport steps: real Wi-Fi Direct/hotspot lifecycle, throughput and resume.
