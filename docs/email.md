# Email protocol (`:protocol:email`)

SecureMessenger treats email as a first-class `MessengerProtocol` (`ProtocolId.EMAIL`).

## Store kinds

| Kind | Receive | Send |
|------|---------|------|
| `IMAP` | Angus Mail IMAP/IMAPS + IDLE | SMTP/SMTPS |
| `POP3` | Angus Mail POP3/POP3S (poll) | SMTP/SMTPS |
| `JMAP` | RFC 8620/8621 over HTTPS (OkHttp) | `EmailSubmission/set` |

Password auth only in v1 (OAuth2 / XOAUTH2 deferred).

## Credential keys (`EncryptedCredentialStore`)

| Key | Meaning |
|-----|---------|
| `email` | Mailbox address |
| `password` | Account password |
| `storeKind` | `IMAP` \| `POP3` \| `JMAP` |
| `folder` | IMAP folder (default `INBOX`) |
| `imapHost` / `imapPort` / `imapSecurity` | IMAP endpoint (`SSL` \| `STARTTLS` \| `NONE`) |
| `pop3Host` / `pop3Port` / `pop3Security` / `pop3LeaveOnServer` | POP3 endpoint |
| `smtpHost` / `smtpPort` / `smtpSecurity` | Submission endpoint |
| `jmapSessionUrl` | JMAP session resource URL |
| `lastImapUid` | Incremental IMAP UID watermark |

## Conversations

Threads are keyed by a stable hash of the root `Message-ID` (from `References` / `In-Reply-To` / own id). Compose-to-address uses `accountId:mailbox:peer@domain` until the first send promotes a thread id.

## Tor

Tor is mandatory:

- Angus uses `mail.{imap,smtp,pop3,imaps,smtps,pop3s}.socks.host/port` via `EmailSocksProperties` + `SocksEndpointResolver`.
- JMAP / ISPDB / DNS SRV (DoH) use `ProxiedHttpClientFactory.okhttpClient()`.

Fail-closed: `NetworkGuard.assertNetworkAllowed()` before connect.

## Autoconfig

1. Thunderbird ISPDB: `https://autoconfig.thunderbird.net/v1.1/{domain}`
2. Domain well-known: `https://autoconfig.{domain}/mail/config-v1.1.xml` then `/.well-known/autoconfig/mail/config-v1.1.xml`
3. Fallback DNS SRV via Cloudflare DoH (`_imaps._tcp`, `_submission._tcp`, `_pop3s._tcp`)

## IMAP extras (protocol APIs)

- SPECIAL-USE / name heuristics: Sent, Drafts, Trash, Archive, Junk
- Bounded progressive older backfill (`backfillOlder`, chunk ≤100) — not full archive
- APPEND Sent after SMTP; Drafts APPEND; trash/move; `\Flagged` / `\Answered`
- IMAP SEARCH / JMAP text query
- HTML part stored in `payloadJson.htmlBody` (sanitized viewer still app-side)

Full ID matrix: [`docs/features/EMAIL_FULL_INVENTORY.md`](features/EMAIL_FULL_INVENTORY.md).

## Capabilities

`directMessages`, `mediaSend`/`mediaReceive`, `messageHistory`, `contacts`, `backupExport`. No groups, no E2EE (OpenPGP out of scope), no in-app registration.
