# Rokid Telegram Phone Companion

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
