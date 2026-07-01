# [SUPERSEDED] Rokid Telegram Phone Companion

> **STATUS — SUPERSEDED 2026-06-30.** The "phone-side WebView companion" direction this prototype embodies was rejected in the fresh brainstorming session that produced the current product. The final architecture is a **bare-metal native Android APK on the glasses** at `../rokid-telegram-native/`, paired with a Sprite Ink voice helper at `../voice-helper/`, with the phone providing internet via standard Bluetooth tethering — no custom phone-side runtime needed.
>
> Authoritative docs:
> - Design spec: `../docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md` (see decisions D1, D2, D5)
> - PR: https://github.com/wickedapp/hermes-glass-bridge/pull/1
>
> Kept here for reference only. Do not extend.

Phone-side Telegram companion app for Rokid glasses.

## Direction

This app runs on the Android phone, not as a glasses-only internet client.

- Phone provides internet via 4G/5G/Wi-Fi
- WebView loads Telegram Web
- Rokid glasses display/control the phone app
- Keyboard input passes through to Telegram Web
- Voice dictation inserts text into Telegram composer

## Not This

- Not part of Rokid Dev Console
- Not Telegram Bot API
- Not glasses/Station direct-internet Telegram WebView as the product direction

## Build

```bash
./gradlew :app:assembleDebug --no-daemon
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Controls

| Input | Behavior |
|---|---|
| Keyboard typing | Telegram Web handles text |
| Enter | Telegram Web handles send/newline |
| Ctrl/Alt/Meta + Enter | Voice dictation |
| Search / voice key | Voice dictation |

## Current Version

```text
v0.1.0-phone-webtg-voice
```
