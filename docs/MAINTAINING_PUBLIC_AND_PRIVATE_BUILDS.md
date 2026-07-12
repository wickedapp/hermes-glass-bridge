# Maintaining public and personal builds

This repository is public. Keep it safe for external users by separating **source code** from **local/private configuration**.

## Recommended strategy

Use **one public repository** as the source of truth, plus private local configuration that is not committed.

Do **not** maintain a permanently divergent private fork unless there is a strong reason. Divergent forks create repeated merge work and make bug fixes harder to share.

## What belongs in the public repo

- Rokid Telegram source code.
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
- Generated APKs that embed private credentials or endpoints.
- Logs/screenshots containing private chats.

## For personal/internal configuration

Prefer these patterns:

| Need | Public-safe pattern |
|---|---|
| Telegram credentials | `rokid-telegram-native/local.properties` (gitignored) |
| One-off local endpoints | `.env` / `.env.local` (gitignored) |
| Generated remote config | `config.local.json` / `*.local.*` (gitignored) |
| Device-specific serial | `SERIAL=<serial> ./script.sh`, not hardcoded default |

## Branch model

Recommended:

- `main`: public, clean, generic.
- Local uncommitted config: preferred for personal endpoints/secrets.
- Optional private branch: only for experimental code that cannot be public yet.

If you create a private branch, keep it short-lived and regularly rebase onto `main`. Do not put general fixes only on the private branch.
