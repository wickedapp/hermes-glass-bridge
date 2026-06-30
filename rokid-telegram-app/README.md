# [SUPERSEDED] rokid-telegram-app

> **STATUS — SUPERSEDED 2026-06-30.** This was an early glasses-side WebView prototype that wrapped `web.telegram.org/k/`. It assumed the glasses had their own internet, which turned out to need significant rework. Both this approach AND the subsequent phone-side WebView companion were rejected during the fresh brainstorming session that produced the current product.
>
> **Use `../rokid-telegram-native/` instead** — a real native Android client with TDLib running on the glasses, internet via vivo Bluetooth tethering, and on-device ASR via a co-installed Sprite Ink helper (`../voice-helper/`).
>
> Authoritative docs:
> - Design spec: `../docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md`
> - PR: https://github.com/wickedapp/hermes-glass-bridge/pull/1
>
> Kept here for reference only. Do not extend.
