# Rokid Telegram

**Rokid Telegram** is a native Telegram client for Rokid smart glasses. It runs directly on the glasses, uses TDLib for Telegram connectivity, and presents Telegram in a compact black/green HUD designed for Rokid touchpad, DPAD, Enter, Back, Bluetooth keyboard, media playback, notifications, and voice reply flows.

> **Status:** experimental developer build. It is suitable for technical users and public source review. It is not yet a consumer one-click APK because Telegram `api_id` / `api_hash` are currently supplied at build time.

## Start here

- **Public quick start:** [`QUICKSTART.md`](QUICKSTART.md)
- **Main glasses app:** [`rokid-telegram-native/`](rokid-telegram-native/)
- **Optional phone dictation companion:** [`rokid-voice-companion/`](rokid-voice-companion/)
- **Public/private maintenance policy:** [`docs/MAINTAINING_PUBLIC_AND_PRIVATE_BUILDS.md`](docs/MAINTAINING_PUBLIC_AND_PRIVATE_BUILDS.md)
- **Release checklist:** [`docs/PUBLIC_RELEASE_CHECKLIST.md`](docs/PUBLIC_RELEASE_CHECKLIST.md)

## Why Rokid Telegram?

| Selling point | What it means |
|---|---|
| **Telegram on glasses, not a phone mirror** | TDLib runs inside the glasses APK. Chat list, message history, media preview/playback, reply state, and notifications are first-class glasses UI. |
| **Glasses-first interaction model** | Built for a 480-wide Rokid HUD with high-contrast black + Rokid green, compact rows, explicit focus, and DPAD/touchpad navigation. |
| **Terminal / BBS density** | The UI favors information density and clear focused rows instead of phone-style oversized chat bubbles. |
| **Voice replies without typing on glasses** | Dictation is behind a provider seam. The production path uses the paired phone over CXR for microphone + ASR; the Sprite helper remains fallback/debug. |
| **Media-aware Telegram renderer** | Text, photos, videos, voice notes, stickers/animated emoji/service messages are summarized for the HUD. |
| **Local-first, sideloadable** | No custom message backend is required. Telegram credentials and TDLib session data remain local to the user's devices. |

## Architecture

```text
Rokid glasses
└─ rokid-telegram-native/ Android APK
   ├─ MainActivity + fragments: auth, chat list, chat, media/full-message views
   ├─ TDLib JNI (local tdlib.aar)
   ├─ TelegramService foreground service
   ├─ ChatRepo / MessageRepo / NotificationCenter
   ├─ ReplyPanel + InputRouter for Rokid keys / BT keyboard
   ├─ MediaCache + ExoPlayer voice/video playback
   └─ DictationProvider seam
      ├─ PhoneCxrDictationProvider  ← preferred production path
      └─ VoiceHelperBridge          ← Sprite helper fallback/debug

Telegram cloud
└─ MTProto through TDLib on the glasses

Android phone, optional for Dictate
└─ rokid-voice-companion/
   ├─ one-time Hi Rokid authorization
   ├─ foreground service bound to Hi Rokid CXR-L media service
   ├─ Android SpeechRecognizer / phone microphone
   └─ protocol: tg.dictate.start/cancel → tg.asr ready/partial/final
```

Only the **dictation / ASR module** moves to the phone companion. Telegram UI, TDLib, history, notification handling, media handling, and final send confirmation stay on the glasses.

## Repository layout

| Path | Purpose |
|---|---|
| `QUICKSTART.md` | Public quick start for building/installing Rokid Telegram. |
| `rokid-telegram-native/` | Main glasses APK (`com.wickedapp.rokidtg`). |
| `rokid-voice-companion/` | Optional Android phone CXR-L ASR companion for Dictate. |
| `voice-helper/` | Sprite Ink ASR helper kept as fallback/debug. |
| `scripts/rokid-tg-doctor.sh` | Host setup checker and local Telegram config helper. |
| `scripts/glasses-smoke.sh` | Build/install/launch smoke test for the glasses APK. |
| `scripts/seed-session.sh` | Optional power-user TDLib login seeding helper. |
| `scripts/push-helper.sh` | Pushes `voice-helper/` to the glasses. |
| `docs/ROKID_DESIGN_GUIDELINES.md` | Rokid visual and interaction constraints. |
| `docs/MAINTAINING_PUBLIC_AND_PRIVATE_BUILDS.md` | How maintainers keep public source clean while using private local config. |
| `docs/PUBLIC_RELEASE_CHECKLIST.md` | Checklist before publishing source tags or APKs. |

## Quick install from source

```bash
git clone https://github.com/wickedapp/rokid-telegram.git
cd rokid-telegram
./scripts/rokid-tg-doctor.sh
cp rokid-telegram-native/local.properties.example rokid-telegram-native/local.properties
```

Edit `rokid-telegram-native/local.properties`:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
tg.apiId=123456
tg.apiHash=0123456789abcdef0123456789abcdef
```

Build and install:

```bash
cd rokid-telegram-native
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.wickedapp.rokidtg/.MainActivity
```

See [`QUICKSTART.md`](QUICKSTART.md) for details and troubleshooting.

## Security and privacy

- Do not commit `local.properties`, TDLib sessions, phone authorization tokens, generated APKs with private credentials, or logs/screenshots containing chats.
- TDLib stores Telegram session data inside the app data directory on the glasses.
- The phone companion stores the Hi Rokid authorization token in private Android app storage so its foreground service can restart.
- Public APK releases need a runtime credential flow or an explicit credential policy; see [`docs/PUBLIC_RELEASE_CHECKLIST.md`](docs/PUBLIC_RELEASE_CHECKLIST.md).

## License

MIT. See [`LICENSE`](LICENSE).
