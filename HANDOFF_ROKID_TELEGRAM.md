# [SUPERSEDED] Handoff — Rokid Telegram Phone Companion

> **STATUS — SUPERSEDED 2026-06-30.** The two direction-defining instructions in this file ("Rokid Telegram = phone-side companion APK" and "Do not continue the glasses-only Telegram WebView as product direction") were **both reversed** during a fresh brainstorming session. The current product is a **bare-metal native Android APK on the glasses** (`rokid-telegram-native/`) + a separate Sprite Ink voice helper (`voice-helper/`), with the phone providing internet via standard Bluetooth tethering (BT-PAN) — not via a custom companion app.
>
> Authoritative documents:
> - **Design spec:** `docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md` (locks the decisions, including why this handoff's direction was rejected — see decisions D1–D9 and the Non-goals section)
> - **Implementation plan:** `docs/superpowers/plans/2026-06-30-rokid-glasses-telegram-client.md`
> - **PR:** https://github.com/wickedapp/hermes-glass-bridge/pull/1
>
> The contents below are preserved for historical context only. Do not act on them.

---

**Date:** 2026-06-30 10:54 +08
**Primary objective for next agent:** Continue phone-side Telegram companion APK validation and refinement.

## User Preference

Keep responses concise and high-signal. Do not repeat superseded/incorrect routes unless needed for blockers.

## Current Correct Direction

### Product Split

1. **Rokid Dev Console**
   - Separate product.
   - Native APK for glasses/Station.
   - Connects to Mac/Mac mini Hermes bridge.

2. **Rokid Telegram**
   - Separate product.
   - **Phone-side companion APK**.
   - Phone handles Telegram + internet via 4G/5G/Wi‑Fi.
   - Rokid glasses are display/input surface.

### Do Not Continue

- Do not make Telegram depend on glasses/Station direct internet.
- Do not merge Telegram into Dev Console.
- Do not use Telegram Bot API for personal chat list/messages.
- Do not continue the glasses-only Telegram WebView as product direction; reuse code only.

## Planning Docs

Obsidian planning doc:

```text
/Users/wickedapp/Library/Mobile Documents/iCloud~md~obsidian/Documents/ObsidianVault/wiki/projects/rokid-ai-glasses/telegram-phone-companion-plan.md
```

## Implemented Projects

### Correct Current Project

```text
/Users/wickedapp/Development/hermes-glass-bridge/rokid-telegram-phone-app
```

APK:

```text
/Users/wickedapp/Development/hermes-glass-bridge/rokid-telegram-phone-app/app/build/outputs/apk/debug/app-debug.apk
SHA256: 9fbf19c4f7a2677c98b3b4ca454890fdbedb763fbd79f6b5dc280cce77211e30
Version: v0.1.0-phone-webtg-voice
Package: com.wickedapp.rokidtelegram
```

Build status: `BUILD SUCCESSFUL`.

### Superseded Prototype

```text
/Users/wickedapp/Development/hermes-glass-bridge/rokid-telegram-app
```

Glasses-side WebView prototype. Not final direction because it requires glasses/Station internet.

### Dev Console Related

```text
/Users/wickedapp/Development/hermes-glass-bridge/android-app
/Users/wickedapp/Development/hermes-glass-bridge/rokid-aiui-mic-test
```

Dev Console is separate from Telegram.

## Current ADB State

Latest check:

```text
adb devices -l
1906092624100227 device product:glasses model:RG_glasses device:glasses
```

Only Rokid glasses are visible. Phone is not visible yet. Next validation requires Android phone with ADB enabled.

## Phone Companion MVP Behavior

- WebView opens `https://web.telegram.org/k/`.
- Cookies/localStorage enabled.
- Keyboard input passes through to Telegram Web.
- Enter is left to Telegram Web.
- Voice shortcut: `Ctrl/Alt/Meta + Enter`, Search, Assist, or Voice Assist key.
- ASR final transcript is injected into Telegram composer.
- Manual send by default.

Main file:

```text
rokid-telegram-phone-app/app/src/main/java/com/wickedapp/rokidtelegram/MainActivity.java
```

## Build Commands

```bash
cd /Users/wickedapp/Development/hermes-glass-bridge/rokid-telegram-phone-app
./gradlew :app:assembleDebug --no-daemon
```

JDK:

```text
rokid-telegram-phone-app/gradle.properties
org.gradle.java.home=/opt/homebrew/opt/openjdk@17
```

Android SDK:

```text
rokid-telegram-phone-app/local.properties
sdk.dir=/Users/wickedapp/Library/Android/sdk
```

## Next Agent Steps

1. Verify Android phone ADB target:

```bash
adb devices -l
```

Target should not be `model:RG_glasses`.

2. Install phone APK:

```bash
cd /Users/wickedapp/Development/hermes-glass-bridge/rokid-telegram-phone-app
adb -s <PHONE_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <PHONE_SERIAL> shell am start -n com.wickedapp.rokidtelegram/.MainActivity
```

3. Validate:

- Telegram Web loads over phone mobile data/Wi‑Fi.
- Login/session persists.
- Keyboard input works.
- Voice dictation starts with shortcut.
- Transcript inserts into Telegram composer.

4. If composer injection fails:

- Inspect Telegram Web DOM via remote WebView debugging.
- Update selector fallback in `injectTelegramText()`.

5. Update Obsidian plan and this file after validation.

## Suggested Skills for Next Agent

- `obsidian`
- `web-debugging`
- `playwright-cli` only if desktop web investigation is needed
- `telegram-voice-troubleshooting` only for Telegram Bot/Hermes gateway issues, not main client implementation
