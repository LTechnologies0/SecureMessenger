# IRC protocol (`:protocol:irc`)

SecureMessenger treats IRC as a first-class `MessengerProtocol` (`ProtocolId.IRC`), backed by [Kitteh IRC Client Library](https://kitteh.org/irc-client-library/) (KICL) 9.x over Netty.

## Features

| Area | Support |
|------|---------|
| Transport | TLS (`SECURE`, default port 6697) via Android/JVM system `TrustManagerFactory`, or cleartext (`INSECURE`, e.g. 6667). Kitteh’s built-in trust defaults are **not** used — they reject Libera and other Let’s Encrypt networks. |
| Tor | SOCKS5 via `Client.Builder.proxy()` + `ProxyType.SOCKS_5` when `ProxyConfig.torRequired` |
| Auth | Optional server password; NickServ; SASL PLAIN (`saslPassword` / `sasl=true`) |
| Channels | `JOIN` / `PART` / auto-join list; PRIVMSG; NOTICE / TOPIC / JOIN / PART / KICK as `MessageKind.SYSTEM` |
| DMs | PRIVMSG to nick |
| Contacts | Snapshot from channel NAMES (`refreshContacts`) |
| Media / E2EE / registration | Not supported (DCC out of scope) |

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
| `channels` | Comma/space/semicolon-separated auto-join list (`#chan` or bare name) |

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

IDs are `${accountId}_${remote}` where `remote` is a channel (`#…`, `&…`, …) or nick. Starting a chat with `asGroup` forces channel join (prefix `#` if missing).

## Tor

When `ProxyConfig.torRequired` is true:

1. `NetworkGuard.assertNetworkAllowed()` before connect.
2. `SocksEndpointResolver.resolveReachableHost(proxy.host, proxy.port)`.
3. Kitteh `proxyHost` / `proxyPort` / `ProxyType.SOCKS_5`.

## Capabilities

`directMessages`, `groupChats`, `contacts`, `backupExport`. No media, typing, receipts, E2EE, history backfill, or in-app registration.

## Limits

- No DCC / CTCP file transfer.
- No bouncer-specific playback (ZNC playback would appear as normal PRIVMSG if the bouncer sends it).
- Multi-account: one Kitteh `Client` per `accountId` in `IrcProtocol`.
