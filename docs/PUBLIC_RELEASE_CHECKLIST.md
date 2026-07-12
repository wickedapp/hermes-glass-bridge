# Public release checklist

Use this before publishing a tag, release, or APK for Rokid TG.

## Source release checklist

- [ ] `git status` is clean.
- [ ] No private hostnames, LAN IPs, Tailscale domains, ngrok URLs, auth tokens, TDLib session files, or chat logs.
- [ ] `grep -R "taila\|ngrok\|trycloudflare\|192\.168\.\|10\.0\.\|td.binlog" ...` reviewed.
- [ ] `./scripts/rokid-tg-doctor.sh` passes or reports only expected local-device warnings.
- [ ] `rokid-telegram-native` builds and tests pass.
- [ ] `rokid-voice-companion` builds.
- [ ] README and `QUICKSTART.md` match the current setup flow.

## APK release policy

Do **not** upload a public Rokid TG APK built from a private `local.properties` unless you explicitly accept that the Telegram API credentials are embedded in the APK.

Current safe public distribution mode is:

1. Publish source code.
2. Publish instructions.
3. Have users supply their own Telegram `api_id` / `api_hash` in `local.properties` and build locally.

A true non-developer APK release needs one of these before publishing:

- Runtime Telegram API credential entry screen, so the APK does not embed maintainer credentials; or
- A dedicated public Telegram app id/hash accepted as extractable from APKs; or
- A private/beta distribution model where credential exposure is an accepted risk.

## Personal/internal build policy

Personal builds may use local/private config, but it must stay outside git:

- `local.properties`
- `*.local.*`
- `.env.local`
- generated AIX/APK files with private endpoints

If a private endpoint is needed for Hermes Glass Terminal, generate that AIX locally from a template instead of committing the generated asset.
