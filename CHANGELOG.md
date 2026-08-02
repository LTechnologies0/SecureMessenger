# Changelog

## 1.0.6

### Dependencies
- Library and CI Actions updates via Dependabot (AGP/Kotlin/Compose/Room/Hilt/OkHttp/Jackson and related).
- Signal vendor overlays adapted for Jackson KotlinModule and Okio 3.18 `utf8Appendable`.

## 1.0.4

### Network / Tor
- Orbot / InviZible **retirés** ; Tor via OnionVPN PAC uniquement
  (`http://127.0.0.1:18201/onionvpn.pac` → SOCKS `:18202`) ; `CUSTOM` pour SOCKS manuel
- Migration prefs `ORBOT`/`INVIZIBLE` → `ONIONVPN`
- Quand Tor est activé : **tous** les protocoles (Signal inclus) passent par le pont SOCKS

### Signal
- Lien d’appareil secondaire (**QR**) : scannable depuis Signal principal
  (Paramètres → Appareils liés) ; inscription SMS inchangée
- version `1.0.4` (versionCode 8)

## 1.0.3

### Network / Tor
- Routage Tor par défaut via **OnionVPN PAC** (`http://127.0.0.1:18201/onionvpn.pac` → `:18202`)
- version `1.0.3` (versionCode 7)

## 1.0.2

### App lock
- Fix bouton « Déverrouiller » grisé : `MainActivity` est maintenant un
  `FragmentActivity` (requis par `BiometricPrompt`), comme OnionVPN
- Fonctionne sur profils privés Android ; prompt PIN / schéma / biométrie
- Bypass « Continuer sans verrouillage » si Keyguard reporte insecure
- version `1.0.2` (versionCode 6)

## 1.0.0-alpha.5

### UI
- « Ajouter un compte » : liste **scrollable**, **Signal en premier**, libellés explicites
  (Signal était hors écran sous Telegram — pas de scroll)

### Network / Signal
- Hérité de 1.0.0-alpha.4 (Signal clearnet, Tor optionnel, killswitch retiré)
- version `1.0.0-alpha.5` (versionCode 5)

## 1.0.0-alpha.4

### Network
- **Killswitch Tor retiré** : plus de blocage global quand SOCKS est down
- **Tor optionnel** (`torRequired` défaut `false`) : clearnet par défaut ; SOCKS opt-in pour Matrix / XMPP / Telegram
- Switch « Routage Tor » réactivé dans Paramètres → Proxy

### Signal
- **Accès Signal sans Tor** : plus de `SignalTor` / SOCKS / `Network.setProxy` (Signal bloque beaucoup d’exits Tor)
- Inscription / sync / envoi en clearnet ; UI et messages mis à jour
- Fix UI : observation des étapes captcha → SMS → PIN pendant tout le flux (plus bloqué après le captcha)
- Erreurs réseau d’inscription remontées en `ConnectionResult.Failure` (plus confondues avec l’étape SMS)
- version `1.0.0-alpha.4` (versionCode 4)

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
