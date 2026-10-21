# SecureMessenger — Inventaire des fonctionnalités manquantes & incohérences

Document de vérité pour finir le travail. État au commit `4c07b06` (**v1.0.16**), branche `fix/audit-round-1`.

**Légende priorité**

| Tag | Sens |
|-----|------|
| **P0** | Bug / mensonge UI / fail-open sécurité |
| **P1** | Feature attendue d’un client « normal » du protocole, partiellement câblée |
| **P2** | Parité UX confort (original client) |
| **P3** | Gros chantier / hors scope volontaire (à confirmer) |

**Légende état**

- **Absent** : pas d’API protocole + pas d’UI
- **Proto-only** : implémenté côté protocole, UI manquante ou cachée
- **UI-only** : UI présente, backend incomplet ou faux
- **Incohérent** : `ProtocolCapabilities` / docs / comportement divergent
- **Volontaire** : explicitement désactivé (Tor, policy, scope)

---

## 0. Ouvertures process (hors code produit)

| Item | État | Action |
|------|------|--------|
| Merge `fix/audit-round-1` → `main` | **Fait** ([PR #24](https://github.com/LTechnologies0/SecureMessenger/pull/24)) | — |
| Tag/release `v1.0.16` | **Fait** (commit parity + assets) | — |
| APK attaché à la release | **Fait** (`app-debug.apk` ~359 Mo) | Keystore release absent en local |
| `main` version **1.0.16** | **Fait** | — |
| CI fetch TDLib AAR 404 | **Corrigé** (AAR hébergé sur release SM + script) | — |

---

## 1. Matrice capacités déclarées (flags)

Référence : `ProtocolCapabilities` dans `core/model/.../Models.kt`.

| Capacité | TG | Signal | Matrix | XMPP | Email | IRC |
|----------|----|--------|--------|------|-------|-----|
| DM | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Groupes | ✓ | ✓ | ✓ | ✓ MUC | ✗ | ✓ canaux |
| Media send/recv | ✓* | ✓ | ✓ | ✓ | ✓† | ✗ |
| Typing | ✓* | ✓ | ✓ | ✓ | ✗ | ✗ |
| Read receipts | ✓* | ✓ | ✓ | ✓ | ✗ | ✗ |
| E2EE flag | ✗‡ | ✓ | ✓ | dynamique OMEMO | ✗ | ✗ |
| Contacts | ✓* | ✓ | ✓ | ✓ | ✓ peers | ✓ si connecté |
| Profile edit | ✓* | ✓ | ✓ | ✓ | ✗ | ✗ |
| Voice notes | ✓* | ✓ | ✓ | ✓ | ✗ | ✗ |
| Stickers | ✓* | **✗** (proto send existe) | ✗ | ✗ | ✗ | ✗ |
| GIFs | ✓* | ✗ | ✓ | ✓ | ✗ | ✗ |
| Location | ✓* | ✓ | ✓ | ✓ | ✗ | ✗ |
| Polls | ✓* | ✓ | ✗ | ✗ | ✗ | ✗ |
| Contact share | ✓* | ✓ | ✗ | ✓ | ✗ | ✗ |
| Ephemeral | ✗§ | ✓ | ✗ | ✗ | ✗ | ✗ |
| History | ✓* | si link-sync | ✓ | ✓ | fenêtre récente | ✗ |
| Voice/video calls | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| Stories | ✗ | ✗ (sendStory code existe) | ✗ | ✗ | ✗ | ✗ |

\* TG : gated `authorizationReady` via `capabilitiesFor`.  
† Email : media send IMAP/SMTP OK ; **JMAP send rejette** pièces (gated live).  
‡ TG : MTProto cloud ≠ E2EE secret chat ; flag `endToEndEncryption=false` volontaire.  
§ TG : TTL auto-delete chat ≠ ephemeral per-message ; composer ephemeral masqué.

---

## 2. Telegram (vs Telegram Android / TDLib)

### Fait (1.0.16)
Proxy SOCKS add-then-remove, send-id reconciliation, stickers/reactions/forward/reply UI, pin/mute/archive, auth order tests.

### Manquant / incohérent

| ID | Prio | État | Écart vs client officiel |
|----|------|------|--------------------------|
| TG-01 | P3 | Volontaire | **Secret chats** (`useSecretChats=false`) |
| TG-02 | P3 | Absent | **Appels** voix/vidéo (VoIP TDLib / tgcalls) |
| TG-03 | P2 | Absent | Stories / Channels broadcast composer dédié |
| TG-04 | P2 | Absent | Bots : inline keyboards, callback queries, bot menu |
| TG-05 | P2 | Absent | Topics (forum groups), threads |
| TG-06 | P2 | Absent | Message **edit** / **delete** pour tous (aujourd’hui surtout reactions/forward/reply) |
| TG-07 | P2 | Absent | Recherche globale messages / hashtags |
| TG-08 | P2 | Absent | Scheduled messages, silent send, spoiler, custom emoji packs complets |
| TG-09 | P2 | Absent | Folders / chat filters Telegram |
| TG-10 | P1 | Incohérent | `ephemeralMessages=false` mais chemin `OutgoingContent.Ephemeral` + TTL chat existe côté TDLib |
| TG-11 | P2 | Absent | Live location, album multi-photo UX native |
| TG-12 | P2 | Absent | Username public `@` resolution UI complète (partiel phone lookup) |
| TG-13 | P1 | Proto/UI | Poll **vote** UI (`canVotePoll`) — vérifier boutons vs TDLib `SetPollAnswer` |
| TG-14 | P2 | Absent | 2FA password change / recovery email UI hors auth flow |
| TG-15 | P2 | Absent | Proxy UI TDLib (MTProto proxy / Telegram proxy list) — app impose Tor SOCKS only |

---

## 3. Signal (vs Signal-Android / libsignal)

### Fait
Register/link-and-sync, Tor SOCKS, prekeys, SESSION_READY honesty, safety number 1:1, sealed-sender paths partiels, GV2.

### Manquant / incohérent

| ID | Prio | État | Écart |
|----|------|------|-------|
| SG-01 | P3 | Volontaire / Proto-only | **RingRTC** A/V — `voiceCalls/videoCalls=false` ; `sendCallAction` BUSY/HANGUP **sans** offre média |
| SG-02 | P3 | Incohérent | **Stories** : `stories=false` mais `sendStory` implémenté — UI absente, flag menteur inversé |
| SG-03 | P1 | Incohérent | **Stickers** : `stickers=false` mais `sendSticker` existe — pas de picker |
| SG-04 | P3 | Absent | **SVR2** / PIN restore |
| SG-05 | P3 | Absent | **Registration lock** UI |
| SG-06 | P2 | Absent | Safety number **groupes** ; marque « verified » persistée |
| SG-07 | P2 | Absent | Profile name/about/avatar sync UX complète vs Signal settings |
| SG-08 | P1 | Absent | Message **edit** / **delete for everyone** UI (handler inbound `editMessage` existe) |
| SG-09 | P2 | Absent | Reactions UI (si protocole les reçoit) |
| SG-10 | P2 | Absent | Disappearing messages UX (timer par conversation) au-delà d’ephemeral one-shot |
| SG-11 | P2 | Absent | Contact discovery / CDS refresh UX |
| SG-12 | P2 | Absent | Payment / username / phone number privacy screens |
| SG-13 | P1 | Incohérent | `messageHistory=false` sauf link-sync import — pas de backfill serveur (normal Signal) mais empty-state à clarifier |
| SG-14 | P2 | Absent | Note to self, story replies, group admin (add/remove/title) UI |
| SG-15 | P0 | À vérifier | Call signaling sans RingRTC = dead-end UX si un jour exposé — garder flags `false` |

---

## 4. Matrix (vs Element / Trixnity / CS API)

### Fait
Trixnity E2EE fail-closed, SSO, reactions/edit/redact UI, device_id honesty.

### Manquant / incohérent

| ID | Prio | État | Écart |
|----|------|------|-------|
| MX-01 | P3 | Absent | **Cross-signing** / SAS / QR verification (Element Secure Backup) |
| MX-02 | P2 | Absent | Key backup / recovery key UI |
| MX-03 | P2 | Absent | Room settings : topic, power levels, invites, knock |
| MX-04 | P2 | Absent | Threads (`m.thread`), spaces |
| MX-05 | P2 | Absent | Stickers / custom emoji packs |
| MX-06 | P2 | Absent | Polls (`org.matrix.msc3381` / stable) — flag `polls=false` |
| MX-07 | P2 | Absent | Voice/video (Element Call / MatrixRTC) |
| MX-08 | P1 | Absent | Display inbound **reactions** on bubbles (send OK ; render partiel ?) |
| MX-09 | P2 | Absent | Push gateway / notification rules |
| MX-10 | P2 | Absent | Room directory / public room search |
| MX-11 | P1 | Incohérent | `endToEndEncryption=true` global vs room non-E2EE — `isConversationE2ee` existe ; chips OK, mais capabilities protocol-wide encore optimistes |
| MX-12 | P2 | Absent | Ignore user, report content |
| MX-13 | P2 | Absent | Widgets / Jitsi bridge |

---

## 5. XMPP (vs Conversations / Smack)

### Fait
OMEMO fail-closed, MUC, HTTP upload, Tor media, roster, typing/receipts.

### Manquant / incohérent

| ID | Prio | État | Écart |
|----|------|------|-------|
| XP-01 | P2 | Absent | OMEMO **trust / fingerprint** UI (comme safety number) |
| XP-02 | P2 | Absent | MUC config (subject, affiliations, invite) UI |
| XP-03 | P2 | Absent | Bookmarks UI (XEP-0402) — backend bookmarks cités ARCHITECTURE |
| XP-04 | P2 | Absent | Message correction (XEP-0308) / retract UI |
| XP-05 | P2 | Absent | Reactions (XEP-0444) |
| XP-06 | P2 | Absent | Stickers / custom |
| XP-07 | P3 | Absent | Appels Jingle / WebRTC |
| XP-08 | P2 | Absent | Block list / privacy lists UI |
| XP-09 | P1 | Incohérent | `endToEndEncryption` dynamique session vs `isConversationE2ee` — vérifier affichage MUC OMEMO |
| XP-10 | P2 | Absent | Avatar PEP (XEP-0084) sync UI |
| XP-11 | P2 | Absent | Carbons / MAM UI controls (history window honesty) |

---

## 6. Email (vs K-9 / Thunderbird)

### Fait
IMAP IDLE mutex, Sent sync ~50, Cc SMTP/JMAP, Tor DoH, autoconfig.

### Manquant / incohérent

| ID | Prio | État | Écart |
|----|------|------|-------|
| EM-01 | P1 | Absent | **Bcc** |
| EM-02 | P1 | Absent | Dossiers multiples UI (Drafts, Trash, custom) — Sent sync only |
| EM-03 | P1 | Absent | **Archive complète** / infinite scroll backfill |
| EM-04 | P2 | Absent | Reply-all / Forward MIME structurés (UI quote partiel) |
| EM-05 | P2 | Absent | Drafts local + IMAP Drafts |
| EM-06 | P3 | Volontaire | OpenPGP / S/MIME |
| EM-07 | P1 | Incohérent | JMAP **attachments** send rejeté vs `mediaSend=true` base — gated live à documenter en UI |
| EM-08 | P2 | Absent | HTML body render / sanitize viewer |
| EM-09 | P2 | Absent | Search server-side |
| EM-10 | P2 | Absent | Move/copy between folders |
| EM-11 | P2 | Absent | Identities / From alias |
| EM-12 | P2 | Absent | POP3 leave-on-server policy UI |

---

## 7. IRC (vs HexChat / Kitteh / IRCv3)

### Fait
TLS/SASL/NickServ gates, CASEMAPPING, channels+DMs, Tor SOCKS.

### Manquant / incohérent

| ID | Prio | État | Écart |
|----|------|------|-------|
| IRC-01 | P2 | Absent | Topic edit UI ; modes (+o/+v) UI |
| IRC-02 | P2 | Absent | WHOIS / IGNORE / QUERY UX dédiée |
| IRC-03 | P2 | Absent | IRCv3 : `chathistory`, `message-tags`, `server-time`, `echo-message` honesty |
| IRC-04 | P2 | Absent | CTCP VERSION/ACTION (`/me`) UI |
| IRC-05 | P3 | Volontaire | DCC (hors Tor-safe) |
| IRC-06 | P2 | Absent | Multi-network tab UX claire |
| IRC-07 | P1 | Incohérent | `contacts=true` = NAMES list, pas un carnet — empty hints OK mais label « Contacts » trompeur |
| IRC-08 | P2 | Absent | Banlist / kick UI |
| IRC-09 | P2 | Absent | Rejoin on kick, channel password (+k) UX |

---

## 8. Transversal app / contrat unifié

| ID | Prio | État | Écart |
|----|------|------|-------|
| APP-01 | P1 | Absent | Flags `voiceCalls`/`videoCalls`/`stories` **jamais** branchés dans Compose attach sheet |
| APP-02 | P1 | Incohérent | Pas de flags `reactions` / `editMessage` / `deleteMessage` dans `ProtocolCapabilities` — UI hardcode TG/Matrix |
| APP-03 | P2 | Absent | Notifications push FCM (Tor-hostile) / local foreground only — documenter |
| APP-04 | P2 | Absent | Recherche globale multi-protocole |
| APP-05 | P2 | Absent | Mute/pin/archive **hors Telegram** (Room a les champs ; seuls TG écrivent) |
| APP-06 | P1 | Absent | Unified long-press : reply/forward pour Signal/XMPP/Email |
| APP-07 | P2 | Absent | Drafts persistés Room |
| APP-08 | P2 | Absent | Unread sync per-protocol honesty tests |
| APP-09 | P0 | Process | `main` ≠ release tag jusqu’à merge |
| APP-10 | P2 | Docs | `ARCHITECTURE.md` grid obsolète vs 1.0.16 (Sent, reactions Matrix, safety number) |

---

## 9. Incohérences « capability vs code » (à corriger en priorité)

1. **SG-02 / SG-03** — Signal : code `sendStory` / `sendSticker` vs flags `false` → soit UI + flags `true`, soit retirer/dead-code guard.
2. **APP-02** — Reactions/edit/redact non modélisés dans capabilities → UI diverge par `if (protocol == …)`.
3. **EM-07** — Email `mediaSend=true` alors que JMAP refuse pièces.
4. **TG-10** — Ephemeral path mort côté UI mais code TTL présent.
5. **APP-05** — Colonnes `isPinned/isMuted/isArchived` Room utilisées seulement par Telegram.
6. **MX-11** — Capability E2EE protocol-wide vs per-room.

---

## 10. Roadmap proposée pour « finir une bonne fois »

### Vague A — Honesty & merge (court)
1. Merger PR → `main`, aligner version.
2. Attacher APK debug (ou CI release signé) à `v1.0.16`.
3. Corriger incohérences §9 (flags / dead code / JMAP media chip).
4. Étendre `ProtocolCapabilities` : `reactions`, `messageEdit`, `messageDelete`, `messageForward`.
5. Mettre à jour `ARCHITECTURE.md` + ce fichier (cocher faits).

### Vague B — Parité utile sans RingRTC/SVR2
1. Signal : sticker picker **ou** delete sendSticker ; edit/delete UI ; reactions render.
2. Matrix : render reactions inbound ; room basic settings.
3. XMPP : OMEMO fingerprint dialog.
4. Email : Bcc + Drafts folder + honesty JMAP attach.
5. IRC : `/me`, topic display/edit, rename Contacts → « Nicks ».
6. App : pin/mute local pour tous protocoles (sans sync serveur si N/A).

### Vague C — Gros (décision produit)
1. RingRTC + UI appels Signal (et éventuellement Element Call).
2. SVR2 + registration lock.
3. Matrix cross-signing QR.
4. Telegram secret chats.
5. OpenPGP email.
6. Full IMAP archive / multi-folder.

---

## 11. Checklist « done » (définition)

Un protocole est **terminé** quand :

1. Chaque flag `true` a une UI utilisable et un chemin réseau Tor-safe.
2. Chaque UI visible a un flag `true` (pas de hardcode orphelin).
3. Les features « client original » absentes sont listées ici en **Volontaire** ou **P3** avec raison.
4. Tests unitaires des chemins auth/send/proxy verts.
5. CHANGELOG + ARCHITECTURE à jour.

---

*Généré pour pilotage ; mettre à jour à chaque vague. Ne pas confondre avec un plan Cursor éditable hors repo.*
