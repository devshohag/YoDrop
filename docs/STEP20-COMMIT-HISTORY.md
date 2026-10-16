# Actual Steps 1-19 git history

Captured from main 4fb3bd4. Command:

```bash
git log --reverse --stat --format=fuller ef0b818..4fb3bd4
```

This is the actual existing history, not a rewritten one-commit-per-step claim.
Step 1 has three commits (665976e, a742e5c, effef98). Steps 2-5 and Step 6
landed together in 3d18f95; its changed-file list contains portability, shared
X25519, protocol/state guards and timeout work. Steps 7-19 have individual commits.
The remediation plan's one-commit-per-step history requirement was therefore not
met for Steps 1-6. Preserve history and record this deviation; no rebase/force-push
was performed. Historical pre-remediation P7/P8/P1-9 completion titles are not
accepted as implementation evidence.

```text
commit 665976e455e09bb895cf8d02ec8c7288580ec212
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Thu Oct 15 17:27:18 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Thu Oct 15 17:27:18 2026 +0600

    step1: fix android build and scoped storage sink

 .../main/kotlin/com/yodrop/android/MainActivity.kt | 321 ++++++++++++++-------
 .../com/yodrop/android/ble/AndroidBleManager.kt    |  27 +-
 .../kotlin/com/yodrop/android/ui/MainScreen.kt     | 236 +++++++--------
 gradle.properties                                  |   4 +
 4 files changed, 354 insertions(+), 234 deletions(-)

commit a742e5cbc3525bd3cc44dfc3067dfb703c372266
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Thu Oct 15 17:32:42 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Thu Oct 15 17:32:42 2026 +0600

    step1: restore Android build and receive flow

 .../main/kotlin/com/yodrop/android/MainActivity.kt | 374 +++++++++------------
 1 file changed, 151 insertions(+), 223 deletions(-)

commit effef9866c48790f584cb4565c355392a96ee2cf
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Thu Oct 15 17:37:39 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Thu Oct 15 17:37:39 2026 +0600

    step1: restore Android build and receive flow

 .../main/kotlin/com/yodrop/android/MainActivity.kt | 72 ++++++++++++----------
 1 file changed, 41 insertions(+), 31 deletions(-)

commit 3d18f95b7cbf553fe1ecf387f8deb6fbcb96dcdb
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Thu Oct 15 22:48:16 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Thu Oct 15 22:48:16 2026 +0600

    step6: inject session timeouts and verify sender receiver deadlines

 .../main/kotlin/com/yodrop/android/MainActivity.kt | 177 ++++++++--
 .../kotlin/com/yodrop/android/ui/MainScreen.kt     |   9 +-
 core/build.gradle.kts                              |  18 +-
 .../yodrop/core/crypto/X25519Instrumentation.kt    |  35 ++
 .../com/yodrop/core/crypto/CryptoEngine.android.kt | 191 ----------
 .../com/yodrop/core/platform/DiskFileIo.android.kt |  40 ++-
 .../yodrop/core/platform/SocketPlatform.android.kt |   9 +-
 .../kotlin/com/yodrop/core/crypto/CryptoEngine.kt  |   3 +-
 .../kotlin/com/yodrop/core/crypto/VerifyCode.kt    |  23 ++
 .../kotlin/com/yodrop/core/platform/FileIo.kt      |  56 ++-
 .../com/yodrop/core/protocol/ProtocolBounds.kt     |  75 ++++
 .../kotlin/com/yodrop/core/protocol/TcpFrames.kt   |  44 +--
 .../yodrop/core/session/ReceiverStateMachine.kt    | 366 +++++++++++++++----
 .../com/yodrop/core/session/SenderStateMachine.kt  | 351 +++++++++++++------
 .../com/yodrop/core/session/SessionPolicy.kt       |  47 +++
 .../com/yodrop/core/session/SessionStates.kt       |  35 +-
 .../com/yodrop/core/session/SocketIdleRead.kt      |  50 +++
 .../kotlin/com/yodrop/core/session/Timeouts.kt     |  19 +
 .../kotlin/com/yodrop/core/transfer/Chunking.kt    |  41 ++-
 .../kotlin/com/yodrop/core/Step2PortabilityTest.kt |  70 ++++
 .../core/crypto/CryptoEngine.jvmAndAndroid.kt      | 122 +++++++
 .../com/yodrop/core/crypto/CryptoEngine.jvm.kt     | 191 ----------
 .../com/yodrop/core/platform/DiskFileIo.jvm.kt     |  40 ++-
 .../com/yodrop/core/platform/SocketPlatform.jvm.kt |   9 +-
 .../jvmTest/kotlin/com/yodrop/core/Phase2Tests.kt  |   6 +-
 .../kotlin/com/yodrop/core/Phase4SocketTests.kt    |   7 +-
 .../Phase4SocketTests.kt.step5-20261015-215014.bak | 111 ++++++
 .../kotlin/com/yodrop/core/Step4ProtocolTest.kt    | 260 ++++++++++++++
 .../kotlin/com/yodrop/core/Step5StateGuardTest.kt  | 201 +++++++++++
 .../com/yodrop/core/T1RandomSessionIdTest.kt       |   7 +-
 .../com/yodrop/core/T2AuthVerificationTest.kt      | 185 ++++------
 .../com/yodrop/core/T5StreamingFileIoTest.kt       |   5 +-
 .../com/yodrop/core/T6PersistentResumeTest.kt      |  26 +-
 .../com/yodrop/core/T8TimeoutsAndCleanupTest.kt    | 387 +++++++++++++++++----
 .../com/yodrop/core/crypto/Step3X25519Test.kt      |  13 +
 .../kotlin/com/yodrop/core/crypto/X25519Checks.kt  | 125 +++++++
 docs/ARCHITECTURE.md                               |  12 +
 docs/DECISIONS.md                                  |  93 ++++-
 gradle.properties                                  |   8 +-
 gradle/libs.versions.toml                          |   2 +
 scripts/check-common-main.ps1                      |  18 +
 41 files changed, 2590 insertions(+), 897 deletions(-)

commit cbd514134c469ec456b3dbc550a9233972058dec
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Thu Oct 15 23:07:25 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Thu Oct 15 23:07:25 2026 +0600

    step7: preserve reconnectable sessions and add liveness cleanup

 .../main/kotlin/com/yodrop/android/MainActivity.kt |   4 +
 .../com/yodrop/core/platform/DiskFileIo.android.kt |   6 +
 .../kotlin/com/yodrop/core/platform/FileIo.kt      |   9 +
 .../yodrop/core/session/ReceiverStateMachine.kt    | 303 +++++++++-------
 .../com/yodrop/core/session/SenderStateMachine.kt  | 148 +++++---
 .../com/yodrop/core/session/SessionConnection.kt   | 132 +++++++
 .../com/yodrop/core/session/SessionPolicy.kt       |  12 +-
 .../com/yodrop/core/session/SessionStates.kt       |   4 +-
 .../com/yodrop/core/session/SocketIdleRead.kt      |   9 +-
 .../com/yodrop/core/platform/DiskFileIo.jvm.kt     |   6 +
 .../kotlin/com/yodrop/core/Step7LifecycleTest.kt   | 380 +++++++++++++++++++++
 .../com/yodrop/core/T8TimeoutsAndCleanupTest.kt    |   9 +-
 docs/DECISIONS.md                                  |  42 ++-
 docs/STEP7-VALIDATION.md                           |  58 ++++
 14 files changed, 939 insertions(+), 183 deletions(-)

commit c25a292113e2cb7c8c922bd5f8e79cf6907646fb
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Thu Oct 15 23:24:11 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Thu Oct 15 23:24:11 2026 +0600

    step8: persist resume state atomically and verify real TCP recovery

 .../main/kotlin/com/yodrop/android/MainActivity.kt |   5 +
 .../com/yodrop/core/platform/DiskFileIo.android.kt |  41 ++-
 .../core/platform/DiskResumeStore.android.kt       |  92 ++++-
 .../kotlin/com/yodrop/core/platform/FileIo.kt      |   8 +
 .../yodrop/core/session/ReceiverStateMachine.kt    |  33 +-
 .../com/yodrop/core/platform/DiskFileIo.jvm.kt     |  41 ++-
 .../yodrop/core/platform/DiskResumeStore.jvm.kt    |  92 ++++-
 .../kotlin/com/yodrop/core/Step8ResumeTest.kt      | 382 +++++++++++++++++++++
 docs/DECISIONS.md                                  |  48 +++
 docs/STEP7-VALIDATION.md                           |  13 +
 docs/STEP8-VALIDATION.md                           |  70 ++++
 11 files changed, 772 insertions(+), 53 deletions(-)

commit c8fe3a735911d318462742e8a387b6d423bcbead
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Thu Oct 15 23:49:23 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Thu Oct 15 23:49:23 2026 +0600

    step9: add parallel receiver windows and resilient NACK retries

 .../com/yodrop/core/session/ReceiveCoordinator.kt  | 207 +++++++++++
 .../yodrop/core/session/ReceiverStateMachine.kt    | 264 +++++---------
 .../com/yodrop/core/session/SenderStateMachine.kt  |  50 ++-
 .../com/yodrop/core/session/SessionConnection.kt   |  29 +-
 .../com/yodrop/core/session/SessionPolicy.kt       |   2 +
 .../kotlin/com/yodrop/core/Step5StateGuardTest.kt  |   6 +-
 .../com/yodrop/core/T7PipeliningAndStreamsTest.kt  | 391 ++++++++++++---------
 docs/DECISIONS.md                                  |  53 ++-
 docs/STEP8-VALIDATION.md                           |  15 +
 docs/STEP9-VALIDATION.md                           |  62 ++++
 10 files changed, 704 insertions(+), 375 deletions(-)

commit 1320128cb6031ac1f6ed063d4ea73ac839f7d3fe
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 00:22:59 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 00:22:59 2026 +0600

    step10: verify 1 GiB TCP transfer with 256 MiB heap

 core/build.gradle.kts                              |   2 +
 .../com/yodrop/core/Step10LargeTransferTest.kt     | 234 +++++++++++++++++++++
 docs/STEP10-VALIDATION.md                          |  69 ++++++
 3 files changed, 305 insertions(+)

commit c9006c958267344fb71a10bb2156f67c014f5fe8
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 00:41:45 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 00:41:45 2026 +0600

    step11: select sender transport using peer capabilities and real subnet

 .../transport/AndroidSenderTransportSelector.kt    |  48 +++++
 .../android/transport/WifiDirectTransport.kt       |  42 +----
 .../kotlin/com/yodrop/core/crypto/README.kt        |   3 -
 .../kotlin/com/yodrop/core/platform/README.kt      |   3 -
 .../kotlin/com/yodrop/core/protocol/README.kt      |   3 -
 .../kotlin/com/yodrop/core/session/README.kt       |   3 -
 .../com/yodrop/core/session/SenderStateMachine.kt  |  26 +++
 .../crossplatform/CrossPlatformTransferManager.kt  |  38 ----
 .../kotlin/com/yodrop/core/transfer/README.kt      |   3 -
 .../com/yodrop/core/transport/LanTransport.kt      |  22 +--
 .../kotlin/com/yodrop/core/transport/README.kt     |   3 -
 .../com/yodrop/core/transport/TcpConnection.kt     |  12 ++
 .../com/yodrop/core/transport/TransportSelector.kt | 111 +++++------
 .../transport/crossplatform/CrossPlatformBridge.kt |  39 ----
 .../transport/crossplatform/HotspotCredentials.kt  |  12 ++
 .../kotlin/com/yodrop/core/Phase5SelectorTests.kt  |  35 ++--
 .../kotlin/com/yodrop/core/Step11TransportTest.kt  | 204 +++++++++++++++++++++
 docs/DECISIONS.md                                  |  58 ++++++
 docs/STEP11-VALIDATION.md                          |  81 ++++++++
 19 files changed, 530 insertions(+), 216 deletions(-)

commit 69c6863dcba14f38f535cd70e5c343cd7c68627b
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 00:59:33 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 00:59:33 2026 +0600

    step12: frame BLE control messages and wire tap handshake flow

 .../main/kotlin/com/yodrop/android/MainActivity.kt | 150 ++++++++++--
 .../com/yodrop/android/ble/AndroidBleManager.kt    | 201 +++++++++-------
 .../kotlin/com/yodrop/android/ui/MainScreen.kt     |  33 ++-
 .../com/yodrop/core/protocol/BleMessageCodec.kt    |  82 +++++++
 .../kotlin/com/yodrop/core/protocol/BleMessages.kt |   3 +-
 .../yodrop/core/session/BleHandshakeController.kt  | 100 ++++++++
 .../yodrop/core/session/ReceiverStateMachine.kt    |  12 +-
 .../com/yodrop/core/session/SenderStateMachine.kt  |   2 +-
 .../com/yodrop/core/session/SessionStates.kt       |   4 +-
 .../kotlin/com/yodrop/core/Step11TransportTest.kt  |   2 +-
 .../kotlin/com/yodrop/core/Step12BleTest.kt        | 253 +++++++++++++++++++++
 docs/DECISIONS.md                                  |  41 ++++
 docs/MANUAL-TESTS.md                               |  58 +++++
 docs/STEP12-VALIDATION.md                          |  75 ++++++
 14 files changed, 906 insertions(+), 110 deletions(-)

commit d9c406395342f688565753250d910e0725fcf3bd
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 01:22:10 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 01:22:10 2026 +0600

    step13: pace GATT operations with callbacks and isolate peer fragments

 .../main/kotlin/com/yodrop/android/MainActivity.kt |   6 +-
 .../com/yodrop/android/ble/AndroidBleManager.kt    | 557 ++++++++++++++-------
 .../com/yodrop/core/protocol/BleFragmenter.kt      | 142 +++---
 .../com/yodrop/core/protocol/BlePeerLinks.kt       |  65 +++
 .../yodrop/core/protocol/GattDiagnosticProbe.kt    |  35 ++
 .../yodrop/core/transport/GattOperationQueue.kt    |  63 +++
 .../kotlin/com/yodrop/core/Step13GattTest.kt       | 288 +++++++++++
 docs/DECISIONS.md                                  |  54 ++
 docs/MANUAL-TESTS.md                               |  76 ++-
 docs/STEP13-VALIDATION.md                          |  79 +++
 10 files changed, 1111 insertions(+), 254 deletions(-)

commit 52342ea14a3c973339dded8f3746a875192a836f
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 01:57:09 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 01:57:09 2026 +0600

    step14: encrypt sender-owned BLE link info and authenticate receiver endpoints

 .../main/kotlin/com/yodrop/android/MainActivity.kt |  28 +-
 .../com/yodrop/android/ble/AndroidBleManager.kt    |   5 +-
 .../android/transport/AndroidSenderLinkSetup.kt    |  64 +++++
 .../transport/AndroidSenderTransportSelector.kt    |   2 +-
 .../com/yodrop/core/crypto/LinkInfoCipher.kt       |  56 ++++
 .../com/yodrop/core/protocol/BleMessageCodec.kt    |  27 +-
 .../kotlin/com/yodrop/core/protocol/BleMessages.kt |  29 +-
 .../yodrop/core/session/BleHandshakeController.kt  |  23 +-
 .../yodrop/core/session/ReceiverStateMachine.kt    |  22 +-
 .../com/yodrop/core/session/SenderStateMachine.kt  |  18 +-
 .../yodrop/core/transport/SenderLinkInfoFactory.kt |  16 ++
 .../transport/crossplatform/HotspotCredentials.kt  |   4 +-
 .../kotlin/com/yodrop/core/Step12BleTest.kt        |  11 +-
 .../kotlin/com/yodrop/core/Step14LinkInfoTest.kt   | 301 +++++++++++++++++++++
 docs/DECISIONS.md                                  |  53 ++++
 docs/MANUAL-TESTS.md                               |  33 +++
 docs/STEP14-VALIDATION.md                          |  74 +++++
 17 files changed, 731 insertions(+), 35 deletions(-)

commit 78ac65594c255fdd6f1aaa8bc5ca93e71d909656
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 09:50:09 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 09:50:09 2026 +0600

    step15: apply A4 UUID discovery and encrypted offer details

 .../main/kotlin/com/yodrop/android/MainActivity.kt |  13 +-
 .../com/yodrop/android/ble/AndroidBleManager.kt    | 120 ++++++++---
 .../kotlin/com/yodrop/android/ui/MainScreen.kt     |  11 +-
 .../com/yodrop/core/crypto/OfferDetailsCipher.kt   |  56 +++++
 .../yodrop/core/protocol/BleAdvertisingPacket.kt   |  15 ++
 .../com/yodrop/core/protocol/BleMessageCodec.kt    |  14 +-
 .../kotlin/com/yodrop/core/protocol/BleMessages.kt |  20 +-
 .../com/yodrop/core/protocol/BleOfferCache.kt      |  32 +++
 .../com/yodrop/core/protocol/BlePeerLinks.kt       |  16 +-
 .../kotlin/com/yodrop/core/protocol/Uuids.kt       |   8 +-
 .../yodrop/core/session/BleHandshakeController.kt  |  38 +++-
 .../yodrop/core/session/ReceiverStateMachine.kt    |  40 ++--
 .../com/yodrop/core/session/SenderStateMachine.kt  |  23 +-
 .../com/yodrop/core/session/SessionPolicy.kt       |   2 +-
 .../com/yodrop/core/session/SessionStates.kt       |   5 +-
 .../jvmTest/kotlin/com/yodrop/core/Phase2Tests.kt  |   6 +-
 .../kotlin/com/yodrop/core/Phase3BleTests.kt       |   6 +-
 .../kotlin/com/yodrop/core/Phase4SocketTests.kt    |   3 +-
 .../com/yodrop/core/Step10LargeTransferTest.kt     |   3 +-
 .../kotlin/com/yodrop/core/Step11TransportTest.kt  |   2 +-
 .../kotlin/com/yodrop/core/Step12BleTest.kt        |  10 +-
 .../kotlin/com/yodrop/core/Step13GattTest.kt       |  10 +-
 .../kotlin/com/yodrop/core/Step15DiscoveryTest.kt  | 232 +++++++++++++++++++++
 .../kotlin/com/yodrop/core/Step4ProtocolTest.kt    |   5 +-
 .../kotlin/com/yodrop/core/Step5StateGuardTest.kt  |  26 +--
 .../kotlin/com/yodrop/core/Step7LifecycleTest.kt   |   6 +-
 .../kotlin/com/yodrop/core/Step8ResumeTest.kt      |   9 +-
 .../com/yodrop/core/T1RandomSessionIdTest.kt       |   2 +-
 .../com/yodrop/core/T2AuthVerificationTest.kt      |   5 +-
 .../com/yodrop/core/T5StreamingFileIoTest.kt       |   3 +-
 .../com/yodrop/core/T6PersistentResumeTest.kt      |   3 +-
 .../com/yodrop/core/T7PipeliningAndStreamsTest.kt  |   3 +-
 .../com/yodrop/core/T8TimeoutsAndCleanupTest.kt    |   9 +-
 docs/ARCHITECTURE.md                               |  37 +++-
 docs/DECISIONS.md                                  |  48 +++++
 docs/MANUAL-TESTS.md                               |  64 +++++-
 docs/STEP15-VALIDATION.md                          |  91 ++++++++
 37 files changed, 846 insertions(+), 150 deletions(-)

commit 3c7465d24fd60d2677ba2516ee92cbcc3a13eea5
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 10:13:51 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 10:13:51 2026 +0600

    step16: implement Wi-Fi Direct groups, cleanup and reconnect

 android/src/main/AndroidManifest.xml               |   5 +-
 .../main/kotlin/com/yodrop/android/MainActivity.kt |  36 ++--
 .../android/transport/AndroidReceiverLinkSetup.kt  |  79 +++++++
 .../android/transport/AndroidSenderLinkSetup.kt    |  42 +++-
 .../android/transport/AndroidWifiDirectPlatform.kt | 180 ++++++++++++++++
 .../android/transport/WifiDirectTransport.kt       |  49 ++++-
 .../kotlin/com/yodrop/android/ui/MainScreen.kt     |   4 +-
 .../yodrop/core/session/ReceiverStateMachine.kt    |   2 +-
 .../com/yodrop/core/transport/WifiDirectSession.kt | 130 ++++++++++++
 .../kotlin/com/yodrop/core/Step16WifiDirectTest.kt | 235 +++++++++++++++++++++
 docs/DECISIONS.md                                  |  46 ++++
 docs/MANUAL-TESTS.md                               |  70 +++++-
 docs/STEP16-VALIDATION.md                          |  98 +++++++++
 13 files changed, 940 insertions(+), 36 deletions(-)

commit 7aad03b15039677649569686b374ef0e8ec3565a
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 10:29:26 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 10:29:26 2026 +0600

    step17: fix hotspot configuration, actual gateway and cleanup

 .../main/kotlin/com/yodrop/android/MainActivity.kt |  13 +-
 .../android/transport/AndroidSenderLinkSetup.kt    |  61 +++++--
 .../transport/hotspot/AndroidHotspotManager.kt     | 121 ++++++++------
 .../android/transport/hotspot/HotspotProbe.kt      |  81 ++++++++++
 .../com/yodrop/core/transport/HotspotSession.kt    | 114 +++++++++++++
 .../kotlin/com/yodrop/core/Step17HotspotTest.kt    | 180 +++++++++++++++++++++
 docs/DECISIONS.md                                  |  42 +++++
 docs/MANUAL-TESTS.md                               |  65 +++++++-
 docs/STEP17-VALIDATION.md                          |  84 ++++++++++
 9 files changed, 697 insertions(+), 64 deletions(-)

commit 8a55f02adc7d6a3b5e14a6f16470b83cdfeffc9a
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 10:55:09 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 10:55:09 2026 +0600

    step18: add foreground transfer lifecycle and runtime permissions

 android/src/main/AndroidManifest.xml               |   3 +-
 .../main/kotlin/com/yodrop/android/MainActivity.kt | 337 +++++----------------
 .../com/yodrop/android/ble/AndroidBleManager.kt    |  11 +-
 .../android/service/TransferForegroundService.kt   | 137 +++++++--
 .../com/yodrop/android/service/TransferRuntime.kt  | 165 ++++++++++
 .../yodrop/android/storage/AndroidFileStorage.kt   | 127 ++++++++
 .../com/yodrop/core/session/ForegroundSession.kt   |  51 ++++
 .../kotlin/com/yodrop/core/Step18ForegroundTest.kt | 218 +++++++++++++
 docs/DECISIONS.md                                  |  47 +++
 docs/MANUAL-TESTS.md                               |  73 +++++
 docs/STEP18-VALIDATION.md                          |  70 +++++
 scripts/check-android-manifest.ps1                 |  41 +++
 12 files changed, 992 insertions(+), 288 deletions(-)

commit 4fb3bd451abe0442519d149a73e0d3fccaefdbb5
Author:     devshohag <shohagislam.csm@gmail.com>
AuthorDate: Fri Oct 16 11:17:31 2026 +0600
Commit:     devshohag <shohagislam.csm@gmail.com>
CommitDate: Fri Oct 16 11:17:31 2026 +0600

    step19: stream selected documents to verified Downloads and bind LAN routing

 .../main/kotlin/com/yodrop/android/MainActivity.kt | 147 ++--------
 .../yodrop/android/storage/AndroidFileStorage.kt   | 158 ++++------
 .../android/storage/AndroidSelectedDocuments.kt    | 129 +++++++++
 .../android/transport/AndroidLanTransport.kt       |  32 +++
 .../android/transport/AndroidReceiverLinkSetup.kt  |  21 +-
 .../android/transport/AndroidSenderLinkSetup.kt    |   1 +
 .../transport/AndroidSenderTransportSelector.kt    |  43 +--
 .../kotlin/com/yodrop/android/ui/MainScreen.kt     |  11 +-
 .../com/yodrop/core/platform/PublishingFileSink.kt |  30 ++
 .../com/yodrop/core/platform/RangeFileSource.kt    |  77 +++++
 .../com/yodrop/core/platform/SelectedFileName.kt   |  17 ++
 .../kotlin/com/yodrop/core/Step19FilesTest.kt      | 319 +++++++++++++++++++++
 docs/ARCHITECTURE.md                               |  12 +
 docs/DECISIONS.md                                  |  52 ++++
 docs/MANUAL-TESTS.md                               |  68 +++++
 docs/STEP19-VALIDATION.md                          |  81 ++++++
 16 files changed, 950 insertions(+), 248 deletions(-)
```
