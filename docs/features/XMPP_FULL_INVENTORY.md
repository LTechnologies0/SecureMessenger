# XMPP — Full Feature Inventory

**Scope:** `protocol/xmpp/**` · Smack **4.5.0** · Tor SOCKS mandatory  
**Branch:** `feat/parity-xmpp`  
**Blocked (product):** Jingle / WebRTC A/V (`XP-JINGLE-*`)

Legend: **DONE** wired end-to-end · **PARTIAL** protocol path incomplete or UI-only gap · **MISS** not implemented · **BLOCKED** intentional · **N/A** not applicable under Tor/fail-closed policy

IDs are stable for parity tracking (`XP-*`).

---

## 0. Transport & session (RFC 6120 / 6121)

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-TOR-01 | Tor SOCKS unresolved sockets | app policy | `SocksUnresolvedSocketFactory`, `DummyTorDnsResolver` | **DONE** | Fail-closed if SOCKS down |
| XP-TOR-02 | Stream-isolated SOCKS user | app policy | `isolatedSocksUser()` | **DONE** | |
| XP-TCP-01 | XMPP over TCP 5222 | RFC 6120 | `XMPPTCPConnection` | **DONE** | |
| XP-TLS-01 | TLS required | RFC 6120 | `SecurityMode.required` | **DONE** | |
| XP-SASL-01 | SASL login (localpart+domain) | RFC 6120 | `setUsernameAndPassword` | **DONE** | |
| XP-RES-01 | Resource `SecureMessenger` | RFC 6120 | `setResource` | **DONE** | |
| XP-COMP-01 | Stream compression | XEP-0138 | `setCompressionEnabled(true)` | **DONE** | |
| XP-PRES-01 | Initial presence on login | RFC 6121 | `setSendPresence(true)` | **DONE** | |
| XP-PING-01 | XMPP ping keepalive | XEP-0199 | `PingManager` interval 60s | **DONE** | |
| XP-RECON-01 | Auto-reconnect | Smack | `ReconnectionManager` | **DONE** | Failure → account ERROR |
| XP-MULTI-01 | Multi-account sessions | app | `sessions: ConcurrentHashMap` | **DONE** | Per-`accountId` facade |
| XP-REG-01 | In-band registration | XEP-0077 | `AccountManager` | **DONE** | Tor probe connection |
| XP-REG-02 | Registration extra fields | XEP-0077 | `NeedsFields` flow | **DONE** | |

---

## 1. Roster & presence (RFC 6121)

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-ROSTER-01 | Roster sync → conversations/contacts | RFC 6121 | `Roster`, `RosterListener` | **DONE** | |
| XP-ROSTER-02 | Add contact + subscribe | RFC 6121 | `Roster.createItemAndRequestSubscription` | **DONE** | Protocol API |
| XP-ROSTER-03 | Remove contact | RFC 6121 | `Roster.removeEntry` | **DONE** | Protocol API |
| XP-PRES-02 | Inbound presence → UI | RFC 6121 | `presenceChanged` | **PARTIAL** | Listener no-op historically; presence query API added |
| XP-PRES-03 | Get peer presence | RFC 6121 | `Roster.getPresence` | **DONE** | Protocol API |
| XP-LAST-01 | Last activity query | XEP-0012 | `LastActivityManager` | **DONE** | Protocol API |

---

## 2. Instant messaging (RFC 6121)

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-IM-01 | 1:1 chat send/receive | RFC 6121 | `ChatManager` | **DONE** | |
| XP-IM-02 | Carbons (multi-device) | XEP-0280 | `CarbonManager.enableCarbons` | **DONE** | Incl. OMEMO carbons |
| XP-IM-03 | Delayed delivery timestamps | XEP-0203 | `DelayInformation` | **DONE** | |
| XP-IM-04 | Stable stanza / origin IDs | XEP-0359 | `StanzaIdElement`, `OriginIdElement` | **DONE** | Origin-id attached on send |
| XP-IM-05 | Out-of-band URL hint | XEP-0066 | OOB URL regex parse | **PARTIAL** | Receive parse only |
| XP-IM-06 | Message replies (local/thread) | app + XEP-0201 | caps `messageReply=true` | **PARTIAL** | Cap on; no XEP-0201 thread wiring |
| XP-IM-07 | Forward | — | caps `messageForward=false` | **MISS** | |
| XP-IM-08 | Contact card as vCard body | XEP-0054 style | `formatContactVCard` | **DONE** | |

---

## 3. Chat states, receipts, markers

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-CS-01 | Typing (composing/paused) | XEP-0085 | `ChatStateManager` | **DONE** | DM only |
| XP-CS-02 | Inbound typing → UI | XEP-0085 | `ChatStateListener` | **DONE** | |
| XP-RR-01 | Delivery receipts request/auto | XEP-0184 | `DeliveryReceiptManager` | **DONE** | |
| XP-RR-02 | Receipt → DELIVERED | XEP-0184 | `ReceiptReceivedListener` | **DONE** | |
| XP-MARK-01 | Markable + displayed | XEP-0333 | `ChatMarkersManager` | **DONE** | |
| XP-MARK-02 | markRead → displayed | XEP-0333 | `DisplayedExtension` | **DONE** | DM only |

---

## 4. Archive (MAM)

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-MAM-01 | Enable MAM for all | XEP-0313 | `enableMamForAllMessages` | **DONE** | Best-effort |
| XP-MAM-02 | Catch-up query + pageNext | XEP-0313 | `MamManager.queryArchive` | **DONE** | `XmppMamSync` |
| XP-MAM-03 | Since-timestamp incremental | XEP-0313 | `limitResultsSince` | **DONE** | |
| XP-MAM-04 | MAM prefs UI / date picker | XEP-0313 | — | **MISS** | Server prefs already enabled |
| XP-MAM-05 | MUC MAM (room archive) | XEP-0313 | query with room JID | **PARTIAL** | Same sync path; server-dependent |

---

## 5. Multi-User Chat

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-MUC-01 | Join room + listen | XEP-0045 | `MultiUserChat.join` | **DONE** | |
| XP-MUC-02 | Send group message | XEP-0045 | `sendMessage` | **DONE** | |
| XP-MUC-03 | Heuristic MUC JID detect | app | `@conference.` / `@muc.` | **DONE** | |
| XP-MUC-04 | Create / createOrJoin | XEP-0045 | `createOrJoin` | **DONE** | Protocol API |
| XP-MUC-05 | Leave room | XEP-0045 | `leave` | **DONE** | Protocol API |
| XP-MUC-06 | Change subject | XEP-0045 | `changeSubject` | **DONE** | Protocol API |
| XP-MUC-07 | Invite | XEP-0045 | `invite` | **DONE** | Protocol API |
| XP-MUC-08 | Occupants list | XEP-0045 | `getOccupants` | **DONE** | Protocol API |
| XP-MUC-09 | Kick / ban / affiliations UI | XEP-0045 | Smack methods exist | **PARTIAL** | Protocol kick/ban APIs; no dedicated admin UI |
| XP-MUC-10 | Room config form UI | XEP-0045 | `MucConfigFormManager` | **MISS** | XP-02 gap |
| XP-MUC-11 | Direct invite listener | XEP-0249 | `DirectMucInvitationManager` | **MISS** | |

---

## 6. Bookmarks

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-BM-01 | Load bookmarked conferences | XEP-0048 | `BookmarkManager.getBookmarkedConferences` | **DONE** | Auto-join on connect |
| XP-BM-02 | Add bookmark | XEP-0048 | `addBookmarkedConference` | **DONE** | Protocol API |
| XP-BM-03 | Remove bookmark | XEP-0048 | `removeBookmarkedConference` | **DONE** | Protocol API |
| XP-BM-04 | Bookmarks management UI | XEP-0048 | — | **PARTIAL** | Protocol ready; dedicated UI thin |
| XP-BM-05 | Bookmarked URLs | XEP-0048 | `BookmarkedURL` | **MISS** | Low priority |

---

## 7. Encryption (OMEMO)

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-OMEMO-01 | Signal store bootstrap | XEP-0384 | `smack-omemo-signal` | **DONE** | GPLv3 path |
| XP-OMEMO-02 | Device list publish | XEP-0384 | `OmemoManager.initializeAsync` | **DONE** | |
| XP-OMEMO-03 | Encrypt 1:1 | XEP-0384 | `encrypt` | **DONE** | Fail-closed plaintext |
| XP-OMEMO-04 | Encrypt MUC | XEP-0384 | MUC encrypt | **DONE** | Fail-closed |
| XP-OMEMO-05 | Decrypt live + carbons | XEP-0384 | `OmemoMessageListener` | **DONE** | |
| XP-OMEMO-06 | Decrypt MAM | XEP-0384 | `unwrapChatBody` | **DONE** | |
| XP-OMEMO-07 | TOFU trust | XEP-0384 | `OmemoTrustCallback` | **DONE** | |
| XP-OMEMO-08 | Fingerprint display | XEP-0384 | `fingerprintSummary` | **DONE** | XP-01 / E2EE chip |
| XP-OMEMO-09 | Manual verify / distrust UI | XEP-0384 | trust callback | **PARTIAL** | TOFU only |
| XP-OMEMO-10 | Cleartext refuse when OMEMO expected | policy | `shouldRefuseInboundCleartext` | **DONE** | |

---

## 8. HTTP upload & media

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-HTTP-01 | Discover upload service | XEP-0363 | `HttpFileUploadManager` | **DONE** | |
| XP-HTTP-02 | Slot + PUT via Tor | XEP-0363 | `requestSlot` + proxy PUT | **DONE** | |
| XP-HTTP-03 | Download via Tor | XEP-0363 | `downloadHttpUploadUrl` | **DONE** | Roster-gated auto-fetch |
| XP-HTTP-04 | aesgcm:// decrypt | XEP-0454 | local AES-GCM | **DONE** | |
| XP-HTTP-05 | Voice / GIF MIME | app | `contentType` on slot | **DONE** | |
| XP-FT-01 | Legacy SI file transfer | XEP-0096 | `FileTransferManager` exposed | **MISS** | Prefer HTTP upload |
| XP-GEO-01 | Geolocation share | XEP-0080 | `GeoLocationManager` | **DONE** | OMEMO → geo: URI body |
| XP-STICKER-01 | Stickers | — | caps false | **N/A** | |
| XP-POLL-01 | Polls | — | caps false | **N/A** | |
| XP-EPH-01 | Ephemeral | — | caps false | **N/A** | |

---

## 9. Message mutation (edit / react / delete)

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-EDIT-01 | Last Message Correction | XEP-0308 | `MessageCorrectExtension` | **DONE** | Caps + ViewModel |
| XP-EDIT-02 | Inbound correction apply | XEP-0308 | `MessageCorrectExtension.from` | **DONE** | Updates local body |
| XP-REACT-01 | Message Reactions send | XEP-0444 | custom `ReactionsExtension` | **DONE** | Smack has no manager |
| XP-REACT-02 | Inbound reactions → payloadJson | XEP-0444 | parse extension | **DONE** | |
| XP-DEL-01 | Message Retraction | XEP-0424 | `MessageRetractionManager` + fastening | **DONE** | Caps + ViewModel |
| XP-DEL-02 | Inbound retract apply | XEP-0424 | `FasteningElement` + `RetractElement` | **DONE** | |

---

## 10. Profile, avatars, blocking

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-VCARD-01 | Own vCard load/save (nick/bio) | XEP-0054 | `VCardManager` | **DONE** | `profileEdit` |
| XP-AVATAR-01 | Own avatar set/get (vCard PHOTO) | XEP-0054 | `VCard.setAvatar` / `getAvatar` | **DONE** | Protocol API |
| XP-AVATAR-02 | Peer avatar fetch → contact | XEP-0054 | `loadVCard(jid)` | **DONE** | Protocol API |
| XP-AVATAR-03 | User Avatar PEP (XEP-0084) | XEP-0084 | PubSub | **MISS** | vCard path preferred |
| XP-BLOCK-01 | Block / unblock / list | XEP-0191 | `BlockingCommandManager` | **DONE** | Protocol API |
| XP-BLOCK-02 | Block UI | XEP-0191 | — | **PARTIAL** | Protocol ready |

---

## 11. Discovery & caps

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-DISCO-01 | Service discovery (implicit) | XEP-0030 | used by upload/MAM/etc. | **DONE** | Via managers |
| XP-CAPS-01 | Entity capabilities | XEP-0115 | Smack default | **DONE** | Library default |
| XP-DISCO-02 | Announce correct/react/retract | XEP-0030 | `addFeature` | **DONE** | On connect |

---

## 12. Calls (blocked)

| ID | Feature | Spec | Smack API | State | Notes |
|----|---------|------|-----------|-------|-------|
| XP-JINGLE-01 | Jingle sessions | XEP-0166 | smack experimental | **BLOCKED** | No WebRTC |
| XP-JINGLE-02 | Jingle RTP A/V | XEP-0167 | — | **BLOCKED** | |
| XP-JINGLE-03 | Jingle FT | XEP-0234 | `jingle_filetransfer` | **BLOCKED** | Prefer XEP-0363 |
| XP-CALL-CAP | `voiceCalls`/`videoCalls` | app | caps false | **DONE** | Honesty |

---

## 13. Misc XEPs available in Smack but unused / low priority

| ID | Feature | Spec | State |
|----|---------|------|-------|
| XP-ATTN-01 | Attention | XEP-0224 | **MISS** |
| XP-NICK-01 | User Nickname PEP | XEP-0172 | **MISS** (vCard nick used) |
| XP-PRIV-01 | Privacy lists | XEP-0016 | **MISS** (prefer XEP-0191) |
| XP-PUSH-01 | Push notifications | XEP-0357 | **MISS** (Tor / no FCM) |
| XP-SPOIL-01 | Spoilers | XEP-0382 | **MISS** |
| XP-MUCL-01 | MUC Light | XEP-xxxx | **MISS** |
| XP-XHTML-01 | XHTML-IM | XEP-0071 | **MISS** |
| XP-CHAT-MARKERS-received | `<received/>` marker | XEP-0333 | **PARTIAL** (displayed + 0184 cover delivery) |

---

## 14. App capability matrix (`ProtocolCapabilities`)

| Cap | Value (post-parity) |
|-----|---------------------|
| directMessages / groupChats / media* / typing / readReceipts | true |
| contacts / profileEdit / voiceNotes / gifs / locationShare / contactShare | true |
| messageHistory / backupExport / messageReply / localChatListControls | true |
| endToEndEncryption | dynamic (OMEMO ready) |
| reactions / messageEdit / messageDelete | **true** |
| stickers / polls / ephemeral / stories / voiceCalls / videoCalls / messageForward | false |

---

## 15. Implementation map (source)

| Area | Files |
|------|-------|
| Facade / Smack | `SmackClientFacade.kt`, `XmppTorSockets.kt` |
| Protocol adapter | `XmppProtocol.kt` |
| OMEMO | `OmemoHelper.kt`, `XmppOmemoBootstrap.kt` |
| MAM | `XmppMamSync.kt` |
| Registration | `XmppRegistration.kt` |
| Reactions XEP-0444 | `XmppReactionExtension.kt` |
| Tests | `OmemoSendDecisionTest`, `XmppMediaUrlTest`, `XmppMucJidTest`, `XmppFeatureExtensionsTest` |

---

## 16. Definition of done (this branch)

1. Inventory lists **every** discrete XMPP feature ID above.  
2. Feasible gaps from FEATURE_GAPS XP-04/05/08/09/02–03/10–11 implemented at **protocol** layer (Tor-safe).  
3. Jingle remains **BLOCKED**.  
4. `:protocol:xmpp` + `:app` compile.  
5. Caps honesty: edit/react/delete true only when wired.
