# Signal vendor stack (libsignal-service + libsignal-client)

SecureMessenger integrates Signal via:

| Component | Source |
|-----------|--------|
| `libsignal-client` / `libsignal-android` | [Signal Maven](https://build-artifacts.signal.org/libraries/maven/) (`org.signal:*`) |
| `libsignal-service` + JVM core deps | Vendored from [signalapp/Signal-Android](https://github.com/signalapp/Signal-Android) (AGPL-3.0) |

## Sync vendor sources

```bash
./scripts/sync-signal-vendor.sh
```

Pin a release tag:

```bash
SIGNAL_ANDROID_REF=v7.49.0 ./scripts/sync-signal-vendor.sh
```

Synced modules land under `vendor/signal/`:

- `util-jvm` — from `core/util-jvm`
- `models-jvm` — from `core/models-jvm`
- `network-jvm` — from `core/network`
- `libsignal-service` — from `lib/libsignal-service`
- `wire-handler/wire-handler-1.0.0.jar` — Wire proto codegen
- `overlays/` — SecureMessenger Tor SOCKS holder + restored attachment/certificate helpers (applied after every sync)

`VERSION` records the git ref. Re-run the script after bumping `libsignal-client` in `gradle/libs.versions.toml`.

## Build

```bash
./gradlew :vendor:signal:libsignal-service:build
./gradlew :protocol:signal:assembleDebug
```

## License note

Vendored Signal-Android code is **AGPL-3.0**. Combining it in the same distributed APK may require SecureMessenger to adopt AGPL. See project `LICENSE` before shipping Signal support.

## CI

`ci.yml` and `release.yml` call `sync-signal-vendor.sh` before Gradle builds.
