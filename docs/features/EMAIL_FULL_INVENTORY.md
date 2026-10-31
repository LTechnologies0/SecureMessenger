# Email protocol — full feature inventory

Scope: `:protocol:email` (IMAP / SMTP / POP3 / JMAP / MIME) vs RFCs and K-9 / Thunderbird expectations.  
Tor is **mandatory** for all network paths. OpenPGP / S/MIME full crypto UX is **blocked**. Prefer **bounded progressive IMAP backfill** over unbounded archive.

**Legend**

| Status | Meaning |
|--------|---------|
| **done** | Implemented end-to-end (protocol ± wired app affordance) |
| **proto** | Protocol API / engine present; messenger UI may be thin |
| **partial** | Subset of K-9/Thunderbird behaviour |
| **absent** | Not implemented |
| **blocked** | Explicitly out of scope / product deferral |

RFC anchors: IMAP4rev1 [RFC 3501](https://www.rfc-editor.org/rfc/rfc3501), IDLE [RFC 2177](https://www.rfc-editor.org/rfc/rfc2177), SPECIAL-USE [RFC 6154](https://www.rfc-editor.org/rfc/rfc6154), SMTP [RFC 5321](https://www.rfc-editor.org/rfc/rfc5321) / submission [RFC 6409](https://www.rfc-editor.org/rfc/rfc6409), MIME [RFC 2045–2049](https://www.rfc-editor.org/rfc/rfc2045), message format [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322), JMAP Core/Mail [RFC 8620](https://www.rfc-editor.org/rfc/rfc8620) / [RFC 8621](https://www.rfc-editor.org/rfc/rfc8621), POP3 [RFC 1939](https://www.rfc-editor.org/rfc/rfc1939).

---

## 1. Account & transport

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-ACCT-01 | IMAP/IMAPS connect (SSL / STARTTLS / NONE) | **done** | Angus Mail + Tor SOCKS |
| EM-ACCT-02 | POP3/POP3S connect | **done** | Poll sync |
| EM-ACCT-03 | JMAP session (HTTPS Basic) | **done** | OkHttp via Tor |
| EM-ACCT-04 | SMTP/SMTPS submission | **done** | Auth required |
| EM-ACCT-05 | Tor-only fail-closed | **done** | `NetworkGuard` + SOCKS / proxied HTTP |
| EM-ACCT-06 | Password auth | **done** | |
| EM-ACCT-07 | OAuth2 / XOAUTH2 | **blocked** | Deferred (v1 password only) |
| EM-ACCT-08 | Thunderbird ISPDB autoconfig | **done** | `autoconfig.thunderbird.net` |
| EM-ACCT-09 | DNS SRV (`_imaps`, `_submission`, `_pop3s`) via DoH | **done** | Cloudflare DoH over Tor |
| EM-ACCT-10 | Domain `.well-known/autoconfig` | **proto** | Fallback after ISPDB |
| EM-ACCT-11 | Multi-account Email sessions | **done** | `ConcurrentHashMap` sessions |
| EM-ACCT-12 | From identity (display name) | **done** | Credential `fromDisplayName` → MIME From |
| EM-ACCT-13 | Multiple From identities / aliases UI | **absent** | JMAP uses first `Identity/get` only |
| EM-ACCT-14 | Capability honesty per store kind | **done** | JMAP `mediaSend=false` |

---

## 2. Folders / mailboxes (RFC 3501 LIST + RFC 6154)

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-FLD-01 | Configurable sync folder (default INBOX) | **done** | Credential `folder` |
| EM-FLD-02 | List mailboxes | **proto** | `EmailProtocol.listMailboxes` |
| EM-FLD-03 | SPECIAL-USE / name-heuristic roles | **proto** | Sent, Drafts, Trash, Archive, Junk |
| EM-FLD-04 | Sent folder sync (UID incremental) | **done** | ~50 recent + UID watermark |
| EM-FLD-05 | Drafts folder detect + APPEND draft | **proto** | `saveDraft` |
| EM-FLD-06 | Trash detect + move-to-trash | **proto** | `trashMessages` (COPY+\\Deleted) |
| EM-FLD-07 | Archive folder detect + move | **proto** | `moveMessages` → Archive |
| EM-FLD-08 | Junk/Spam folder detect + move | **proto** | Role mapping only |
| EM-FLD-09 | Create / rename / delete folders | **absent** | K-9 historically cautious |
| EM-FLD-10 | Subscribe / LSUB UI | **absent** | |
| EM-FLD-11 | Multi-folder messenger UI | **absent** | P2 app; protocol list ready |
| EM-FLD-12 | Namespace / hierarchy delimiter UX | **absent** | |

---

## 3. Sync, UID, IDLE, backfill

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-SYNC-01 | UID FETCH incremental (`lastImapUid`) | **done** | Per INBOX |
| EM-SYNC-02 | Initial recent window (~100 INBOX / ~50 Sent) | **done** | |
| EM-SYNC-03 | IMAP IDLE (one folder, mutex with sync) | **done** | RFC 2177 via Angus |
| EM-SYNC-04 | Bounded progressive older backfill | **proto** | Chunked older UIDs; `oldestImapUid` |
| EM-SYNC-05 | Unbounded full-mailbox archive | **blocked** | Prefer EM-SYNC-04 |
| EM-SYNC-06 | CONDSTORE / QRESYNC | **absent** | |
| EM-SYNC-07 | POP3 poll (~90s) | **done** | |
| EM-SYNC-08 | POP3 leave-on-server policy | **done** | Credential + login toggle |
| EM-SYNC-09 | JMAP `Email/query` recent limit | **done** | Default 50 |
| EM-SYNC-10 | JMAP push / EventSource | **absent** | |
| EM-SYNC-11 | Sent UID watermark | **done** | `lastImapSentUid` |

---

## 4. Flags (RFC 3501 STORE)

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-FLAG-01 | `\Seen` (markRead) | **done** | Recent-window Message-ID match |
| EM-FLAG-02 | `\Flagged` star | **proto** | `setFlagged` |
| EM-FLAG-03 | `\Deleted` + expunge / trash move | **proto** | Via trash/move |
| EM-FLAG-04 | `\Answered` on reply | **partial** | Set best-effort after reply send |
| EM-FLAG-05 | `\Draft` on draft APPEND | **proto** | |
| EM-FLAG-06 | Custom keywords / user flags | **absent** | |
| EM-FLAG-07 | `$Junk` / `$Forwarded` | **absent** | |

---

## 5. Search

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-SRCH-01 | Local conversation/body search (app) | **absent** | APP-04 |
| EM-SRCH-02 | IMAP SEARCH (SUBJECT/FROM/TEXT) | **proto** | `searchMailbox` |
| EM-SRCH-03 | JMAP `Email/query` text filter | **proto** | `searchMailbox` when JMAP |
| EM-SRCH-04 | UID SEARCH + FETCH bodies into inbox | **partial** | Returns Message-IDs; optional persist |

---

## 6. Compose / MIME / recipients

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-CMP-01 | Plain-text body | **done** | |
| EM-CMP-02 | Subject (+ `Subject:` parse) | **done** | |
| EM-CMP-03 | To | **done** | |
| EM-CMP-04 | Cc | **done** | SMTP + JMAP |
| EM-CMP-05 | Bcc | **done** | SMTP + JMAP |
| EM-CMP-06 | Reply headers (`In-Reply-To`, `References`) | **done** | |
| EM-CMP-07 | Attachments send (multipart) | **done** | IMAP/SMTP; 25 MiB cap |
| EM-CMP-08 | Attachments receive | **done** | Cache dir |
| EM-CMP-09 | HTML part capture (`htmlBody` payload) | **done** | Strip for `body`; raw in payload |
| EM-CMP-10 | HTML compose (`multipart/alternative`) | **partial** | Optional `htmlBody` on build |
| EM-CMP-11 | Forward | **absent** | Cap `messageForward=false` |
| EM-CMP-12 | Draft save (IMAP APPEND Drafts) | **proto** | |
| EM-CMP-13 | JMAP blob/attachment send | **blocked** honesty | `mediaSend=false` for JMAP |
| EM-CMP-14 | APPEND copy to Sent after SMTP | **done** | K-9 “upload sent” |
| EM-CMP-15 | Charset UTF-8 | **done** | |
| EM-CMP-16 | Nested multipart / related CID | **partial** | Collects text + attachments |

---

## 7. Threading & conversations

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-THR-01 | Root Message-ID via References / In-Reply-To | **done** | |
| EM-THR-02 | Stable `accountId:thread:<hash>` | **done** | |
| EM-THR-03 | Pre-send mailbox shell → promote on send | **done** | |
| EM-THR-04 | JMAP `threadId` as primary key | **partial** | Still uses Message-ID root |
| EM-THR-05 | Subject-based fallback threading | **absent** | |

---

## 8. Crypto / privacy (product)

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-PGP-01 | OpenPGP encrypt / sign / decrypt UX | **blocked** | Vague C |
| EM-PGP-02 | S/MIME | **blocked** | |
| EM-PGP-03 | Autocrypt | **blocked** | |
| EM-PGP-04 | Detect encrypted MIME as opaque attachment | **absent** | |

---

## 9. Contacts, backup, misc

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| EM-MISC-01 | Contacts derived from peers | **done** | |
| EM-MISC-02 | JSON backup export | **done** | |
| EM-MISC-03 | Wipe local email media | **done** | |
| EM-MISC-04 | Move between folders | **proto** | `moveMessages` |
| EM-MISC-05 | Copy between folders | **proto** | Internal COPY |
| EM-MISC-06 | MDN / DSN / Disposition-Notification | **absent** | |
| EM-MISC-07 | Sieve / server filters | **absent** | |
| EM-MISC-08 | IMAP NOTIFY / push | **absent** | IDLE covers push-ish |
| EM-MISC-09 | Offline queue / outbox | **absent** | Fail on send |
| EM-MISC-10 | HTML sanitized viewer UI | **absent** | Payload ready (EM-CMP-09) |
| EM-MISC-11 | Per-message headers inspector | **absent** | |

---

## 10. Mapping to legacy FEATURE_GAPS IDs

| Legacy | Inventory | Status |
|--------|-----------|--------|
| EM-01 | EM-CMP-05 | **done** |
| EM-02 | EM-FLD-02/11 | **proto** / UI absent |
| EM-03 | EM-SYNC-04/05 | progressive **proto**; full archive **blocked** |
| EM-05 | EM-FLD-05 / EM-CMP-12 | **proto** |
| EM-06 | EM-PGP-* | **blocked** |
| EM-07 | EM-ACCT-14 / EM-CMP-13 | **done** |
| EM-08 | EM-CMP-09 / EM-MISC-10 | capture **done**; viewer absent |
| EM-09 | EM-SRCH-* | **proto** |
| EM-10 | EM-MISC-04 / EM-FLD-06 | **proto** |
| EM-11 | EM-ACCT-12/13 | display name **done**; multi-id absent |
| EM-12 | EM-SYNC-08 | **done** |

---

## 11. Implementation wave (this branch)

Implemented or upgraded on `feat/parity-email`:

- EM-ACCT-10, EM-ACCT-12  
- EM-FLD-02–08 (protocol)  
- EM-SYNC-04 (bounded backfill)  
- EM-FLAG-02–05 (protocol)  
- EM-SRCH-02/03  
- EM-CMP-09, EM-CMP-12, EM-CMP-14  
- EM-MISC-04/05  
- EM-SYNC-08 login toggle  

Still blocked / absent by design: EM-ACCT-07, EM-SYNC-05, EM-PGP-*, EM-FLD-09–11 UI, EM-MISC-10 rich HTML viewer.
