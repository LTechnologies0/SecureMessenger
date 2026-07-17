# Changelog

## 1.0.0-alpha.3

### Cross-cutting
- App lock (PIN / biométrie système) + SQLCipher DB gated on unlock
- `Attachment` model on `Message`; Room v3 migration; chat UI media picker
- `MessengerProtocol.sendMedia()` + `MainViewModel.sendMedia()`
- **Discord retiré** (module, DI, FeatureFlags, docs)
- version `1.0.0-alpha.3` (versionCode 3)

### Tor (toutes plateformes)
- Inscriptions Matrix / XMPP / Signal / Telegram : fail-closed via `ConnectionManager` + `NetworkGuard`
- WebView Tor proxy pour UIA / SSO Matrix

### Telegram
- Tor fail-closed avant auth TDLib; multi-account `TelegramSession`
- Médias Photo/Video/Document/Voice; `sendMedia` / `downloadFile`
- Debug ABI `arm64-v8a` + `x86_64`; Gradle refuse stub Maven sans `libtdjni.so` + auto-fetch AAR

### Matrix
- Trixnity E2EE fail-closed (soft-login access token; wipe+retry; stop HTTP `/sync` si Trixnity live)
- Store crypto H2 + `fromStore` reconnect
- SSO OIDC (`m.login.sso`) → Tor WebView → `loginToken` → soft-login
- Médias mxc via Trixnity

### Signal
- Store libsignal + WebSocket receive + send
- Médias CDN upload/download
- GV2 sender-key (+ fan-out chiffré si endorsements absents)
- Vendor sync script + Tor SOCKS patches

### XMPP
- MUC + bookmarks + HTTP upload + MAM
- OMEMO 1:1 + MUC **fail-closed** (pas de fallback cleartext si OMEMO annoncé)

## 1.0.0-alpha.2

- Release CI: Telegram API secrets required; TDLib kept in R8
- See GitHub release notes for ABI APKs

## 1.0.0-alpha.1

- Intermediate pre-release build

## 1.0.0-alpha

- Greenfield MVP: Tor-only multi-protocol secure messenger
- Modules: `:core:model`, `:core:proxy`, `:core:network`, `:core:security`, `:data`, `:protocol:api`, `:protocol:xmpp`, `:protocol:matrix`, `:protocol:telegram`, `:protocol:signal`, `:app`
- Protocols: Matrix (raw Client-Server API + Trixnity crypto), XMPP (Smack), Telegram (TDLib), Signal
- Tor-only network enforcement with killswitch (`NetworkGuard`)
- Matrix: `.well-known/matrix/client` delegated homeserver discovery
- Matrix registration: `m.login.dummy`, `m.login.registration_token`, and Tor-routed WebView fallback for UIA stages
- XMPP registration: XEP-0077 in-band registration via Smack `AccountManager`
- Per-account SOCKS5 stream isolation for XMPP
- Compose UI + Encrypted credential storage
