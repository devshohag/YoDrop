# Agent instructions — YoDrop

Read `docs/ARCHITECTURE.md` fully before writing code. It is the source of truth.

## Rules
- Work one phase at a time (ARCHITECTURE §9). Start with **P1**.
- A phase is done only when `./gradlew :core:jvmTest` (and later `:android:assembleDebug`) pass and the phase's "Done when" criteria are met.
- After a phase is green: commit directly to `main` with message `P<n>: <summary>`.
- Stack is fixed. No new third-party libraries beyond those in `gradle/libs.versions.toml` (+ MediaPipe Tasks in P6). Use platform APIs / our own code.
- Do NOT use Google Nearby Connections. The transfer protocol is our own.
- Shared logic only in `core/`. `android/` and `ios/` = platform glue + UI.
- Never load a whole file into memory; stream from disk.
- Dependency versions may be bumped only if the build fails on current ones; note it in the commit.
- If the spec is ambiguous, pick the simplest option consistent with ARCHITECTURE.md and note it in `docs/DECISIONS.md`.

## Commands
- Core tests: `./gradlew :core:jvmTest`
- Android build: `./gradlew :android:assembleDebug`
