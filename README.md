# Rokid TG / Hermes Glass Bridge

**Rokid TG** is a Telegram client designed for Rokid smart glasses: a native Android APK runs directly on the glasses, talks to Telegram through TDLib, and presents Telegram in a compact black/green terminal-style HUD that works with Rokid touchpad, DPAD, Enter, Back, Bluetooth keyboard, notifications, media, and voice reply flows.

This repository also contains the earlier **Hermes Glass Terminal** bridge and historical prototypes, but the shareable product in this repo is now **Rokid TG**.

> **Status:** experimental developer build. It is useful for personal sideloading and technical review, but it is not a Play Store product and still depends on Rokid/Android/CXR behavior that can vary by device firmware.

## Start here

- **Public user quick start:** [`QUICKSTART.md`](QUICKSTART.md)
- **Main glasses app:** [`rokid-telegram-native/`](rokid-telegram-native/)
- **Optional phone dictation companion:** [`rokid-voice-companion/`](rokid-voice-companion/)
- **Public/private maintenance policy:** [`docs/MAINTAINING_PUBLIC_AND_PRIVATE_BUILDS.md`](docs/MAINTAINING_PUBLIC_AND_PRIVATE_BUILDS.md)
- **Release checklist:** [`docs/PUBLIC_RELEASE_CHECKLIST.md`](docs/PUBLIC_RELEASE_CHECKLIST.md)

## Why Rokid TG?

| Selling point | What it means |
|---|---|
| **Telegram on glasses, not a phone mirror** | TDLib runs on the glasses APK. Chat list, message history, media preview/playback, reply state, and notifications are first-class glasses UI. |
| **Glasses-first interaction model** | Designed for a 480×640 / 480×400-safe Rokid HUD with high-contrast black + Rokid green, compact rows, explicit focus, and DPAD/touchpad navigation. |
| **Terminal / BBS density** | The UI favors information density: `GROUP / TIME / LAST MESSAGE / NEW` style lists and compact message rows instead of phone-style chat bubbles. |
| **Voice replies without typing on glasses** | Dictation is abstracted behind a provider seam. Production path uses a phone CXR companion for microphone + ASR; Sprite Ink helper remains a fallback/debug path. |
| **Media-aware Telegram renderer** | Text, photos, videos, voice notes, stickers/animated emoji/service messages are summarized for the HUD instead of leaking raw TDLib class names. |
| **Local-first, sideloadable** | No custom backend is required for Telegram messages. Secrets are local (`local.properties`, TDLib session files, optional phone authorization token). |

## Current architecture

```text
┌─────────────────────────────────────────────────────────────────────┐
│ Rokid RG glasses                                                     │
│                                                                     │
│  rokid-telegram-native/ Android APK                                  │
│  ├─ MainActivity + fragments: auth, chat list, chat, media viewer    │
│  ├─ TDLib JNI (local tdlib.aar)                                      │
│  ├─ TelegramService foreground service                              │
│  ├─ ChatRepo / MessageRepo / NotificationCenter                     │
│  ├─ ReplyPanel + InputRouter for Rokid keys / BT keyboard            │
│  ├─ MediaCache + ExoPlayer voice/video playback                      │
│  └─ DictationProvider seam                                           │
│      ├─ PhoneCxrDictationProvider  ← preferred production path       │
│      └─ VoiceHelperBridge         ← Sprite helper fallback/debug     │
└─────────────────────────────────────────────────────────────────────┘
            │
            │ Telegram MTProto through TDLib
            ▼
      Telegram cloud

            │ CXR custom commands for Dictate only
            ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Android phone                                                        │
│                                                                     │
│  rokid-voice-companion/                                              │
│  ├─ one-time Hi Rokid authorization                                  │
│  ├─ foreground service bound to Hi Rokid CXR-L media service         │
│  ├─ Android SpeechRecognizer / phone microphone                      │
│  └─ protocol: tg.dictate.start/cancel → tg.asr ready/partial/final   │
└─────────────────────────────────────────────────────────────────────┘

Fallback/debug:
  voice-helper/ Sprite Ink mini-app on glasses
  └─ Rokid SpeechRecognition → localhost WebSocket → native APK
```

### Important design decision

Only the **dictation / ASR module** moves to the phone companion. Telegram UI, TDLib, message history, notification handling, media handling, and final send confirmation stay on the glasses.

## Repository layout

| Path | Purpose | Status |
|---|---|---|
| `QUICKSTART.md` | Public user quick start for building/installing Rokid TG. | Start here |
| `rokid-telegram-native/` | Main Rokid TG native glasses APK (`com.wickedapp.rokidtg`). | Active |
| `rokid-voice-companion/` | Phone-side CXR-L ASR companion for Dictate. | Active / experimental |
| `voice-helper/` | Sprite Ink ASR helper for fallback/debug dictation. | Fallback |
| `scripts/rokid-tg-doctor.sh` | Checks host setup and can generate local Telegram config. | Active |
| `scripts/glasses-smoke.sh` | Build/install/launch smoke test for the glasses APK. | Active |
| `scripts/seed-session.sh` | Optional TDLib login seeding helper. | Active / power-user |
| `scripts/push-helper.sh` | Pushes `voice-helper/` to the glasses. | Fallback path |
| `docs/MAINTAINING_PUBLIC_AND_PRIVATE_BUILDS.md` | How to keep public source clean while using private local config. | Maintainers |
| `docs/PUBLIC_RELEASE_CHECKLIST.md` | Checklist for safe source/APK releases. | Maintainers |
| `docs/ROKID_DESIGN_GUIDELINES.md` | Rokid visual and interaction constraints. | Reference |
| `docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md` | Original product spec and decision log. | Reference |
| `app/`, `web/`, `android-app/` | Earlier Hermes Glass Terminal / Dev Console bridge. | Separate product / not needed for Rokid TG |
| `rokid-telegram-app/`, `rokid-telegram-phone-app/`, `rokid-telegram-mockup/` | Earlier prototypes. | Superseded/reference |

## Prerequisites

### Host Mac

- macOS with Android SDK / platform tools (`adb`) installed.
- JDK 17. The scripts assume Homebrew OpenJDK 17 at:
  `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- GitHub CLI (`gh`) only if you maintain/publish the repo.
- Optional: [`tdl`](https://github.com/iyear/tdl) if you want to seed TDLib login from the Mac instead of typing on the glasses.

### Rokid glasses

- Rokid RG glasses with developer/ADB access enabled.
- USB connection for sideloading, or another ADB route.
- Network connectivity on the glasses. In the original setup this was phone Bluetooth PAN/tethering, but any route that lets TDLib reach Telegram should work.

### Telegram developer credentials

Create a Telegram app at <https://my.telegram.org/apps> and note:

- `api_id`
- `api_hash`

These are required by TDLib and must **not** be committed.

### Optional phone companion

For the preferred Dictate flow:

- Android phone paired with the Rokid glasses through Hi Rokid / CXR.
- Hi Rokid / global AI app installed (`com.rokid.sprite.global.aiapp`).
- Google/Android speech recognition provider available if you want Android `SpeechRecognizer` to work reliably.
- Microphone, Bluetooth, nearby-device/location, and notification permissions granted to the companion app.

## Installation and configuration

### 1. Clone the repo

```bash
git clone https://github.com/wickedapp/hermes-glass-bridge.git
cd hermes-glass-bridge
```

### 2. Configure Telegram credentials

Run the doctor first so missing tools are obvious:

```bash
./scripts/rokid-tg-doctor.sh
```

Create `rokid-telegram-native/local.properties` from the template:

```bash
cp rokid-telegram-native/local.properties.example rokid-telegram-native/local.properties
```

Then edit it:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
tg.apiId=123456
tg.apiHash=0123456789abcdef0123456789abcdef
```

Or let the doctor create it from environment variables:

```bash
TG_API_ID=123456 TG_API_HASH=0123456789abcdef0123456789abcdef \
  ./scripts/rokid-tg-doctor.sh --write-local-properties
```

`local.properties` is gitignored.

### 3. Build the glasses APK

```bash
cd rokid-telegram-native
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH
./gradlew :app:assembleDebug
```

APK output:

```text
rokid-telegram-native/app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install on the glasses

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.wickedapp.rokidtg/.MainActivity
```

If more than one device is connected, pass `-s <serial>` or set `SERIAL=<serial>` for repo scripts.

### 5. Log in to Telegram

You have two options:

#### Option A — normal TDLib auth on glasses

Launch the app and complete the phone/code flow on the Rokid UI. This works but is not pleasant on a small HUD.

#### Option B — seed the TDLib session from Mac

```bash
cd ..
./scripts/seed-session.sh +<your-phone-number>
```

This script expects `tdl` on `PATH`, completes login on the Mac, then pushes the resulting TDLib session/binlog into the app data directory on the glasses.

### 6. Install the phone dictation companion (recommended for Dictate)

From repo root:

```bash
cd rokid-voice-companion
./gradlew :app:assembleDebug
adb -s <phone-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <phone-serial> shell am start -n com.wickedapp.rokidvoicecompanion/.MainActivity
```

On first launch:

1. Grant requested Android permissions.
2. Complete Hi Rokid authorization when prompted.
3. Let the app start its foreground service and move to the background.
4. Keep the notification/service running while using Dictate from the glasses.

Expected protocol when you press **Reply → Dictate** on the glasses:

```text
glasses → phone: tg.dictate.start(sessionId, chatId, lang)
phone   → glasses: tg.asr ready
phone   → glasses: tg.asr partial "..."
phone   → glasses: tg.asr final "..."
```

### 7. Optional Sprite helper fallback

If you are testing the older glasses-side ASR helper:

```bash
./scripts/push-helper.sh
```

This pushes `voice-helper/` to `/sdcard/aix/voice-helper` and asks the Sprite launcher to reload. Treat this path as fallback/debug; the phone companion is the intended production dictation path.

## Verification

### Unit tests

```bash
cd rokid-telegram-native
./gradlew :app:testDebugUnitTest
```

### Glasses smoke test

```bash
cd ..
SERIAL=<rokid-serial> ./scripts/glasses-smoke.sh
```

The smoke script builds the APK, installs it, launches `MainActivity`, performs safe key events, checks service/notification state, and runs JVM unit tests.

### Useful manual checks

```bash
# Foreground activity
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'

# Rokid TG logs
adb logcat -d -s TG PhoneDictation RokidVoiceCompanion AndroidRuntime

# Check package installed
adb shell pm list packages | grep wickedapp
```

## Runtime controls

| Input | Expected behavior |
|---|---|
| DPAD / Rokid touchpad Up/Down | Move focus through chat rows/windows or message rows depending on mode. |
| Enter / single click | Open selected chat/message/action, or confirm selected reply action. |
| Back | Leave active window, return to chat list, or exit according to the current layer. |
| Bluetooth keyboard text | Text reply path where supported. |
| Reply → Dictate | Starts phone CXR ASR companion, streams transcript back, then shows confirm-send UI. |
| Reply → Voice | Records an OGG/Opus Telegram voice note and sends through TDLib. |

## Security and privacy notes

- Do not commit `local.properties`, TDLib session files, phone authorization tokens, generated APKs with private credentials, or logs containing chats.
- TDLib stores Telegram session data inside the app data directory on the glasses.
- The companion phone app stores the Hi Rokid authorization token in Android shared preferences so its foreground service can restart.
- This project is for personal/developer sideloading. Audit the code and dependencies before sharing builds with other people.

## Development notes

- Main code: `rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/`.
- UI constraints: black background, Rokid green highlights, large enough focus indication, compact terminal/BBS rows, no tiny phone UI controls.
- Keep Dictate behind `DictationProvider`; do not move Telegram/TDLib to the phone unless the architecture intentionally changes.
- Prefer real-device verification: build → install → launch → logcat/dumpsys/screenshot proof.

## Legacy Hermes Glass Terminal

The original Hermes Glass Terminal remains in this repo:

```bash
./scripts/run.sh
# opens FastAPI/WebSocket bridge at http://127.0.0.1:8765/
```

Its Android WebView wrapper lives in `android-app/`. It is separate from Rokid TG.

## License

MIT. See [`LICENSE`](LICENSE).
