# SecureMessenger — Inventaire des fonctionnalités manquantes & incohérences

État au **1.0.17** (`feat/feature-gaps-complete`). Mettre à jour à chaque vague.

**Légende priorité** : **P0** bug/mensonge · **P1** feature normale partielle · **P2** confort UX · **P3** gros / volontaire

---

## 0. Process

| Item | État |
|------|------|
| Merge audit → `main` | **Fait** (PR #24) |
| Release `v1.0.16` + APK + TDLib AAR | **Fait** |
| CI TDLib fetch | **Fait** (PR #25) |
| Vague A honesty + Vague B utile | **Fait** en 1.0.17 |
| Vague C (RingRTC / SVR2 / QR / secret chats / OpenPGP / full IMAP) | **Volontaire P3** |

---

## 1. Fait en 1.0.17 (cocher)

- [x] Caps `reactions` / `messageEdit` / `messageDelete` / `messageForward` / `messageReply` / `localChatListControls`
- [x] UI long-press gated par capabilities
- [x] Signal stickers=true ; stories fail-closed ; réactions + remote-delete DM
- [x] Telegram edit/delete TDLib ; ephemeral fail-closed
- [x] Email Bcc SMTP/JMAP ; honesty JMAP sans PJ
- [x] Inbox pin/mute/archive local tous protocoles
- [x] XMPP empreintes OMEMO (puce E2EE)
- [x] IRC `/me` → CTCP ACTION ; Contacts → « Nicks »
- [x] Affichage `payloadJson.reactions`

---

## 2. Restant — par protocole

### Telegram
| ID | Prio | État | Note |
|----|------|------|------|
| TG-01 | P3 | Volontaire | Secret chats (`useSecretChats=false`) |
| TG-02 | P3 | Absent | Appels VoIP / tgcalls |
| TG-03–15 | P2 | Absent | Stories, bots, topics, search, folders, scheduled, live location, … |

### Signal
| ID | Prio | État | Note |
|----|------|------|------|
| SG-01 | P3 | Volontaire | RingRTC A/V |
| SG-02 | P3 | Proto-only | `sendStory` existant, flag false + fail-closed |
| SG-04/05 | P3 | Absent | SVR2 / registration lock |
| SG-06 | P2 | Absent | Safety number groupes + verified persist |
| SG-08 | P1 | Partiel | Edit outbound non câblé (inbound OK) |
| SG-10–14 | P2 | Absent | Disappearing UX, CDS, payments, group admin |

### Matrix
| ID | Prio | État | Note |
|----|------|------|------|
| MX-01/02 | P3 | Absent / bloqué | Cross-signing QR (bloqué taille) ; key backup UX absent |
| MX-03–13 | P2 | **Partiel** `feat/parity-matrix` | leave/invite/state/directory/hierarchy/relations/reply/thread/ignore/favourite protocol APIs — voir `docs/features/MATRIX_FULL_INVENTORY.md` |
| MX-08 | P1 | **Fait** | Réactions inbound+outbound ; edit/redact inbound ; `m.in_reply_to` |
| MX-CALL / widgets | P3 | Bloqué | MatrixRTC / Jitsi sans WebRTC |

### XMPP
| ID | Prio | État | Note |
|----|------|------|------|
| XP-01 | P2 | **Fait** 1.0.17 | Fingerprint OMEMO |
| XP-04/05 | P2 | Absent | XEP-0308 / XEP-0444 |
| XP-07 | P3 | Absent | Jingle |
| XP-02/03/08–11 | P2 | Absent | MUC config, bookmarks UI, block, avatar, MAM controls |

### Email
| ID | Prio | État | Note |
|----|------|------|------|
| EM-01 | P1 | **Fait** 1.0.17 | Bcc |
| EM-02/03/05 | P1–P2 | Absent | Multi-folder UI, full archive, Drafts IMAP |
| EM-06 | P3 | Volontaire | OpenPGP / S/MIME |
| EM-07 | P1 | **Fait** honesty | JMAP mediaSend=false per account |
| EM-08–12 | P2 | Absent | HTML viewer, search, move, identities, POP3 policy |

### IRC
| ID | Prio | État | Note |
|----|------|------|------|
| IRC-04 | P2 | **Fait** `/me` | |
| IRC-07 | P1 | **Fait** label Nicks | |
| IRC-01–03/06/08/09 | P2 | Absent | Topic edit UI, WHOIS, IRCv3 honesty, modes, +k |
| IRC-05 | P3 | Volontaire | DCC |

### App transversal
| ID | Prio | État | Note |
|----|------|------|------|
| APP-02/05/06 | P1–P2 | **Fait** 1.0.17 | Caps + local list + long-press unifié |
| APP-01 | P1 | OK | Calls/stories non branchés (flags false) |
| APP-03 | P2 | Doc | Pas de FCM (Tor) — foreground only |
| APP-04/07 | P2 | Absent | Recherche globale, drafts Room |
| APP-10 | P2 | À jour via ce fichier + ARCHITECTURE |

---

## 3. Vague C — décision produit (non commencé)

1. RingRTC + UI appels Signal  
2. SVR2 + registration lock  
3. Matrix cross-signing QR  
4. Telegram secret chats  
5. OpenPGP email  
6. Full IMAP archive / multi-folder  

---

## 4. Definition of done

1. Flag `true` ⇒ UI + chemin Tor-safe  
2. UI visible ⇒ flag `true`  
3. Absents listés ici en Volontaire/P3  
4. Tests auth/send/proxy verts  
5. CHANGELOG + ce fichier à jour  
