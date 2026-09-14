# TDLib / Telegram native setup

SecureMessenger needs `libtdjni.so` plus matching Java bindings (`org.drinkless.tdlib`).

## 1. Android SDK + NDK

Copy `local.properties.example` to `local.properties` and set your paths:

```properties
sdk.dir=/home/user/Android/Sdk
ndk.dir=/home/user/Android/Sdk/ndk/26.1.10909125
```

Install NDK and CMake if missing:

```bash
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1" "platforms;android-34"
```

`gradle.properties` pins `android.ndkVersion=26.1.10909125` for reproducible builds.

## 2. Prebuilt TDLib (recommended)

Fastest path — no C++ compile, no gperf/php:

```bash
chmod +x scripts/fetch-tdlib-prebuilt.sh
./scripts/fetch-tdlib-prebuilt.sh
./gradlew :app:assembleDebug -x validateSigningDebug
```

This downloads `protocol/telegram/libs/tdlib-core-0.1.0.aar` (all ABIs + Java API).
`:protocol:telegram` **refuses** the Maven JAR stub (no natives) and auto-fetches the AAR when missing.

Debug APKs package `arm64-v8a` + `x86_64` by default so Waydroid/x86 emulators get `libtdjni.so`
(`-Psecuremessenger.devAbis=arm64-v8a` to override).

## 3. Build from source (optional)

If you need a specific TDLib commit or custom flags:

```bash
# Fedora deps
sudo dnf install gperf php-cli

chmod +x scripts/build-tdlib.sh
./scripts/build-tdlib.sh
```

Copies `libtdjni.so` into `protocol/telegram/src/main/jniLibs/{abi}/`.
Build time: 30–90 minutes depending on CPU.

Official reference: [TDLib Android example](https://github.com/tdlib/td/tree/master/example/android).

## 4. Verify

After install, APK must contain `libtdjni.so`:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libtdjni
# Expect at least:
#   lib/arm64-v8a/libtdjni.so
#   lib/x86_64/libtdjni.so
```

In the app, Telegram connect should no longer show `libtdjni.so missing`.

## 5. API credentials

Users supply `api_id` and `api_hash` from [my.telegram.org](https://my.telegram.org).
Stored in EncryptedSharedPreferences — never hardcode.

## 7. Version / CVE notes

- Runtime natives come from **prebuilt AAR** `tdlib-android` **v0.1.0** (`scripts/fetch-tdlib-prebuilt.sh`), not from the Maven `ca.denisab85:tdlib` stub (`v1.8.8-20221107` in the version catalog).
- The Maven coordinate remains only as a historical compile fallback and is **API-incompatible** with `:protocol:telegram` — CI always fetches the AAR.
- To pick up newer Telegram TDLib security fixes: rebuild with `scripts/build-tdlib.sh` against current [tdlib/td](https://github.com/tdlib/td), or bump `TDLIB_ANDROID_VERSION` when a newer signed prebuilt release is published.
- Prefer SOCKS5 via OnionVPN PAC before `setTdlibParameters` (see §6).
