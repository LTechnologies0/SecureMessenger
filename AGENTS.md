# AGENTS.md

## Cursor Cloud specific instructions

This is an Android app (Gradle Kotlin DSL, AGP 9.1.1, Kotlin 2.2.10, JDK 21,
`compileSdk`/`targetSdk` 37, `minSdk` 26) that integrates Telegram (TDLib),
Signal, Matrix, and XMPP. Standard build/run commands are in `README.md` and
`.github/workflows/ci.yml`.

### Environment (already provisioned in the VM snapshot)
- Android SDK lives at `/opt/android-sdk` (`platforms;android-37.0`,
  `build-tools;37.0.0`, `platform-tools`). `ANDROID_HOME`/`ANDROID_SDK_ROOT` are
  exported from `~/.bashrc`.
- `local.properties` (gitignored) already contains `sdk.dir=/opt/android-sdk`
  plus `telegram.api.id=0` / `telegram.api.hash=` placeholders (fine for building;
  real values are only needed to actually connect to Telegram at runtime).
- There is **no KVM / Android emulator** in this VM, so the app cannot be launched
  on a device here. Validate changes the same way CI does: unit tests + a debug APK
  build. Instrumented (`androidTest`) tests cannot run.

### Vendored dependencies (required before building — gitignored, persisted in snapshot)
The build needs two out-of-tree artifacts that are **not** in git (see `.gitignore`:
`protocol/telegram/libs/*.aar`, `vendor/signal/**/src/`). They are already present in
the snapshot; if a fresh checkout is ever missing them, regenerate with:
- `./scripts/fetch-tdlib-prebuilt.sh` — downloads the prebuilt TDLib AAR.
- `./scripts/sync-signal-vendor.sh` — clones `signalapp/Signal-Android` and applies
  overlays into `vendor/signal/` (needs network + `python3`).

### Build & test
- Note the app-specific ABI property prefix (`securemessenger`).
- Debug APK (fastest — single ABI): `./gradlew :app:assembleDebug -Psecuremessenger.devAbi=arm64-v8a --no-daemon`
  → `app/build/outputs/apk/debug/app-debug.apk` (~220 MB; bundles native TDLib libs).
- Own-module unit tests (all pass): `./gradlew :core:security:test :core:proxy:test :data:test :protocol:signal:test :protocol:matrix:test :protocol:xmpp:test :protocol:telegram:test --no-daemon`.

### Gotcha: full `./gradlew test` fails in the vendored Signal module
`:vendor:signal:libsignal-service:compileTestKotlin` fails because the upstream
Signal test sources (synced from `Signal-Android` `main`, a moving target)
reference `assertk`, which the vendored module's `build.gradle.kts` does not
declare. This is a pre-existing upstream/vendor issue, not an environment problem.
Run SecureMessenger's own module test tasks (above) instead of the aggregate `test`.
