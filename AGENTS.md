# AGENTS.md

## Cursor Cloud specific instructions

SecureMessenger is an Android (Kotlin/Compose) messaging app; multi-module Gradle
project (`app` + `core:*` + `data` + `protocol:*`, including a Telegram/TDLib protocol
module). Standard commands and architecture live in `README.md`, `docs/`, and
`.github/workflows/ci.yml`.

The Cloud VM is already provisioned: OpenJDK 17 and 21, the Android SDK at
`$HOME/android-sdk` (env vars exported in `~/.bashrc`), and a generated
`local.properties` (gitignored) with `sdk.dir` plus `telegram.api.id=0` /
`telegram.api.hash=` (empty stub values are sufficient to build and test).

- **JDK: use 21.** CI builds SecureMessenger with Temurin 21. Run Gradle with
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- **TDLib prerequisite:** the `protocol:telegram` module needs a prebuilt TDLib AAR at
  `protocol/telegram/libs/tdlib-core-<version>.aar` (gitignored). Fetch it with
  `./scripts/fetch-tdlib-prebuilt.sh` (idempotent; skips if present). The build fails
  without it. The startup/update flow runs this automatically.
- **Build (debug APK):** `./gradlew :app:assembleDebug -Psecuremessenger.devAbi=arm64-v8a --no-daemon`
  → `app/build/outputs/apk/debug/app-debug.apk`.
- **Unit tests:** `./gradlew test --no-daemon` (fast).
- **Lint:** `./gradlew :app:lintDebug --no-daemon`.

Non-obvious caveats:
- `android.ndkVersion` is set in `gradle.properties`, but the app links the prebuilt
  TDLib `.so`s from the AAR via `abiFilters` only — **no NDK/CMake compilation happens**,
  so the NDK does not need to be installed to build the APK.
- Real Telegram connectivity needs valid `telegram.api.id`/`telegram.api.hash`
  (https://my.telegram.org). They are not required for building or unit tests.
- `compileSdk`/`targetSdk` are **37**; installed SDK platforms are `android-37.0` /
  `android-37.1` (no plain `platforms;android-37`). AGP 9.1.1 handles this — do not change it.
- Headless VM with **no `/dev/kvm`** → no Android emulator. Verify via unit tests and by
  inspecting the built APK (`$ANDROID_SDK_ROOT/build-tools/37.0.0/aapt dump badging <apk>`).
