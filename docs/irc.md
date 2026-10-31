# IRC protocol (`:protocol:irc`)

SecureMessenger treats IRC as a first-class `MessengerProtocol` (`ProtocolId.IRC`), backed by [Kitteh IRC Client Library](https://kitteh.org/irc-client-library/) (KICL) 9.x over Netty.

Full discrete feature IDs: [`docs/features/IRC_FULL_INVENTORY.md`](features/IRC_FULL_INVENTORY.md).

## Features

| Area | Support |
|------|---------|
| Transport | TLS (`SECURE`, default port 6697) via Android/JVM system `TrustManagerFactory`, or cleartext (`INSECURE`, e.g. 6667). Kitteh’s built-in trust defaults are **not** used — they reject Libera and other Let’s Encrypt networks. |
| Tor | SOCKS5 via `Client.Builder.proxy()` + `ProxyType.SOCKS_5` (mandatory); resolver throws; stream-isolated SOCKS user |
| Auth | Optional server password; NickServ; SASL PLAIN (`saslPassword` / `sasl=true`) |
| Channels | JOIN / PART / KICK / MODE / TOPIC; auto-join; `+k` keys (`#chan=key`); banlists |
| DMs | PRIVMSG to nick |
| Slash | `/me` `/topic` `/whois` `/nick` `/join` `/part` `/kick` `/invite` `/mode` `/op` `/deop` `/voice` `/devoice` `/ban` `/unban` `/banlist` `/key` `/notice` `/msg` `/names` `/away` `/back` `/ctcp` `/react` `/unreact` `/caps` `/quote` `/help` |
| IRCv3 | Kitteh default caps + optional `echo-message` / `invite-notify` / `draft/chathistory`; `server-time` timestamps; `+draft/react` via TAGMSG |
| Contacts | Snapshot from channel NAMES (`refreshContacts`); UI « Nicks » |
| Media / E2EE / DCC | Not supported (DCC blocked — Tor-unsafe) |

## Credential keys (`EncryptedCredentialStore`)

| Key | Meaning |
|-----|---------|
| `host` | IRC server hostname (required) |
| `nick` | Nickname (required) |
| `port` | Port (default `6697`) |
| `tls` / `useTls` | `true`/`false` (default: TLS unless port is `6667`) |
| `user` | Ident username (default: nick) |
| `realName` | GECOS / real name (default: display name) |
| `password` | Optional server password |
| `nickServPassword` | NickServ account password |
| `saslPassword` | SASL PLAIN password (implies SASL when set) |
| `sasl` | `true` to force SASL PLAIN with NickServ password |
| `channels` | Auto-join list: `#chan` or `#chan=key` (comma/semicolon) |
| `channelKeys` | Optional extra `#chan=key` map merged into auto-join |

UI entry: **Ajouter un compte → IRC** (`AccountsScreen` fields) or protocol chip in new chat.

## Example: Libera.Chat

| Field | Value |
|-------|-------|
| Host | `irc.libera.chat` |
| Port | `6697` |
| TLS | on |
| Nick | your nick |
| NickServ / SASL | optional account password |

Without `secureTrustManagerFactory(system roots)`, Kitteh TLS fails on this host (and most public networks).

## Conversations

IDs are `${accountId}_${remote}` where `remote` is a channel (`#…`, `&…`, …) or nick. Starting a chat with `asGroup` forces channel join (prefix `#` if missing). Leaving a channel conversation sends `PART`.

## Tor

When `ProxyConfig.torRequired` is true:

1. `NetworkGuard.assertNetworkAllowed()` before connect.
2. `SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)`.
3. Kitteh `proxyHost` / `proxyPort` / `ProxyType.SOCKS_5`.

## Capabilities

`directMessages`, `groupChats`, `contacts`, `backupExport`, `messageReply`, `localChatListControls`.  
When connected: `messageHistory` if `draft/chathistory` negotiated; `reactions` if `message-tags` negotiated.  
No media, typing, receipts, E2EE, or in-app registration.

## Limits

- No DCC / CTCP file transfer (blocked).
- Topic/WHOIS/modes are slash-driven (no separate chrome widgets).
- Multi-account: one Kitteh `Client` per `accountId` in `IrcProtocol`.
