# Security Policy

## Supported Versions

| Version     | Supported |
| ----------- | --------- |
| 1.0.x-alpha | Yes       |

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

1. Use **GitHub Private Vulnerability Reporting** (Security → Advisories → Report a vulnerability) on this repository.
2. Or email the maintainers with:
   - Affected version(s) and protocol (Matrix / XMPP / Telegram / Discord / Signal)
   - Steps to reproduce
   - Impact assessment (traffic deanonymization, credential exposure, message plaintext leak, etc.)

We aim to acknowledge reports within **72 hours** and provide a fix or mitigation timeline within **14 days** for confirmed issues.

## Scope

In scope:

- SecureMessenger application code in this repository
- Gradle build scripts and GitHub Actions workflows
- The Tor-only network enforcement layer (`core/network` `NetworkGuard`, killswitch)
- Proxy/SOCKS routing (`core/proxy`) for every protocol, including WebView fallback flows
- Credential storage (`EncryptedCredentialStore`) and account registration flows
- Protocol adapters (`protocol/*`) as they relate to this app's use of upstream SDKs

Out of scope:

- Vulnerabilities in upstream SDKs themselves (Smack, Trixnity, TDLib, Signal, Discord libs) — report upstream
- Third-party Android OS bugs
- Compromised devices (root malware, accessibility malware)
- Weak user-chosen passwords
- Attacks requiring physical device access with an unlocked screen
- Deanonymization attacks against the Tor network itself (report to the Tor Project)

## Security Design

- **Tor-only by default**: all protocol traffic (Matrix, XMPP, Telegram, Discord, Signal, and any in-app WebView) is routed through a SOCKS5 proxy to Tor. A killswitch blocks all connections if the proxy is unavailable — see `core/network`.
- **No direct DNS**: hostname resolution for the Matrix `.well-known` discovery and XMPP SRV lookups happens through the proxy, not the device's default resolver.
- **Encrypted credential storage**: account secrets (passwords, tokens, session data) are stored via `EncryptedCredentialStore` (AndroidX Security Crypto / Keystore-backed).
- **No hardcoded secrets**: signing keys, SDK/NDK paths, and Telegram API credentials are never committed (`keystore.properties`, `local.properties` are gitignored; see `local.properties.example`).
- **Release hardening**: R8 minification, resource shrinking, privacy-safe logging (Timber verbose/debug/info stripped in release — see `gradle/privacy-logging.pro`).
- **CI signing**: release keystores are provided via GitHub Actions encrypted secrets only (`RELEASE_KEYSTORE_*`).

## Recommended Repository Settings

Enable these free GitHub security features under **Settings → Code security**:

- Dependabot alerts
- Dependabot security updates
- Secret scanning + push protection
- Code scanning (CodeQL workflow included in `.github/workflows/ci.yml`)
- Private vulnerability reporting

## CI Secrets (maintainers only)

| Secret | Description |
|--------|-------------|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded `.keystore` or `.jks` file |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (default: `securemessenger`) |
| `RELEASE_KEY_PASSWORD` | Key password (optional if same as keystore) |

Generate a keystore locally with `scripts/generate-release-keystore.sh` — never commit the output.

Telegram API credentials (`telegram.api.id` / `telegram.api.hash`) are supplied per-builder via `local.properties` (gitignored) or the `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` environment variables — never committed to source. Each builder should obtain their own free credentials from [my.telegram.org](https://my.telegram.org).
