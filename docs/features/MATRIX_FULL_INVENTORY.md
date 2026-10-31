# Matrix CS API — Full Feature Inventory

**Worktree:** `parity-matrix` · **Branch:** `feat/parity-matrix`  
**Stack:** Trixnity **4.22.7** (`:protocol:matrix`) · Tor SOCKS mandatory  
**Spec baseline:** Matrix Client-Server API **v1.14** (stable) + widely deployed MSCs  
**Legend:** `done` · `partial` · `missing` · `blocked` · `n/a`

Tor constraint: all CS HTTP goes through SOCKS (`MatrixHttpClientFactory`). Clearnet / UDP WebRTC paths are out of scope.

---

## Status summary

| Bucket | Count (approx.) |
|--------|-----------------|
| Done | core auth, E2EE Megolm, sync, DM/rooms join/create, text/media/voice/location, typing, receipts, outbound react/edit/redact |
| Partial | inbound aggregations, protocol reply, room admin, directory, spaces/threads |
| Missing feasible | room leave/invite/state writers, knock, tags, ignore list, public directory, inbound relations, thread send |
| Blocked | QR cross-signing ceremony (large UX), MatrixRTC / Element Call (no WebRTC), Jitsi widgets |

---

## A. Discovery & session

| ID | Feature | CS API / events | Status | Notes |
|----|---------|-----------------|--------|-------|
| MX-DISC-01 | `.well-known/matrix/client` | `GET https://{host}/.well-known/matrix/client` | **done** | `MatrixUrls.resolveApiBaseUrl` |
| MX-DISC-02 | Homeserver versions | `GET /_matrix/client/versions` | **done** | probed in discovery |
| MX-AUTH-01 | Login flows | `GET /_matrix/client/v3/login` | **done** | `MatrixLoginFlows` |
| MX-AUTH-02 | Password login | `POST /login` `m.login.password` | **done** | Trixnity `loginWithPassword` |
| MX-AUTH-03 | Token soft-login | `POST /login` / restore store | **done** | `loginWithAccessToken` + H2 |
| MX-AUTH-04 | SSO + `loginToken` | `/login/sso/redirect` + `m.login.token` | **done** | Tor WebView; one-shot token |
| MX-AUTH-05 | Registration + UIA | `POST /register` + auth stages | **done** | dummy / token / WebView |
| MX-AUTH-06 | Username available | `GET /register/available` | **done** | |
| MX-AUTH-07 | Whoami | `GET /account/whoami` | **done** | |
| MX-AUTH-08 | Logout / invalidate | `POST /logout` | **partial** | local wipe + disconnect; no explicit logout call |
| MX-AUTH-09 | Soft-logout UX | sync 401 → re-auth | **partial** | fail paths clear session; no Element soft-logout UI |
| MX-AUTH-10 | Refresh tokens | MSC3861 / OIDC refresh | **missing** | OIDC via SSO only today |
| MX-SESS-01 | Device id persistence | login response `device_id` | **done** | credential store |
| MX-SESS-02 | Multi-account sessions | local | **done** | `MatrixProtocol` session map |
| MX-SESS-03 | Tor gate | SOCKS required | **done** | fail if SOCKS down |

---

## B. Sync & filters

| ID | Feature | CS API | Status | Notes |
|----|---------|--------|--------|-------|
| MX-SYNC-01 | `/sync` long-poll | `GET /_matrix/client/v3/sync` | **done** | via Trixnity client |
| MX-SYNC-02 | Plaintext HTTP sync | same | **n/a** | `MatrixSyncEngine` orphaned; E2EE fail-closed |
| MX-SYNC-03 | Client filters | `POST /user/{userId}/filter` | **missing** | Trixnity defaults |
| MX-SYNC-04 | Sliding Sync | MSC3575 proxy | **missing** | optional; Tor-friendly but separate stack |
| MX-TODEV-01 | To-device | `PUT /sendToDevice/...` | **partial** | crypto only (passive) |

---

## C. Rooms — membership & directory

| ID | Feature | CS API | Status | Notes |
|----|---------|--------|--------|-------|
| MX-ROOM-01 | Create room | `POST /createRoom` | **done** | DM + group + encryption initial state |
| MX-ROOM-02 | Join by id | `POST /join/{roomId}` | **done** | `ensureRoom` `!` |
| MX-ROOM-03 | Join by alias | `POST /join/{alias}` | **done** | `#` |
| MX-ROOM-04 | Leave room | `POST /rooms/{roomId}/leave` | **done** | protocol API; no dedicated leave UI yet |
| MX-ROOM-05 | Forget room | `POST /rooms/{roomId}/forget` | **done** | protocol API |
| MX-ROOM-06 | Invite | `POST /rooms/{roomId}/invite` | **done** | create + invite-to-existing |
| MX-ROOM-07 | Kick | `POST /rooms/{roomId}/kick` | **done** | protocol API |
| MX-ROOM-08 | Ban / unban | `POST .../ban` `/unban` | **done** | protocol API |
| MX-ROOM-09 | Knock | `POST /knock/{roomIdOrAlias}` | **done** | protocol API |
| MX-ROOM-10 | Joined rooms list | sync / `GET /joined_rooms` | **done** | Trixnity room observe |
| MX-ROOM-11 | Joined members | `GET /rooms/{id}/joined_members` | **partial** | contacts/titles; no member UI |
| MX-ROOM-12 | Public room directory | `GET/POST /publicRooms` | **done** | protocol `listPublicRooms`; Tor-slow OK |
| MX-ROOM-13 | Room visibility | `GET/PUT /directory/list/room/{id}` | **missing** | |
| MX-ROOM-14 | Room aliases | `GET/PUT/DELETE /directory/room/{alias}` | **partial** | join `#` only |
| MX-ROOM-15 | Upgrade room | `POST /rooms/{id}/upgrade` | **missing** | |
| MX-ROOM-16 | Hierarchy (spaces children) | `GET /rooms/{id}/hierarchy` | **done** | protocol `getSpaceHierarchy`; no space nav UI |

---

## D. Room state & power

| ID | Feature | CS API / event | Status | Notes |
|----|---------|----------------|--------|-------|
| MX-STATE-01 | Read `m.room.name` | state | **partial** | titles via Trixnity `room.name` |
| MX-STATE-02 | Set name | `PUT .../state/m.room.name` | **done** | protocol API |
| MX-STATE-03 | Set topic | `PUT .../state/m.room.topic` | **done** | protocol API |
| MX-STATE-04 | Room avatar | `m.room.avatar` | **missing** | media upload + state |
| MX-STATE-05 | Power levels | `m.room.power_levels` | **missing** | needs ACL UI |
| MX-STATE-06 | Join rules | `m.room.join_rules` | **missing** | |
| MX-STATE-07 | History visibility | `m.room.history_visibility` | **missing** | |
| MX-STATE-08 | Guest access | `m.room.guest_access` | **missing** | |
| MX-STATE-09 | Canonical alias | `m.room.canonical_alias` | **missing** | |
| MX-STATE-10 | Encryption enable | `m.room.encryption` | **done** | on create |

---

## E. Messaging

| ID | Feature | CS API / event | Status | Notes |
|----|---------|----------------|--------|-------|
| MX-MSG-01 | Send text | `PUT .../send/m.room.message` | **done** | |
| MX-MSG-02 | Send image/video/file | media + message | **done** | |
| MX-MSG-03 | Send audio / voice | `m.audio` | **done** | |
| MX-MSG-04 | Send location | `m.location` | **done** | |
| MX-MSG-05 | Send emote `/me` | `m.emote` | **done** | `sendEmote` |
| MX-MSG-06 | Send notice | `m.notice` | **missing** | |
| MX-MSG-07 | Stickers | `m.sticker` | **missing** | cap `stickers=false` |
| MX-MSG-08 | Timeline history | `/messages` + gaps | **done** | `fillTimelineGaps` |
| MX-MSG-09 | Live timeline | sync | **done** | |
| MX-MSG-10 | Media download (mxc) | `/media/...` | **partial** | on history path; live often deferred |
| MX-MSG-11 | Encrypted media | Megolm + EncryptedFile | **done** | |
| MX-MSG-12 | Local echo / txn | txnId | **partial** | library |

---

## F. Relations (MSC1849 / MSC2674 / MSC2675 / MSC2676 / MSC3440)

| ID | Feature | Event / API | Status | Notes |
|----|---------|-------------|--------|-------|
| MX-REL-01 | Reactions outbound | `m.reaction` annotation | **done** | UI + Trixnity `react` |
| MX-REL-02 | Reactions inbound | same + aggregations | **done** | merge into target `payloadJson.reactions` |
| MX-REL-03 | Edit outbound | `m.replace` | **done** | |
| MX-REL-04 | Edit inbound | apply replace to target | **done** | updates original bubble |
| MX-REL-05 | Redact outbound | `POST .../redact/...` | **done** | |
| MX-REL-06 | Redact inbound | `RedactedEventContent` | **done** | `[Message supprimé]` |
| MX-REL-07 | Reply outbound | `m.in_reply_to` | **done** | ChatScreen + `OutgoingContent.Text.replyToEventId` |
| MX-REL-08 | Reply inbound | parse relates_to | **done** | `payloadJson.replyTo` |
| MX-REL-09 | Threads send | `m.thread` (MSC3440) | **done** | `threadRootEventId`; no thread list UI |
| MX-REL-10 | Threads list UI | `/rooms/{id}/threads` | **missing** | no thread UI model |
| MX-REL-11 | Forward | client-side re-send | **missing** | cap false |
| MX-REL-12 | Relations API | `GET .../relations/...` | **partial** | via Trixnity aggregations |

---

## G. E2EE & verification

| ID | Feature | CS API | Status | Notes |
|----|---------|--------|--------|-------|
| MX-E2EE-01 | Megolm rooms | `m.room.encryption` + to-device | **done** | fail-closed connect |
| MX-E2EE-02 | Olm sessions | crypto module | **done** | Trixnity |
| MX-E2EE-03 | Device keys upload | `/keys/upload` | **done** | library |
| MX-E2EE-04 | Key query / claim | `/keys/query` `/claim` | **done** | library |
| MX-E2EE-05 | Cross-signing bootstrap | `/keys/device_signing/upload` | **missing** | library-capable; no UX |
| MX-E2EE-06 | SAS verification | to-device `m.key.verification.*` | **missing** | |
| MX-E2EE-07 | QR cross-signing ceremony | MSC3906 / related | **blocked** | too large for this pass |
| MX-E2EE-08 | Key backup (server) | `/room_keys/version` | **missing** | `exportBackup` is local JSON only |
| MX-E2EE-09 | SSSS / 4S | account data secret storage | **missing** | |
| MX-E2EE-10 | Dehydrated devices | MSC3814 | **missing** | |

---

## H. Presence, receipts, typing, push

| ID | Feature | CS API | Status | Notes |
|----|---------|--------|--------|-------|
| MX-EPHEM-01 | Typing send/recv | `/rooms/{id}/typing/...` | **done** | |
| MX-EPHEM-02 | Read markers | `/rooms/{id}/read_markers` | **done** | |
| MX-EPHEM-03 | Read receipts | `/rooms/{id}/receipt/...` | **done** | send; no UI aggregation |
| MX-EPHEM-04 | Presence | `/presence/...` | **missing** | privacy: keep off by default |
| MX-PUSH-01 | Pushers | `/pushers/...` | **missing** | no FCM (Tor policy) |
| MX-PUSH-02 | Push rules | `/pushrules/...` | **missing** | local notifs only |
| MX-PUSH-03 | UnifiedPush | — | **n/a** | product: Tor foreground |

---

## I. Profile, account data, 3PID

| ID | Feature | CS API | Status | Notes |
|----|---------|--------|--------|-------|
| MX-PROF-01 | Display name get/set | `/profile/.../displayname` | **done** | |
| MX-PROF-02 | Avatar | `/profile/.../avatar_url` | **missing** | |
| MX-ACC-01 | Account data generic | `/user/.../account_data/...` | **partial** | unused by app |
| MX-ACC-02 | Ignored users | `m.ignored_user_list` | **done** | protocol API |
| MX-ACC-03 | Room tags / favourite | `/user/.../rooms/.../tags` | **done** | `m.favourite` + local pin |
| MX-ACC-04 | Direct room map | `m.direct` | **partial** | Trixnity DM detect |
| MX-3PID-01 | Bind email/phone | `/account/3pid/...` | **missing** | privacy low priority |
| MX-ID-01 | Identity server | `_matrix/identity/...` | **n/a** | avoid (privacy) |

---

## J. Spaces, polls, widgets, calls

| ID | Feature | Spec | Status | Notes |
|----|---------|------|--------|-------|
| MX-SPACE-01 | Space rooms (`m.space`) | room type + parent/child | **partial** | hierarchy API done; no space nav UI |
| MX-SPACE-02 | Space summary / navigate | hierarchy | **missing** | UI |
| MX-POLL-01 | Polls MSC3381 | `org.matrix.msc3381.poll.*` | **missing** | cap `polls=false` |
| MX-CALL-01 | Legacy VoIP `m.call.*` | WebRTC | **blocked** | no WebRTC + Tor UDP |
| MX-CALL-02 | MatrixRTC / Element Call | LiveKit/WebRTC | **blocked** | mandate |
| MX-WID-01 | Widgets / Jitsi | `im.vector.modular.widgets` | **blocked** | Jitsi needs WebRTC |
| MX-SEARCH-01 | Server search | `POST /search` | **missing** | Tor latency; local DB possible |

---

## K. Capability honesty (`ProtocolCapabilities`)

| Flag | Matrix value | Honesty |
|------|--------------|---------|
| `endToEndEncryption` / media | gated on live Trixnity | OK |
| `reactions` / `messageEdit` / `messageDelete` | true | OK after inbound fix |
| `messageReply` | true | was UI-quote only → protocol reply |
| `polls` / `stickers` / `voiceCalls` / `videoCalls` / `stories` | false | OK |
| `messageForward` | false | OK |
| `localChatListControls` | true | local pin/mute/archive |
| `backupExport` | true | local dump ≠ Matrix key backup |

---

## L. Implementation queue (this branch)

Ordered feasible work (protocol module first; Tor-safe):

1. MX-REL-02/04/06/07/08 — inbound aggregations + protocol reply  
2. MX-ROOM-04/05/06/07/08/09/12/16 — leave/forget/invite/kick/ban/knock/directory/hierarchy  
3. MX-STATE-02/03 — set name/topic  
4. MX-ACC-02/03 — ignore + favourite tag  
5. MX-REL-09 — thread reply send  
6. MX-MSG-05 — emote  

**Explicitly not in this pass:** MX-E2EE-07 (QR), MX-CALL-*, MX-WID-01, full cross-signing UX, key backup UX, polls UI, stickers pack UX.

---

## M. Code map

| Area | Path |
|------|------|
| Protocol facade | `protocol/matrix/.../MatrixProtocol.kt` |
| Trixnity engine | `protocol/matrix/.../TrixnityMatrixEngine.kt` |
| Discovery / SSO / register | `MatrixUrls.kt`, `MatrixLoginFlows.kt`, `MatrixRegistration.kt` |
| Tor HTTP | `MatrixHttpClientFactory.kt` |
| Caps | `MatrixProtocol` `baseCapabilities` |
| UI react/edit/redact | `app/.../ChatScreen.kt`, `MainViewModel.kt` |

---

*Generated for SecureMessenger Matrix parity. Update status column when IDs land.*
