# Changelog

## 1.0.0-alpha

- Greenfield MVP: Tor-only multi-protocol secure messenger
- Modules: `:core:model`, `:core:proxy`, `:core:network`, `:core:security`, `:data`, `:protocol:api`, `:protocol:xmpp`, `:protocol:matrix`, `:protocol:telegram`, `:protocol:discord`, `:protocol:signal`, `:app`
- Protocols: Matrix (raw Client-Server API + Trixnity crypto), XMPP (Smack), Telegram (TDLib), Discord, Signal
- Tor-only network enforcement with killswitch (`NetworkGuard`)
- Matrix: `.well-known/matrix/client` delegated homeserver discovery
- Matrix registration: `m.login.dummy`, `m.login.registration_token`, and Tor-routed WebView fallback for UIA stages (captcha, email, terms)
- XMPP registration: XEP-0077 in-band registration via Smack `AccountManager`, dynamic extra-field rendering
- Per-account SOCKS5 stream isolation for XMPP (Tor circuit isolation)
- Compose UI: accounts screen with login/register toggle, conversation list, chat screen
- Encrypted credential storage (`EncryptedCredentialStore`)
