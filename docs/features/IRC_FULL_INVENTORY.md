# IRC — Full Feature Inventory

SecureMessenger `:protocol:irc` (Kitteh / KICL **9.0.0**, Tor SOCKS5 mandatory).  
Specs: RFC 1459, RFC 2812, [IRCv3 registry](https://ircv3.net/registry).  
Blocked by product policy: **DCC** (direct IP — Tor-unsafe).

**Legend**

| Status | Meaning |
|--------|---------|
| **Done** | Wired end-to-end (protocol ± slash UX); Tor-safe |
| **Partial** | Present with honest limits |
| **Missing** | Feasible; not yet wired |
| **Blocked** | Will not implement (policy / Tor) |
| **N/A** | Not applicable to IRC or Kitteh |

---

## Transport & session

| ID | Feature | Spec / Kitteh | Status | Notes |
|----|---------|---------------|--------|-------|
| IRC-T01 | TLS (`SECURE`, 6697) | RFC 7194 / Kitteh `SecurityType.SECURE` | **Done** | System `TrustManagerFactory` (`IrcTls`) |
| IRC-T02 | Cleartext (`INSECURE`) | RFC 2812 | **Done** | Passwords/SASL/NickServ refused without TLS |
| IRC-T03 | Tor SOCKS5 | App policy + Kitteh `ProxyType.SOCKS_5` | **Done** | `IrcTorNetwork` + unresolved DNS |
| IRC-T04 | Stream-isolated SOCKS user | Tor best practice | **Done** | Per-connection `irc-<uuid>` |
| IRC-T05 | Server password (`PASS`) | RFC 2812 | **Done** | Secret `password` |
| IRC-T06 | Multi-account / multi-network | App | **Done** | One Kitteh `Client` per `accountId` |
| IRC-T07 | Auto-reconnect | Kitteh | **Partial** | Kitteh may reconnect; app treats close as disconnect |
| IRC-T08 | STS (Strict Transport Security) | IRCv3 `sts` | **Missing** | Kitteh STS machine available; not enabled |
| IRC-T09 | STARTTLS | IRCv3 `tls` (deprecated) | **N/A** | Prefer native TLS port |
| IRC-T10 | WebSocket IRC | IRCv3 websockets | **N/A** | Not in Kitteh 9 path we use |

---

## Registration & identity

| ID | Feature | Spec / Kitteh | Status | Notes |
|----|---------|---------------|--------|-------|
| IRC-A01 | NICK / USER / real name | RFC 2812 | **Done** | Secrets `nick`, `user`, `realName` |
| IRC-A02 | Nick change (`NICK`) | RFC 2812 | **Done** | `/nick`; `UserNickChangeEvent` |
| IRC-A03 | SASL PLAIN | IRCv3 sasl | **Done** | `SaslPlain`; await 903 / fail 902–908 |
| IRC-A04 | NickServ IDENTIFY | Services | **Done** | Kitteh `NickServ` |
| IRC-A05 | SASL EXTERNAL / ECDSA | IRCv3 | **Missing** | Kitteh has classes; cert UX absent |
| IRC-A06 | Oper (`OPER`) | RFC 2812 | **Missing** | Kitteh `OperCommand`; high privilege |
| IRC-A07 | SETNAME | IRCv3 `setname` | **Missing** | Cap in Kitteh defaults; no slash |
| IRC-A08 | Account registration UX | Services | **N/A** | Out of band (NickServ REGISTER) |

---

## Messaging

| ID | Feature | Spec / Kitteh | Status | Notes |
|----|---------|---------------|--------|-------|
| IRC-M01 | Channel PRIVMSG | RFC 2812 | **Done** | `sendMultiLineMessage` |
| IRC-M02 | Private PRIVMSG (DM) | RFC 2812 | **Done** | |
| IRC-M03 | NOTICE in/out | RFC 2812 | **Partial** | Inbound system msgs; `/notice` outbound |
| IRC-M04 | Multi-line cut | Kitteh cutter | **Done** | |
| IRC-M05 | CTCP ACTION (`/me`) | CTCP | **Done** | Outbound + inbound |
| IRC-M06 | CTCP VERSION/PING/TIME/CLIENTINFO | CTCP | **Done** | Safe auto-reply; no DCC |
| IRC-M07 | CTCP DCC SEND/CHAT | CTCP | **Blocked** | Tor-unsafe direct sockets |
| IRC-M08 | Wallops | RFC 2812 | **Missing** | Rare; Kitteh `WallopsCommand` |
| IRC-M09 | Local reply UX | App `messageReply` | **Partial** | Cap true; no IRCv3 `+reply` wire yet for text |
| IRC-M10 | Message edit/delete | — | **N/A** | Not in classic IRC |

---

## Channels

| ID | Feature | Spec / Kitteh | Status | Notes |
|----|---------|---------------|--------|-------|
| IRC-C01 | JOIN | RFC 2812 | **Done** | `addChannel` / startConversation |
| IRC-C02 | PART | RFC 2812 | **Done** | Events + `/part` + `closeConversation` |
| IRC-C03 | KICK (observe) | RFC 2812 | **Done** | System message |
| IRC-C04 | KICK (issue) | RFC 2812 | **Done** | `/kick` via Kitteh `KickCommand` |
| IRC-C05 | TOPIC observe | RFC 2812 | **Done** | `ChannelTopicEvent` |
| IRC-C06 | TOPIC set/query | RFC 2812 | **Done** | `/topic` |
| IRC-C07 | MODE observe | RFC 2812 | **Done** | `ChannelModeEvent` |
| IRC-C08 | MODE set | RFC 2812 | **Done** | `/mode`, `/op`, `/deop`, `/voice`, `/devoice`, `/ban`, `/unban`, `/key` |
| IRC-C09 | Channel key `+k` JOIN | RFC 2812 | **Done** | `addKeyProtectedChannel`; keys in secrets / `/join #c key` |
| IRC-C10 | Ban list (`+b`) | RFC 2812 | **Done** | `/banlist`; `ChannelModeInfoListEvent` |
| IRC-C11 | Invite (in/out) | RFC 2812 | **Done** | Event + `/invite` |
| IRC-C12 | NAMES / contacts | RFC 2812 | **Done** | `refreshContacts`; UI « Nicks » |
| IRC-C13 | Auto-join list | App | **Done** | Secret `channels` |
| IRC-C14 | Knock (`+i` knock) | Ext | **Missing** | Kitteh `knockChannel` |
| IRC-C15 | Quiet / except / invite lists | Modes `q`/`e`/`I` | **Partial** | Generic `/mode`; no dedicated UX |
| IRC-C16 | LIST directory | RFC 2812 | **Missing** | No channel browser UI |

---

## User info & presence

| ID | Feature | Spec / Kitteh | Status | Notes |
|----|---------|---------------|--------|-------|
| IRC-U01 | WHOIS | RFC 2812 | **Done** | `/whois` + `WhoisEvent` → system msg |
| IRC-U02 | WHO / WHOX | RFC / ISUPPORT | **Missing** | |
| IRC-U03 | QUIT observe | RFC 2812 | **Done** | Shared-channel system lines |
| IRC-U04 | AWAY set/clear | RFC 2812 | **Done** | `/away`, `/back` |
| IRC-U05 | away-notify | IRCv3 | **Partial** | Kitteh default cap; no dedicated UI |
| IRC-U06 | MONITOR | IRCv3 | **Missing** | Kitteh `MonitorCommand` |
| IRC-U07 | account-notify / account-tag | IRCv3 | **Partial** | Negotiated by Kitteh; unused in UI |
| IRC-U08 | chghost | IRCv3 | **Partial** | Cap default; no system line |

---

## IRCv3 capabilities & tags

| ID | Feature | Spec | Status | Notes |
|----|---------|------|--------|-------|
| IRC-V01 | Cap negotiation (Kitteh defaults) | CAP | **Done** | Kitteh `CapabilityManager.Defaults` |
| IRC-V02 | Cap honesty / logging | App | **Done** | Negotiated set stored; `/caps` |
| IRC-V03 | `message-tags` | IRCv3 | **Partial** | Negotiated; used for react/time |
| IRC-V04 | `server-time` | IRCv3 | **Done** | `MessageTag.Time` → message timestamp |
| IRC-V05 | `echo-message` | IRCv3 | **Done** | Prefer echo over local mirror when negotiated |
| IRC-V06 | `batch` | IRCv3 | **Partial** | Kitteh handles; chathistory consumer |
| IRC-V07 | `labeled-response` | IRCv3 | **Partial** | Kitteh default; unused by app |
| IRC-V08 | `draft/chathistory` | IRCv3 draft | **Done** | CAP REQ + `CHATHISTORY` + `loadMessageHistory` |
| IRC-V09 | `+draft/react` / unreact | Client tag | **Done** | `/react`; TAGMSG inbound → `payloadJson` |
| IRC-V10 | `+reply` (msgids) | Client tag | **Partial** | Used with react; text reply wire optional |
| IRC-V11 | `msgid` | IRCv3 | **Partial** | Stored in `payloadJson` when present |
| IRC-V12 | `multiline` | IRCv3 draft | **Missing** | Kitteh multi-line PRIVMSG fallback |
| IRC-V13 | `standard-replies` | IRCv3 | **Missing** | |
| IRC-V14 | Cap honesty flags | App caps | **Done** | `reactions` / `messageHistory` when caps allow |

---

## App / MessengerProtocol surface

| ID | Feature | Status | Notes |
|----|---------|--------|-------|
| IRC-P01 | `directMessages` / `groupChats` | **Done** | |
| IRC-P02 | `contacts` (NAMES) | **Done** | Connected-only |
| IRC-P03 | `backupExport` | **Done** | Local JSON |
| IRC-P04 | Media / E2EE / calls / stories | **N/A** | Caps false |
| IRC-P05 | `localChatListControls` | **Done** | |
| IRC-P06 | Slash command surface | **Done** | See `/help` |
| IRC-P07 | UI topic editor widget | **Partial** | Via `/topic` (no dedicated chrome) |
| IRC-P07b | Contacts label « Nicks » | **Done** | |

---

## Historical FEATURE_GAPS IDs

| Gap ID | Maps to | Status |
|--------|---------|--------|
| IRC-01 | Topic edit | **Done** (`/topic`) |
| IRC-02 | WHOIS | **Done** |
| IRC-03 | IRCv3 honesty | **Done** |
| IRC-04 | `/me` ACTION | **Done** |
| IRC-05 | DCC | **Blocked** |
| IRC-06 | Modes | **Done** |
| IRC-07 | Nicks label | **Done** |
| IRC-08 | Banlists | **Done** |
| IRC-09 | `+k` | **Done** |

---

## Slash commands (client)

| Command | IDs covered |
|---------|-------------|
| `/me`, `/help`, `/caps` | M05, V02, P06 |
| `/topic`, `/whois`, `/nick`, `/away`, `/back` | C06, U01, A02, U04 |
| `/join`, `/part`, `/kick`, `/invite` | C01–C04, C11, C09 |
| `/mode`, `/op`, `/deop`, `/voice`, `/devoice` | C08 |
| `/ban`, `/unban`, `/banlist`, `/key` | C08–C10, C09 |
| `/notice`, `/msg`, `/quote` | M03, M01–02 |
| `/react`, `/unreact` | V09 |
| `/names` | C12 |
| `/ctcp` (VERSION/PING/TIME/CLIENTINFO only) | M06 |

---

## Implementation map

| Layer | Path |
|-------|------|
| Adapter | `protocol/irc/.../IrcProtocol.kt` |
| Slash | `protocol/irc/.../IrcSlashCommands.kt` |
| Targets / keys | `protocol/irc/.../IrcTargets.kt` |
| Tor | `protocol/irc/.../IrcTorNetwork.kt` |
| TLS | `protocol/irc/.../IrcTls.kt` |
| Tests | `protocol/irc/src/test/...` |
| Account UI | `app/.../AccountsScreen.kt` |
| Docs | `docs/irc.md`, this file |
