# P6 dependencies, model and modifications

CameraX 1.4.2 (core/camera2/lifecycle/view) is from AndroidX/AOSP. Its published
POMs describe Apache 2.0 and, for camera-core's libyuv component, BSD licensing.
MediaPipe Tasks Vision/Core 0.10.32 are from The MediaPipe Authors under Apache
2.0. Existing dependency versions and Android SDK levels are unchanged.

- [CameraX release notes](https://developer.android.com/jetpack/androidx/releases/camera)
- [MediaPipe source at v0.10.32](https://github.com/google-ai-edge/mediapipe/tree/v0.10.32)
- [Hand Landmarker Android guide](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/android)
- [MediaPipe privacy notice](https://developers.google.com/edge/mediapipe/solutions/tasks#mediapipe_tasks_privacy_notice)

## Packaged model

Hand Landmarker full, float16, version 1; the bundle contains palm detection and
hand landmarks models. The official documentation's linked model card specifies
Apache License 2.0. The model is unmodified and is not a source file in this repo.

Source: https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task

SHA-256: `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`

[Official guide and model card link](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker#models).
`scripts/prepare-p6-model.ps1` fetches this fixed version on the PC, verifies it
and copies it to ignored `android/src/main/assets/hand_landmarker.task`. Gradle
refuses missing/changed bytes. The app performs no model download. Attribution
and the Apache license accompany the asset in the APK.

## MediaPipe binary modification (YoDrop P6, 2026-10-16)

The published SDK enables remote performance/usage metrics. YoDrop's architecture
requires no data collection. `LocalOnlyMediaPipe` in android/build.gradle.kts
therefore transforms the official tasks-core AAR before Android compilation:

1. Verify original TasksStatsLoggerFactory.class SHA-256:
   `fc88676ca1ca1543d6e0b495bbe9c948161f87f51c369eeb6d7478e094aa8944`.
2. Change its two constant-pool references from TasksStatsProtoLogger to the
   bundled TasksStatsDummyLogger, whose methods are no-ops. Its public return
   type remains the existing TasksStatsLogger interface.
3. Remove TasksStatsProtoLogger.class and RemoteLoggingClient.class. Exclude
   their com.google.android.datatransport transitive dependencies. Other SDK
   classes, native binaries, resources and notices remain unchanged.

This modification is made by YoDrop, not endorsed by the MediaPipe authors. No
post-initialization reflection is used. The transform refuses an unreviewed
version, hash or logging layout; verifyPrivateMediaPipe also checks the resolved
AAR before every app preBuild. Keep the source/model/license notices when
redistributing. Review this transform before any future MediaPipe upgrade.

Local checks used the real official AAR, Gradle 8.14.3 resolution, and a JVM
verification probe that called the rewritten factory with null Android Context
and exercised logger methods. Physical camera/network validation is still in
P6-VALIDATION.md. This note does not establish overall release readiness.
