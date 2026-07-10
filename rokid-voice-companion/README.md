# Rokid Voice Companion

Phone-side ASR companion POC for Rokid TG.

## Scope

This app moves only the Dictate / voice-to-text module out of the glasses app. Telegram UI, TDLib, chat history, and final send stay in `../rokid-telegram-native` on the glasses.

## CXR protocol

Glasses -> phone:

- `tg.dictate.start` Caps fields: `[sessionId, chatId, lang]`
- `tg.dictate.cancel` Caps fields: `[sessionId]`

Phone -> glasses:

- `tg.asr` Caps fields: `[sessionId, event, payload]`
- `event`: `ready`, `partial`, `final`, `error`, `end`

## Current POC behavior

- Phone app must be launched manually.
- It installs a `CxrApi.setCustomCmdListener` listener.
- On `tg.dictate.start`, it starts Android `SpeechRecognizer` using the phone microphone.
- It streams `ready` / `partial` / `final` back to the glasses via `CxrApi.sendCustomCmd("tg.asr", caps)`.

## Build

```bash
./gradlew :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Verification target

1. Install `rokid-telegram-native` on the glasses.
2. Install this companion APK on the paired Android phone.
3. Pair/connect phone and Rokid glasses through CXR.
4. Launch this companion app and grant microphone / Bluetooth / location permissions.
5. On glasses: Reply -> Dictate.
6. Expected:
   - phone logs `onCustomCmd name=tg.dictate.start`
   - phone sends `tg.asr ready/partial/final`
   - glasses ReplyPanel shows interim/final text
   - final opens normal confirm-send UI

## Not yet production-ready

- No foreground service yet; Activity must stay alive for POC.
- No glasses-mic audio stream yet; first POC uses phone mic.
- No phone device e2e has been verified in this repo session yet.
