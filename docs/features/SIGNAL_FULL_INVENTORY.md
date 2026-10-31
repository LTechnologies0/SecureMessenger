# Signal / libsignal — Full Feature Inventory

**Worktree:** `feat/parity-signal`  
**Vendor pin:** Signal-Android `v8.20.0` (`vendor/signal/VERSION`)  
**Scope:** Every discrete Signal client feature exposed by vendored `libsignal-service` + SecureMessenger `protocol/signal` status.  
**Tor:** Mandatory SOCKS (`SignalSocksHolder` / `SignalRuntimeFactory.applyNetworkSocks`); fail-closed when unset.

**Status legend:** `done` · `partial` · `missing` · `fail-closed` · `blocked`

**Blocked (mandate — do not implement):** RingRTC media path · SVR2 · registration lock needing PIN/SVR server · payments (MobileCoin / donations pay stack)

---

## 1. Trust boundary & stack

| Layer | Path |
|-------|------|
| Protocol façade | `protocol/signal/.../SignalProtocol.kt` |
| Helpers | `SignalRegistrationFlow`, `SignalLinkFlow`, `SignalLinkAndSync`, `SignalGroupHelper`, `SignalSyncEngine`, `SignalMessageHandler`, `SignalStorageSync`, `SignalFeatureHelpers`, `SignalSafetyNumber`, `SignalBackup*`, `SignalPreKeyUtil`, `SignalCertificateUtil`, `SignalRuntimeFactory` |
| Vendor API | `vendor/signal/libsignal-service/.../org/whispersystems/signalservice/api/**` |
| Crypto client | Maven `org.signal:libsignal-client` / `libsignal-android` |

Capabilities (`baseSignalCapabilities`): DM/group/media/typing/receipts/E2EE/contacts/profile/voice/stickers/location/polls/contactShare/ephemeral/reactions/delete/edit/reply/`localChatListControls` = **true**; gifs/calls/stories/forward = **false**; `messageHistory` after link-and-sync import.

---

## 2. libsignal-service public API map

| Package | Feature-relevant types |
|---------|------------------------|
| root | `SignalServiceMessageSender`, `SignalServiceMessageReceiver`, `SignalServiceAccountManager`, protocol stores, `SignalSessionLock` |
| `account/` | `AccountApi`, attributes, prekey upload, change-number, PNI distribution |
| `registration/` | `RegistrationApi`, restore-method bodies |
| `keys/` | `KeysApi`, `PreKeyRepository`, counts |
| `message/` | `MessageApi`, spam token |
| `messages/` | Data/edit/story/typing/receipt/attachment/sticker/groupV2 models |
| `messages/calls/` | Offer/Answer/Ice/Hangup/Busy/Opaque, TURN info |
| `messages/multidevice/` | `SignalServiceSyncMessage` + contacts/read/verified/blocked/keys/config/payments/… |
| `crypto/` | Sealed sender, session/group ciphers, profile/attachment ciphers |
| `groupsv2/` | `GroupsV2Api`, `GroupsV2Operations`, ZK / endorsements |
| `profiles/` | `ProfileApi`, `ProfileRepository`, avatar params |
| `cds/` | `CdsiV2Service` |
| `svr/` / `kbs/` | SVR2/V3 + PIN helpers |
| `storage/` | Storage Service API + record types |
| `archive/` | Backup DTOs only (no `ArchiveApi` client class in this vendor tree) |
| `link/` / `provisioning/` | Device link DTOs, `ProvisioningSocket` |
| `donations/` / `subscriptions/` / `payments/` | Donations + money formatters |
| `websocket/` / `push/` / `util/` | WS, addresses, ServiceIds, usernames, TLS proxy |

### Key request → response surfaces

| API | Methods (summary) |
|-----|-------------------|
| `SignalServiceMessageSender` | `sendDataMessage`, `sendEditMessage`, `sendGroupDataMessage`, `sendReceipt`, `sendTyping`/`sendGroupTyping`, `sendCallMessage`, `sendGroupStory`/`sendStorySyncMessage`, `sendSyncMessage`, `uploadAttachment`, sender-key helpers |
| `SignalServiceMessageReceiver` | `retrieveAttachment`, avatars, stickers/manifest, `retrieveBackup` |
| `RegistrationApi` | session create/status, captcha, SMS, verify, `registerAccount`, SVR auth validate, secondary register |
| `AccountApi` | whoAmI, attributes/capabilities, **reg lock**, delete, change number, **username** reserve/confirm/link |
| `KeysApi` | prekey counts/upload/fetch |
| `GroupsV2Api` | put/get/patch group, history, join info, avatar upload, credentials |
| `ProfileApi` | set/get versioned profile (+ credential), batch fetch |
| `CdsiV2Service` | `getRegisteredUsers` → ACI/PNI map |
| `StorageServiceApi` | auth, read/write items, ping |
| `SecureValueRecoveryV2/V3` | setPin / restore / delete / authorization |
| `DonationsApi` | boost/subscription / redeem (payment stack) |

---

## 3. Feature inventory (discrete IDs)

### Registration & account

| ID | Feature | API / req→resp | SM status | Notes |
|----|---------|----------------|-----------|-------|
| SIG-REG-01 | Registration session + SMS/captcha | `RegistrationApi` create / captcha / SMS / verify | **done** | `SignalRegistrationFlow`; SMS only (no voice) |
| SIG-REG-02 | Register + ACI/PNI/Kyber prekeys | `registerAccount` + `KeysApi.setPreKeys` | **done** | `SignalPreKeyUtil` |
| SIG-REG-03 | Registration lock (PIN→SVR) | `AccountApi.enableRegistrationLock` + SVR `setPin` | **blocked** | PIN local-only; `registrationLock=null` |
| SIG-REG-04 | SVR2 restore / auth | `SecureValueRecoveryV2` | **blocked** | Mandate |
| SIG-REG-05 | SVR3 | `SecureValueRecoveryV3` | **blocked** | Mandate |
| SIG-REG-06 | Voice verification code | `VerificationCodeTransport.VOICE` | **missing** | SMS-only today |
| SIG-ACCT-01 | WhoAmI / attributes at register | `AccountApi` | **partial** | Set at register; no full mgmt UI |
| SIG-ACCT-02 | Change number / delete / data report | `AccountApi` | **missing** | |
| SIG-ACCT-03 | FCM token set/clear | GCM endpoints | **missing** | Tor/foreground design by choice |
| SIG-USER-01 | Username reserve/confirm/delete + link | `AccountApi` username* | **partial** | `setUsername`/`clearUsername` reserve+confirm+delete; link create/update UI not wired; sync inbound still log-only |
| SIG-USER-02 | Phone number discoverability privacy | account attributes | **partial** | Hardwired `discoverableByPhoneNumber=true` |

### Linking & backup

| ID | Feature | API / req→resp | SM status | Notes |
|----|---------|----------------|-----------|-------|
| SIG-LINK-01 | Link secondary device | `ProvisioningSocket` + `registerAsSecondaryDevice` | **done** | `SignalLinkFlow` |
| SIG-LINK-02 | Link-and-sync archive import | `retrieveBackup` + MessageBackup | **done** | `SignalLinkAndSync` + `SignalBackupImporter` |
| SIG-LINK-03 | Primary: wait linked device / transfer archive | `link/*` DTOs | **missing** | Secondary-only |
| SIG-BKP-01 | Local JSON backup export | SM dump | **partial** | Not Signal encrypted backup format |
| SIG-ARCH-01 | Cloud Archive service | archive DTOs / redeem | **missing** | No ArchiveApi in vendor tree |

### Transport & Tor

| ID | Feature | API / req→resp | SM status | Notes |
|----|---------|----------------|-----------|-------|
| SIG-PROXY-01 | Tor SOCKS for all Signal traffic | `SignalSocksHolder` + Network invalid-proxy fail-closed | **done** | Mandatory |
| SIG-WS-01 | Authenticated WS drain | `SignalWebSocket.readMessageBatch` | **done** | `SignalSyncEngine` |
| SIG-KEY-01 | Prekey replenishment | `KeysApi` | **done** | `syncPreKeysIfNeeded` |

### Direct messages & content

| ID | Feature | API / req→resp | SM status | Notes |
|----|---------|----------------|-----------|-------|
| SIG-DM-01 | 1:1 text send/receive | `sendDataMessage` / decrypt | **done** | |
| SIG-DM-02 | Attachments upload/download | `uploadAttachment` / `retrieveAttachment` | **done** | CDN v4 |
| SIG-DM-03 | Voice notes | `voiceNote` flag | **done** | |
| SIG-DM-04 | Quotes / reply outbound+inbound | DataMessage.quote | **done** | Inbound + outbound `withQuote` via `OutgoingContent.Text.replyToMessageId` |
| SIG-DM-05 | Reactions | `withReaction` | **done** | DM + GV2 out; inbound via classify |
| SIG-DM-06 | Remote delete | `withRemoteDelete` / wire `delete` | **done** | DM + GV2 out; inbound deletes local target |
| SIG-DM-07 | Message edit | `sendEditMessage` / inbound EditMessage | **done** | DM + GV2 fan-out outbound; inbound OK; cap `messageEdit=true` |
| SIG-DM-08 | Forward | client re-compose | **missing** | Cap false |
| SIG-TYP-01 | Typing indicators | `sendTyping` / `sendGroupTyping` | **done** | 1:1 + GV2 |
| SIG-RCPT-01 | Read receipts outbound | `sendReceipt` | **partial** | 1:1; groups local-only |
| SIG-RCPT-02 | Delivery receipts inbound | receiptMessage | **done** | |
| SIG-SEAL-01 | Sealed sender 1:1 | `SealedSenderAccess.forIndividual` + sender cert | **partial** | Reads Storage-synced peer keys; one ProfileApi refresh/drop on sealed failure then identified retry |
| SIG-SEAL-02 | Group send endorsements | `forFanOutGroupSend` / endorsements | **partial** | Fallback fan-out when fetch fails |
| SIG-STICK-01 | Stickers send | DataMessage.sticker / image | **done** | Cap true |
| SIG-STICK-02 | Sticker pack CDN fetch/install | `retrieveSticker(Manifest)` | **missing** | No pack browser |
| SIG-POLL-01 | Poll create | `PollCreate` | **done** | |
| SIG-POLL-02 | Poll vote / terminate | poll vote protos | **partial** | Outbound `votePoll` / `withPollVote`; terminate later |
| SIG-LOC-01 | Location | geo: body convention | **partial** | Not PlacePointer protobuf |
| SIG-CONTACT-01 | Shared contact card | `SharedContact` | **done** | |
| SIG-EPH-01 | Disappearing timer on send | `withExpiration` | **done** | Per-message OK; conversation timer via `setDisappearingTimer` / blank `OutgoingContent.Ephemeral` (`asExpirationUpdate`); composer UX still message-oriented |
| SIG-EPH-02 | View-once | `withViewOnce` / ViewOnceOpen | **missing** | |
| SIG-GIF-01 | Dedicated GIF path | — | **fail-closed** | Cap `gifs=false` |

### Groups V2

| ID | Feature | API / req→resp | SM status | Notes |
|----|---------|----------------|-----------|-------|
| SIG-GV2-01 | Create group | `putNewGroup` | **done** | `SignalGroupHelper.createGroup` |
| SIG-GV2-02 | Send (sender key) | `sendGroupDataMessage` | **done** | + fan-out fallback |
| SIG-GV2-03 | Refresh group / members | `getGroup` | **partial** | Cache + storage seed; no change-log UI |
| SIG-GV2-04 | Admin: title/desc/members/roles/ban | `patchGroup` + GroupOperations | **partial** | Title + add/remove members via `SignalGroupHelper` / `SignalProtocol`; roles/ban/link/desc later |
| SIG-GV2-05 | Invite links / join by link | join info + link password | **partial** | `enableOrResetInviteLink` / `createGroupInviteLink` / `joinGroupByLink` / join-info fetch; no QR UI |
| SIG-GV2-06 | Leave / terminate / announce | leave builders | **partial** | `SignalGroupHelper.leaveGroup` + `SignalProtocol.leaveGroup` (self-remove via `createRemoveMembersChange`/`patchGroup`); no terminate/announce; UI not wired (`closeConversation` is screen dispose only) |
| SIG-GV2-07 | Group avatar upload | `uploadAvatar` | **missing** | |
| SIG-GV2-08 | SKDM process | `SignalGroupSessionBuilder` | **done** | Handler |

### Profiles, CDS, safety

| ID | Feature | API / req→resp | SM status | Notes |
|----|---------|----------------|-----------|-------|
| SIG-PROF-01 | Get/set name+about | `ProfileApi` versioned | **done** | |
| SIG-PROF-02 | Profile avatar upload | AvatarUploadParams | **done** | `setProfileAvatar` via `AvatarUploadParams.forAvatar` + PushServiceSocket CDN (Tor SOCKS) |
| SIG-PROF-03 | Fetch peer profiles / credentials | getVersioned + credential | **partial** | Used for GV2 create; no peer UX |
| SIG-CDS-01 | CDSI v2 contact discovery | `CdsiV2Service` | **done** | `refreshContacts` + token |
| SIG-SAFE-01 | Safety number 1:1 | libsignal fingerprint | **done** | |
| SIG-SAFE-02 | Verified mark persist + group SN | Verified sync | **partial** | Inbound + outbound `markIdentityVerified` + SyncMessage.forVerified; no group SN |

### Sync & storage

| ID | Feature | API / req→resp | SM status | Notes |
|----|---------|----------------|-----------|-------|
| SIG-STOR-01 | Storage Service read | read items | **partial** | Contacts + GV2 only |
| SIG-STOR-02 | Storage Service write | `writeStorageItems` | **missing** | |
| SIG-STOR-03 | Story lists / folders / call-link records | storage record types | **missing** | Ignored |
| SIG-SYNC-01 | Inbound multi-device sync | SyncMessage handlers | **partial** | Core done; some types log-only |
| SIG-SYNC-02 | Outbound sync (verified, settings…) | `sendSyncMessage` | **partial** | Mostly receive |
| SIG-BLOCK-01 | Block list inbound | blocked sync | **partial** | Inbound persist + outbound `setBlockedList` / `syncBlockedList` via `forBlocked`; no block UX |
| SIG-MUTE-01 | Mute/pin/archive | — | **partial** | Local caps only (`localChatListControls`) |

### Stories, calls, payments (mostly blocked / fail-closed)

| ID | Feature | API / req→resp | SM status | Notes |
|----|---------|----------------|-----------|-------|
| SIG-STORY-01 | Send story | `sendGroupStory` / sync | **fail-closed** | Impl exists; `stories=false` |
| SIG-STORY-02 | Receive story | storyMessage | **partial** | Handler exists; `receiveStories=false` on WS |
| SIG-STORY-03 | Story replies / distribution lists | — | **missing** | |
| SIG-CALL-01 | Call signaling (busy/hangup) | `sendCallMessage` | **partial** | Inbound → auto-busy; no media |
| SIG-CALL-02 | RingRTC media / TURN | calling relays | **blocked** | Mandate |
| SIG-CALL-03 | Group calls | group opaque | **blocked** | Needs RingRTC |
| SIG-PAY-01 | MobileCoin / payment messages | payments pkg / sync | **blocked** | Mandate |
| SIG-DON-01 | Donations / subscriptions / gift badges | `DonationsApi` | **blocked** | Payment stack |
| SIG-SPAM-01 | Report spam | `MessageApi.reportSpam` | **missing** | |
| SIG-CFG-01 | Remote config | `RemoteConfigResult` | **missing** | DTO only |

---

## 4. Crosswalk to `FEATURE_GAPS.md` (1.0.17)

| Doc ID | Inventory |
|--------|-----------|
| SG-01 RingRTC | SIG-CALL-02 **blocked** |
| SG-02 Stories fail-closed | SIG-STORY-01 |
| SG-04/05 SVR2 / reg lock | SIG-REG-03/04/05 **blocked** |
| SG-06 Group safety + verified | SIG-SAFE-02 |
| SG-08 Edit outbound | SIG-DM-07 |
| SG-10–14 Disappearing UX, CDS, payments, group admin | CDS = SIG-CDS-01 **done**; rest missing/blocked |

---

## 5. Implementation backlog (feasible, Tor-safe)

Priority order for atomic commits on this branch (excludes blocked):

1. **SIG-DM-04** — outbound quotes (`withQuote`)
2. **SIG-GV2-06** — leave group
3. **SIG-GV2-04** — title/desc/add/remove members (minimal admin)
4. **SIG-EPH-01** — conversation timer update message
5. ~~**SIG-SAFE-02** — mark verified + sync outbound~~ (1:1 done; group SN still open)
6. ~~**SIG-USER-01** — username set (API)~~ (reserve/confirm/delete done; link UI open)
7. ~~**SIG-PROF-02** — profile avatar upload~~
8. **SIG-SEAL-01** — fetch peer profile key for sealed sender
9. **SIG-POLL-02** — poll vote
10. ~~**SIG-GV2-05** — invite links~~ (enable/reset + join by URL; no QR UI)
11. **SIG-STOR-02** — storage write-back (if time)
12. **SIG-STICK-02** — sticker pack retrieve (if time)

**Explicitly not in backlog:** SIG-CALL-02/03, SIG-REG-03–05, SIG-PAY-01, SIG-DON-01.

---

## 6. Verification

```bash
./scripts/sync-signal-vendor.sh   # or copy from synced tree
./gradlew :vendor:signal:libsignal-service:compileDebugKotlin \
          :protocol:signal:assembleDebug \
          :app:assembleDebug
```

Update this file’s status column when a feature lands; keep commits atomic per ID.
