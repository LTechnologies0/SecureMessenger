# Changelog

## 1.0.17

### Honesty & capability contract
- `ProtocolCapabilities` : `reactions`, `messageEdit`, `messageDelete`, `messageForward`, `messageReply`, `localChatListControls`
- UI long-press / attach gated by capabilities (plus de hardcode TG/Matrix seul)
- Signal : `stickers=true` ; `stories` gardé false avec fail-closed ; réactions + remote-delete DM
- Telegram : édition / suppression TDLib ; ephemeral fail-closed si flag false
- Email : **Bcc** SMTP/JMAP ; honesty JMAP sans pièces jointes
- Inbox : pin/mute/archive **local** pour tous les protocoles (TDLib pour Telegram)
- XMPP : empreintes OMEMO via puce E2EE
- IRC : `/me` → CTCP ACTION ; onglet Contacts → « Nicks »
- Affichage réactions depuis `payloadJson.reactions`

### Still P3 / volontaire
RingRTC, SVR2/reg lock, Matrix QR cross-signing, secret chats TG, OpenPGP, archive IMAP complète, stories UI.

## 1.0.16

### UX / feature parity (deferred pass 2)
- **Telegram** : épingler, couper notifications ([SetChatNotificationSettings](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1_set_chat_notification_settings.html)), archiver/désarchiver ([AddChatToList](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1_add_chat_to_list.html) + listes Main/Archive) ; état pin/mute/archive en Room ; appui long dans l’inbox.
- **Email IMAP** : sync read-only du dossier Sent (~50 msgs / UID incremental) après INBOX, sans IDLE Sent ; détection noms courants (Sent, Gmail Sent Mail, etc.) ; bannières mises à jour quand Sent est actif.

### Known gaps (unchanged)
- RingRTC / appels, Signal SVR2 & verrouillage d’inscription, Matrix QR cross-signing, stories Signal, chats secrets Telegram.

## 1.0.15

### UX / feature parity (deferred pass)
- **Email** : champ Cc actif (SMTP + JMAP) ; honnêteté Sent (sync IMAP Sent absente — envois locaux après envoi app).
- **Matrix** : réactions, édition et redaction depuis appui long (Trixnity `react` / `replace` / CS redact).
- **Signal** : numéro de sécurité 1:1 via puce E2EE (libsignal fingerprint) ; SVR2 / verrouillage d’inscription toujours absents.

### Known gaps (unchanged)
- Appels A/V (RingRTC), stories Signal, sync dossier Sent IMAP, vérification clé Matrix (QR cross-signing).
- Telegram mute / pin / archive (pas de surface Room/TDLib action côté app).

## 1.0.14

### Known gaps (unchanged)
- Signal : SVR2 / verrouillage d’inscription non exposés dans l’UI.
- Appels A/V (RingRTC), stories Signal, dossiers Sent IMAP dédiés, vérification clé Matrix (QR).

### UX / feature parity (Compose)
- **Telegram**: sticker picker (packs installés TDLib); réactions + transfert + citation depuis appui long; capability `stickers` activée quand autorisé.
- **Chat**: E2EE/OMEMO par conversation (XMPP/Signal/Matrix); bannières Signal link-and-sync; champs mail À/Objet + honnêteté fenêtre récente.
- **Inbox / contacts**: empty states et FAB libellés par protocole; bannières Email/IRC sur limites d’historique.

### P2 / UX (post-audit)
- **Telegram**: outbound messages keyed by TDLib message id (reconcile with `UpdateMessageSendSucceeded`); `SetTdlibParameters` errors surfaced; `capabilitiesFor` hides composer features until authorized.
- **IRC**: server `CASEMAPPING` from negotiation; per-account `capabilitiesFor` (contacts when connected).
- **ConnectionManager**: app lock bumps restore epoch so in-flight restore/connect stops before DB close.
- **UI**: Matrix chat shows when E2EE is unavailable; Telegram phone lookup gated on live contacts capability.

### Audit remédiation (Critical / High)
- **Telegram**: AddProxy before RemoveProxy (no clearnet window on live clients); ephemeral send fixed (`awaitResult` null=OK); audio / video-note attachments downloadable.
- **Matrix**: whoami / login `device_id` authoritative over stale store; user_id reconciled with whoami; crypto wipe only on store/crypto signals (not transient Tor errors).
- **Signal**: `sessionReady` only after register/link Success; provisional wipe on cancel/fail; one-time prekey replenishment + signed rotation; SOCKS auth on CDN/provisioning OkHttp.
- **Email**: IMAP IDLE serialized with markSeen (one folder per Store); store-first then SMTP DoH; invalidate SMTP auth on send fail; Tor DoH when `torRequired`; sendMessage/sendMedia remap conversationId; JMAP clears session on 401/403.
- **XMPP**: HTTP media fail-closed without SOCKS; OMEMO listener before login; Room ERROR on reconnect fail; refuse inbound cleartext when peer OMEMO; conversations filtered by protocol.
- **IRC**: Ready after negotiation + auth; SASL CAP NAK fast-fail; refuse SASL/NickServ without TLS; Netty EventLoop shutdown hook.
- **ConnectionManager**: `continueRegistration` Failure if connect fails; Room ERROR on connect Failure; wipe restore also deletes Room row; single killswitch debounce job.
- **UnifiedMessengerClient / protocols**: observeConversations filtered by ProtocolId (no N× Room flatten).

## 1.0.13

### Connection / Tor restore (Phase 0)
- **ConnectionManager**: restore on `null → healthy` SOCKS (unlock while Tor was down); pending auth requires matching `accountId`; post-Success Room stays `CONNECTED` when session live; register → Failure if post-register connect fails.
- **Telegram / Signal Room honesty**: soft-disconnect writes `DISCONNECTED`/`CONNECTING` (not fake `CONNECTED`); wipe keeps provisioned secrets.
- **FGS**: killswitch/restore uses `isNetworkAllowed()`; **UnifiedMessengerClient** aligns Tor rewrite with `resolveProxyOrFail`.
- **Shell**: prefer last / first `CONNECTED` account; unread badge per rail account.

### Email (Phase 2)
- Local `storeAuthenticated` (Angus `Store.isConnected` NOOP removed); IDLE/poll after CONNECTED; SMTP via DoH IP; OkHttp DoH SOCKS remoteDns; peer address for replies; JMAP method errors → Failure.

### Matrix (Phase 1)
- Persist register `deviceId`; token paste + whoami; store token mismatch → wipe + soft-login; SSO cancel clears Room; skip `fetchFlows` with token; `sendText` failure when disconnected.

### XMPP (Phase 3)
- `smack-omemo-signal` + per-account store; OMEMO state machine; MAM/live carbons decrypt when READY; Tor media SOCKS auth; roster merge preserves preview/unread.

### Signal (Phase 4) / IRC (Phase 5)
- Link-and-sync cancel-safe; suspend cancel + await wipe; same-id reconnect shutdown; occupy → Room ERROR; SOCKS user/pass.
- IRC: SOCKS5 auth pipeline; Success after SASL 903 / NickServ; auto-join after auth; case-fold; aggregate connection state.

### Telegram polish (Phase 6)
- Per-account pending-auth map; `AddProxy` clears prior proxies; TDLib DB key only from Keystore-backed credential store.

## 1.0.9

### Multi-account aggregation
- **ConnectionManager**: persist `CONNECTING` vs `CONNECTED` from per-account readiness (`isAccountConnected` + pending auth scoped to that `accountId`); parallel restore; per-account disconnect; do not wipe live Telegram/Signal mid-auth.
- **Auth routing**: `AuthStep.accountId`; `pendingAuth(protocol, accountId)`; Telegram continue/resend/republish for sibling SMS/2FA; Matrix SSO cancel scoped per account.
- **Signal**: refuse second connect/link while `CONNECTING`/`CONNECTED`; `cancelRegistration(accountId)` does not kill sibling; QR `startDeviceLink` returns provisional id; leave/cancel tears down unconnected link fully; AuthSteps carry `accountId`.
- **Send/session**: Email/XMPP/Telegram/Signal resolve session from conversation/`accountId` (no multi-session `singleOrNull`); Unified inbox merges all protocol flows; account-scoped `capabilitiesFor`.
- **UI**: Settings capabilities per account; Chat/NewChat/Shell wired; fail-fast `startConversation` when multi-account and no `accountId`.

### Bug fixes (connect / sync)
- **Signal link-and-sync**: long-poll `GET /v1/devices/transfer_archive` until archive/error (404/204 = not ready); await restore **before** WS drain / storage sync (Signal-Android order); 1h deadline; validate backup fail-closed; merge contacts during restore; advertise official `AppCapabilities` (incl. attachmentBackfill); provision `readReceipts`; 429 backoff.
- **Telegram**: `LoadChats` until TDLib 404; history pages until **empty** (short page ≠ EOF); `authorizationReady` gates connected; login wait 180s under Tor; **WaitEmailAddress/WaitEmailCode** + UI; **UpdateChatPosition** (leave main list → drop conversation).
- **Matrix**: persist/reuse real CS `device_id` for soft-login E2EE; **password re-login sends stored device_id**; login-flows fail-closed; SSO cancel; reuse existing DM before create; multi-pass `fillTimelineGaps`; encryption `initial_state` on new DMs/groups.
- **XMPP OMEMO/MAM**: disco timeout ≠ plaintext; await device-list publish; MAM `pageNext` catchup (+ since local max); keep helper when publish pending; inbound/MAM media via that account’s Smack session.
- **IRC**: connect timeout 90s (Tor); Netty HTTP codec on classpath.

### Security / dependencies
- **Netty**: bump to **4.1.137.Final** (CVE-2026-42580 HTTP request smuggling; IRC / Kitteh path).

## 1.0.8

### Security / control-flow
- **IRC TLS**: use Android/JVM system `TrustManagerFactory` (Libera / Let’s Encrypt work).
- **Matrix E2EE fail-closed**: no plaintext HTTP `/sync` before Trixnity; session published only when E2EE is live; plaintext send path removed.
- **Matrix SSO**: pending SSO keyed per account (refuse concurrent SSO for another account).
- **XMPP OMEMO**: block cleartext while OMEMO initializes; geolocation via OMEMO when peer supports it; MAM fail-closed on OMEMO payloads.
- **Email Tor DNS**: DoH-over-SOCKS resolve + SNI to original hostname (no local DNS under `remoteDns`).
- **Email IMAP**: mutex around IDLE/sync/markSeen; unread count only bumps for new messages.
- **Telegram**: cancel login tears down only that account; registration step after SMS; preserve display name on disconnect.
- **Signal**: refuse second live session / device-link clobber; capability flags match reality (no fake A/V/stories/GIF).
- **IRC UI**: SASL vs NickServ toggle; TLS/port stay coherent (6697/6667).

### Docs
- Libera example + system trust note in `docs/irc.md`.

## 1.0.7

### Bug fixes
- Room crash on unlock / add-account (v1.0.6): `contacts.accountId` index from migration 3→4
  is declared on `ContactEntity` (schema v5), so Expected/Found indices match.
- IRC login: remove duplicate Kitteh `connect()` that always failed.
- App packaging: exclude Netty `META-INF/io.netty.versions.properties` so release/debug APKs assemble.
- Email autoconfig: invalid address no longer leaves the detect button stuck busy.

### Protocols
- **Email** (merged #9): IMAP / POP3 / JMAP + SMTP with autoconfig, Tor SOCKS.
- **IRC** (Kitteh): TLS/cleartext, SOCKS5 via Tor, NickServ/SASL PLAIN, channels + DMs,
  NOTICE/TOPIC/JOIN/PART/KICK as system messages, auto-join channels, contacts from NAMES.
- Signal secondary-device gaps (link-sync import, call/story signaling) from #9.

### Docs
- Architecture / README updated for Email + IRC; new `docs/irc.md`.

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
