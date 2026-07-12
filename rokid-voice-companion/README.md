# Rokid Voice Companion

`rokid-voice-companion/` is the phone-side ASR companion for **Rokid TG**.

It intentionally moves **only Dictate / speech-to-text** off the glasses. Telegram UI, TDLib, chat history, media, notifications, and final send confirmation stay in `../rokid-telegram-native/` on the Rokid glasses.

## Why this exists

Rokid glasses can run the Telegram client locally, but dictation is more reliable when the paired Android phone handles microphone capture and Android speech recognition, then streams the transcript back over Rokid CXR.

## Current behavior

- Runs as an Android phone app + foreground service.
- Opens Hi Rokid authorization once and stores the returned token locally.
- Binds to Hi Rokid / global AI app CXR-L media service (`com.rokid.sprite.global.aiapp`).
- Configures a `CUSTOMAPP` CXR session for the glasses app package `com.wickedapp.rokidtg`.
- Receives `tg.dictate.start` / `tg.dictate.cancel` commands.
- Uses Android `SpeechRecognizer` with default language `zh-TW`.
- Sends `tg.asr` `ready`, `partial`, `final`, `error`, and `end` events back to the glasses.
- Can move its Activity to the background after the foreground service starts.

## CXR protocol

Glasses → phone:

| Message | Caps fields |
|---|---|
| `tg.dictate.start` | `sessionId`, `chatId`, `lang` |
| `tg.dictate.cancel` | `sessionId` |

Phone → glasses:

| Message | Caps fields |
|---|---|
| `tg.asr` | `sessionId`, `event`, `payload` |

`event` is one of:

- `ready`
- `partial`
- `final`
- `error`
- `end`

## Prerequisites

- Android phone paired with Rokid glasses through Hi Rokid / CXR.
- Hi Rokid / global AI app installed (`com.rokid.sprite.global.aiapp`).
- Android speech recognition provider available. Google Speech Recognition and Synthesis usually works best.
- Permissions granted:
  - Microphone / `RECORD_AUDIO`
  - Bluetooth / nearby devices
  - Location where required by Android Bluetooth APIs
  - Notifications for the foreground-service notification

## Build

```bash
./gradlew :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install and authorize

```bash
adb -s <phone-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <phone-serial> shell am start -n com.wickedapp.rokidvoicecompanion/.MainActivity
```

On first launch:

1. Grant permissions.
2. Tap/accept Hi Rokid authorization.
3. Wait for `Background service started`.
4. The Activity can move to the background; keep the foreground-service notification alive.

## Verify with logs

```bash
adb -s <phone-serial> logcat -c
adb -s <phone-serial> logcat -s RokidVoiceCompanion AndroidRuntime
```

Expected healthy log snippets:

```text
configCXRSession CUSTOMAPP com.wickedapp.rokidtg -> true
bindGlobalHiRokidService -> true
CXR-L service connected=true
Hi Rokid glasses BT connected=true
onCustomCmdResult key=tg.dictate.start
send tg.asr event=ready result=0
send tg.asr event=partial result=0
send tg.asr event=final result=0
```

On glasses, the native app should log/receive the matching `tg.asr` events and show the transcript in the Reply panel.

## Troubleshooting

| Symptom | Check |
|---|---|
| `Needs Hi Rokid authorization` | Open the companion Activity and authorize with Hi Rokid again. |
| No `onCustomCmdResult key=tg.dictate.start` on phone | Verify Hi Rokid is installed/running, glasses are paired, and CXR-L binding succeeded. |
| `SpeechRecognizer unavailable` | Install/enable a public speech recognition provider such as Google Speech Recognition and Synthesis. |
| `speech_error_7` / no speech | Treat as unclear/no speech; retry near the phone mic. |
| Glasses send returns `rc=0` but phone sees nothing | This usually means CXR routing/listener mismatch. Inspect Hi Rokid CXR-L binding before changing Telegram UI code. |

## Security notes

- The Hi Rokid authorization token is stored in this app's private shared preferences.
- Do not commit logs containing private transcripts.
- The companion does not own Telegram credentials or Telegram session data; those stay in the glasses app.
