# Rokid Telegram Native

`rokid-telegram-native/` is the main **Rokid TG** glasses app: a native Android Telegram client for Rokid RG glasses.

- Package: `com.wickedapp.rokidtg`
- Runtime: sideloaded Android APK on the glasses
- Telegram stack: TDLib JNI (`app/libs/tdlib.aar`)
- UI style: black/green compact terminal/BBS HUD for 480-wide Rokid displays
- Input: Rokid touchpad/DPAD/Enter/Back plus optional Bluetooth keyboard
- Dictation: `DictationProvider` seam with phone CXR companion as preferred path and Sprite Ink helper as fallback/debug

## Features

- Telegram authorization through TDLib.
- Chat list and per-chat message history.
- BBS/terminal-style compact chat/message rows.
- Local chat pin/mute state for glasses HUD controls.
- Text, photo, video, voice-note, sticker/emoji, service-message summaries.
- Media viewer / voice playback using Android media stack.
- Reply panel with text, voice-note, and Dictate flows.
- Off-chat banner notification channel.
- Traditional Chinese default dictation language (`zh-TW`).

## Architecture

```text
MainActivity
├─ AuthFragment
├─ ChatListFragment
├─ ChatFragment
├─ FullMessageFragment / MediaViewerFragment
└─ TelegramService foreground service
   ├─ TdLibClient
   ├─ ChatRepo / MessageRepo
   ├─ NotificationCenter
   └─ NetworkMonitor

Reply / voice layer
├─ ReplyPanel
├─ AudioCapturer → VoiceNoteEncoder → TDLib InputMessageVoiceNote
└─ DictationProvider
   ├─ PhoneCxrDictationProvider      preferred: phone CXR-L companion ASR
   └─ VoiceHelperBridge              fallback/debug: Sprite Ink ASR helper
```

Only ASR/dictation is delegated to the phone companion. Telegram UI, TDLib, history, media, and final send confirmation remain on the glasses.

## Prerequisites

- macOS host with Android SDK / `adb`.
- JDK 17.
- Rokid glasses connected by ADB.
- Telegram `api_id` and `api_hash` from <https://my.telegram.org/apps>.
- Optional: `tdl` CLI for Mac-side session seeding.
- Optional but recommended for Dictate: `../rokid-voice-companion/` installed on the paired Android phone.

## Configure Telegram credentials

Create `local.properties` in this directory:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
tg.apiId=123456
tg.apiHash=0123456789abcdef0123456789abcdef
```

`local.properties` is gitignored. Do not commit your Telegram credentials.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH
./gradlew :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install and launch on glasses

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.wickedapp.rokidtg/.MainActivity
```

If multiple devices are connected:

```bash
adb -s <rokid-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <rokid-serial> shell am start -W -n com.wickedapp.rokidtg/.MainActivity
```

## Telegram login

### Option A: login on glasses

Launch the app and follow TDLib authorization on the HUD.

### Option B: seed from Mac

From repo root:

```bash
./scripts/seed-session.sh +<your-phone-number>
```

This avoids typing the entire login flow on the glasses. The script expects `tdl` on `PATH` and pushes the session into the app data directory.

## Dictation setup

### Preferred: phone CXR companion

Install and authorize `../rokid-voice-companion/` on the Android phone paired to the glasses. Then use **Reply → Dictate** on the glasses.

Protocol:

```text
glasses -> phone: tg.dictate.start(sessionId, chatId, lang)
glasses -> phone: tg.dictate.cancel(sessionId)
phone   -> glasses: tg.asr(sessionId, ready|partial|final|error|end, payload)
```

### Fallback/debug: Sprite Ink helper

From repo root:

```bash
./scripts/push-helper.sh
```

This pushes `../voice-helper/` to `/sdcard/aix/voice-helper`. Use this only as a fallback/debug path; the production direction is phone CXR ASR.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Current tests cover repo/data logic, TDLib wrapper behavior, media helpers, voice note encoding, voice helper bridge behavior, and input routing.

## Smoke test

From repo root:

```bash
SERIAL=<rokid-serial> ./scripts/glasses-smoke.sh
```

The script builds, installs, launches the app, fires safe navigation events, checks the foreground service and notification channel, then runs unit tests.

## Useful debug commands

```bash
adb logcat -c
adb shell am start -W -n com.wickedapp.rokidtg/.MainActivity
adb logcat -d -s TG PhoneDictation VoiceBridge AndroidRuntime
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
adb shell dumpsys activity services | grep TelegramService
```

## Source layout

```text
app/src/main/kotlin/com/wickedapp/rokidtg/
├── MainActivity.kt
├── TelegramApp.kt
├── data/                 ChatRepo, MessageRepo, row models, local chat prefs
├── media/                media cache + playback pool
├── service/              TelegramService, TdLibClient, network monitor, notifications
├── ui/                   auth/list/chat/media/reply/full-message UI
├── ui/input/             Rokid key/touchpad/broadcast routing
└── voice/                dictation providers, ASR bridge, audio capture, OGG/Opus voice notes
```

## Notes for contributors

- Keep Telegram data and TDLib session files local.
- Keep all user-facing text localized through Android resources.
- Do not display raw TDLib class names in message rows.
- Keep focus highlights obvious: filled green background + strong border, not outline-only.
- Verify UI/focus work on real glasses with ADB, logs, screenshots, or mirror evidence.
