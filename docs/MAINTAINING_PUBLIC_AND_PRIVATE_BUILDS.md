# Maintaining public and personal builds

This repository is public. Keep it safe for external users by separating **source code** from **local/private configuration**.

## Recommended strategy

Use **one public repository** as the source of truth, plus private local configuration that is not committed.

Do **not** maintain a permanently divergent private fork unless there is a strong reason. Divergent forks create repeated merge work and make bug fixes harder to share.

## What belongs in the public repo

- Rokid TG source code.
- Phone companion source code.
- Build scripts that work for any user.
- Templates such as `local.properties.example`.
- Generic docs and troubleshooting.
- Optional release automation.

## What must stay private/local

- Telegram `api_id` / `api_hash` in `local.properties`.
- TDLib session files / `td.binlog`.
- Hi Rokid authorization tokens.
- Private hostnames, LAN IPs, Tailscale domains, ngrok URLs, Cloudflare tunnel secrets.
- Generated APKs that embed private endpoints.
- Logs/screenshots containing private chats.

## For personal/internal configuration

Prefer these patterns:

| Need | Public-safe pattern |
|---|---|
| Telegram credentials | `rokid-telegram-native/local.properties` (gitignored) |
| One-off local endpoints | `.env` / `.env.local` (gitignored) |
| Generated remote config | `config.local.json` / `*.local.*` (gitignored) |
| Device-specific serial | `SERIAL=<serial> ./script.sh`, not hardcoded default |
| Private Dev Console bridge URL | runtime config or generated local AIX, not committed asset |

## Branch model

Recommended:

- `rokid-tg-client` / default branch: public, clean, generic.
- Local uncommitted config: preferred for personal endpoints/secrets.
- Optional private branch: only for experimental code that cannot be public yet.

If you create a private branch, keep it short-lived and regularly rebase onto the public branch. Do not put general fixes only on the private branch.

## Current note about Hermes Glass Terminal

`android-app/` is a separate Hermes Glass Terminal / Dev Console product, not required for Rokid TG.

Private changes such as:

```text
ws://<your-private-host>:8765/ws/glass
```

must not be committed into public assets. If that product needs public sharing later, add a runtime configuration file or a generator script that produces a local AIX from a template.
