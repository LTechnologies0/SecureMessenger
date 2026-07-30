# SecureMessenger

<p align="center">
  <strong>Messagerie Android multi-protocole — Matrix, XMPP, Telegram et Signal dans une seule app, Tor optionnel via OnionVPN.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="GPL-3.0"></a>
  <a href="https://github.com/LTechnologies0/SecureMessenger/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/LTechnologies0/SecureMessenger/ci.yml?branch=main" alt="CI"></a>
  <a href="https://github.com/LTechnologies0/SecureMessenger/releases"><img src="https://img.shields.io/github/v/release/LTechnologies0/SecureMessenger?include_prereleases" alt="Release"></a>
  <a href="https://ltechnologies0.github.io/SecureMessenger/"><img src="https://img.shields.io/badge/docs-KDoc%20(Dokka)-blue" alt="API docs"></a>
  <img src="https://img.shields.io/badge/minSdk-26-green" alt="minSdk 26">
  <img src="https://img.shields.io/badge/compileSdk-37-green" alt="compileSdk 37">
  <img src="https://img.shields.io/badge/Matrix-Trixnity-teal" alt="Matrix">
  <img src="https://img.shields.io/badge/XMPP-Smack-amber" alt="XMPP">
  <img src="https://img.shields.io/badge/Telegram-TDLib-blue" alt="Telegram">
  <img src="https://img.shields.io/badge/Signal-libsignal-indigo" alt="Signal">
</p>

**SecureMessenger** is an open-source Android messenger that unifies **Matrix**, **XMPP**, **Telegram**, and **Signal** in one Material 3 inbox — contacts, history, rich media (voice, location, polls, GIFs where the protocol supports them), typing, read receipts, profile edit, and local JSON backup. Part of the [OnionPhone](https://onionphone.org) app family.

> Tor routing is **optional** (off by default). When enabled, traffic uses the [OnionVPN](https://github.com/LTechnologies0/OnionVPN) PAC bridge
> (`http://127.0.0.1:18201/onionvpn.pac` → SOCKS5 `127.0.0.1:18202`). Orbot / InviZible are not used.

**Keywords / topics:** `android` · `kotlin` · `jetpack-compose` · `material-design` · `messenger` · `matrix` · `xmpp` · `telegram` · `signal` · `tdlib` · `tor` · `privacy` · `onionphone`

---

## Table of contents

- [Features](#features)
- [Architecture](#architecture)
- [Install from GitHub Releases](#install-from-github-releases)
- [Build it yourself](#build-it-yourself)
- [API documentation (KDoc)](#api-documentation-kdoc)
- [Security](#security)
- [Contributing](#contributing)
- [License](#license)

---

## Features

| Feature | Description |
|---------|-------------|
| **Multi-protocol inbox** | Matrix, XMPP, Telegram, and Signal accounts side by side |
| **Unified messaging** | Text, images, files, GIFs, voice notes, location, polls, contact cards, stickers/ephemeral where the protocol API allows |
| **Contacts & profile** | Protocol contacts (roster / GetContacts / CDSI / room members), display-name (and bio/photo where supported) |
| **History** | Telegram TDLib history, XMPP MAM, Matrix timeline backfill; Signal live + device sync (no cloud 1:1 backfill) |
| **Typing & read receipts** | Outbound + inbound typing (Matrix/XMPP/Telegram); read markers / receipts where supported |
| **Signal link (QR)** | Register by SMS or link an existing account as a secondary device |
| **Account registration** | Matrix (UIA / WebView) and XMPP (XEP-0077) account creation |
| **Optional Tor** | OnionVPN PAC SOCKS for all protocols when Tor is enabled; custom SOCKS optional |
| **Local backup** | Per-account JSON export of conversations/messages |
| **Encrypted at rest** | SQLCipher DB + Keystore-backed credentials; system app lock |
| **Material 3 UI** | Jetpack Compose, expressive motion, capability chips per protocol |

### Network flow

```mermaid
flowchart LR
    UI[Compose UI] --> CM[ConnectionManager]
    CM --> Matrix[protocol:matrix]
    CM --> XMPP[protocol:xmpp]
    CM --> TG[protocol:telegram]
    CM --> Signal[protocol:signal]
    Matrix & XMPP & TG -->|Tor opt-in| Proxy[core:proxy — SOCKS5]
    Matrix & XMPP & TG -->|default| Clearnet((Clearnet))
    Signal --> Clearnet
    Proxy -->|Tor enabled + healthy| Tor((Tor network))
```

---

## Architecture

12-module Gradle project — see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full module graph and registration flow details.

```
:app
 ├── :core:model     (shared data classes)
 ├── :core:proxy     (SOCKS5 resolution)
 ├── :core:network   (Tor killswitch, WebView proxying)
 ├── :core:security  (encrypted credential storage)
 ├── :data           (persistence)
 └── :protocol:api   (MessengerProtocol interface)
      ├── :protocol:xmpp      (Smack)
      ├── :protocol:matrix    (raw CS API + Trixnity)
      ├── :protocol:telegram  (TDLib JNI)
      └── :protocol:signal
```

| Layer | Technologies |
|-------|--------------|
| UI | Jetpack Compose, Material 3, Navigation Compose |
| DI | Dagger Hilt |
| Async | Kotlin Coroutines + Flow |
| Networking | Ktor (Matrix), OkHttp/WebView (proxied), Smack (XMPP), TDLib (Telegram) |
| Storage security | AndroidX Security Crypto (Keystore-backed encrypted storage) |
| Proxy | SOCKS5 → Tor, enforced at the network layer, not just per-request |

---

## Install from GitHub Releases

1. Open **[Releases](https://github.com/LTechnologies0/SecureMessenger/releases)**.
2. Download the APK matching your device CPU:

   | APK suffix | Devices |
   |------------|---------|
   | `arm64-v8a` | Most modern phones (2017+) |
   | `armeabi-v7a` | Older 32-bit ARM phones |
   | `x86_64` | Emulators, some tablets |
   | `x86` | Older emulators |

3. Enable **Install unknown apps** for your browser/files app.
4. Install the APK.
5. On first launch, add a Telegram account requires your own free API credentials from [my.telegram.org](https://my.telegram.org) if you build from source (release APKs from CI already bundle build-time credentials configured by the maintainer).

> Release APKs are signed with the project release key when CI secrets are configured. Verify the signature with `apksigner verify`.

---

## Build it yourself

### Prerequisites

| Tool | Version |
|------|---------|
| JDK | **21** (Temurin recommended) |
| Android SDK | API **37**, Build-Tools **36+** |
| Android NDK | **26.1.10909125** (only needed for Telegram/TDLib — see [docs/tdlib-build.md](docs/tdlib-build.md)) |
| Gradle | **9.x** (wrapper included) |

Set `ANDROID_HOME` / `ANDROID_SDK_ROOT` and create `local.properties` from the example:

```bash
cp local.properties.example local.properties
# Edit sdk.dir / ndk.dir, and telegram.api.id / telegram.api.hash (see below)
```

Telegram support needs your own free API credentials from [my.telegram.org](https://my.telegram.org) — see [docs/tdlib-build.md](docs/tdlib-build.md) for the full TDLib setup (prebuilt AAR fetch or build-from-source).

### One-shot commands by OS

<details>
<summary><strong>Linux / macOS</strong></summary>

```bash
# Clone
git clone https://github.com/LTechnologies0/SecureMessenger.git && cd SecureMessenger
cp local.properties.example local.properties   # then edit paths/credentials

# Debug APK (arm64-v8a, fastest)
./gradlew :app:assembleDebug

# All unit tests
./gradlew test

# Release APKs — all 4 ABIs (unsigned without keystore)
./gradlew :app:assembleRelease

# Release APKs — signed locally
cp keystore.properties.example keystore.properties
# Edit keystore.properties, then:
./gradlew :app:assembleRelease

# Install debug on connected device
./gradlew :app:installDebug

# API documentation
./gradlew dokkaGenerate
# → build/dokka/html/index.html
```

</details>

<details>
<summary><strong>Windows (PowerShell)</strong></summary>

```powershell
git clone https://github.com/LTechnologies0/SecureMessenger.git; cd SecureMessenger
Copy-Item local.properties.example local.properties   # then edit paths/credentials

# Debug APK
.\gradlew.bat :app:assembleDebug

# Tests
.\gradlew.bat test

# Release (all ABIs)
.\gradlew.bat :app:assembleRelease

# Install debug
.\gradlew.bat :app:installDebug

# Docs
.\gradlew.bat dokkaGenerate
```

</details>

### Output paths

| Task | Output |
|------|--------|
| `assembleDebug` | `app/build/outputs/apk/debug/app-<abi>-debug.apk` |
| `assembleRelease` | `app/build/outputs/apk/release/app-<abi>-release.apk` |
| `dokkaGenerate` | `build/dokka/html/index.html` |
| `test` | `**/build/reports/tests/` |

### Local release signing

```bash
bash scripts/generate-release-keystore.sh
cp keystore.properties.example keystore.properties
# Edit paths/passwords — never commit these files
./gradlew :app:assembleRelease
```

---

## API documentation (KDoc)

Public APIs are documented with **KDoc** in source. HTML reference is generated with **[Dokka](https://kotlinlang.org/docs/dokka-introduction.html)**:

```bash
./gradlew dokkaGenerate
# → build/dokka/html/index.html
```

**Live docs** (auto-deployed on push to `main`): [ltechnologies0.github.io/SecureMessenger](https://ltechnologies0.github.io/SecureMessenger/)

---

## Security

- **Tor-only, always**: see [SECURITY.md](SECURITY.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full network-enforcement design.
- **No secrets in repo**: `keystore.properties`, `local.properties`, and keystores are gitignored.
- **Encrypted credential storage**: account secrets stored via Keystore-backed encrypted storage.
- **CI signing**: GitHub Actions secrets only — see [SECURITY.md](SECURITY.md).

| GitHub secret | Purpose |
|---------------|---------|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded keystore |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Enable in repo settings: Dependabot alerts, secret scanning, push protection, CodeQL.

---

## Contributing

1. Fork and create a feature branch from `main`.
2. Run `./gradlew test :app:assembleDebug` before pushing.
3. Add KDoc for new public APIs.
4. Open a PR — CI runs tests, debug build, CodeQL, and dependency review.

See [CHANGELOG.md](CHANGELOG.md) for release history.

---

## License

**GPL-3.0-or-later** — see [LICENSE](LICENSE).

Smack, Trixnity, TDLib, and other third-party SDKs are licensed separately under their own terms.
