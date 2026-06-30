# Rokid Telegram (native)

Sideloaded bare-metal Android APK that runs on Rokid RG-glasses and acts as a personal Telegram client.

- **Telegram session lives on the glasses** — TDLib (`org.drinkless.tdlib` v1.8.65 via `app/libs/tdlib.aar`) holds the MTProto connection.
- **Internet from the vivo X200 Ultra over Bluetooth tethering** — glasses see `bt-pan` as a normal network interface; no phone-side companion app at runtime.
- **Voice-to-text via Rokid's native ASR** — a separate Sprite Ink mini-app at `../voice-helper/` runs `SpeechRecognition` (NXP RT600 + iFlytek pipeline) and ships transcripts over a localhost WebSocket with per-session UUID nonce.
- **Voice notes** — 8-channel `AudioRecord` (mask `0x6000FC`) → keep ch 0/1 → Concentus Opus encoder → custom RFC 7845 OggWriter → TDLib `InputMessageVoiceNote`.

Spec + decisions: `../docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md`
Design rules: `../docs/ROKID_DESIGN_GUIDELINES.md`
PR: https://github.com/wickedapp/hermes-glass-bridge/pull/1

## Prereqs

- macOS host with JDK 17 (`/opt/homebrew/opt/openjdk@17`) and Android SDK (`~/Library/Android/sdk`).
- Rokid RG-glasses connected via dev cable (serial `1906092624100227` in this repo's scripts; override with `SERIAL=<id>`).
- A Telegram developer account → grab `api_id` / `api_hash` at https://my.telegram.org/apps.

Write your Telegram credentials into `local.properties` (gitignored):
```
sdk.dir=/Users/wickedapp/Library/Android/sdk
tg.apiId=12345
tg.apiHash=abcdef0123456789abcdef0123456789
```

## Build & install

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH \
  ./gradlew :app:installDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Package id: `com.wickedapp.rokidtg`.

## Seed the TDLib session (one-time)

Typing your phone number + SMS code on the 480-wide HUD is miserable; do it once on Mac and `adb push` the resulting `td.binlog` into the app's data dir:

```bash
../scripts/seed-session.sh +<your-phone-number>
```

The script expects [`tdl`](https://github.com/iyear/tdl) on `PATH`.

## Push the voice helper

```bash
../scripts/push-helper.sh
```

This pushes `voice-helper/` to `/sdcard/aix/voice-helper` on the glasses and asks the Sprite launcher to reload its aix index.

## Smoke

```bash
../scripts/glasses-smoke.sh
```

Builds, installs, pushes the helper, launches `MainActivity`, soft-checks authorization, fires safe key events, verifies the foreground service is running + the `tg-banner` notification channel is registered, then runs the JVM unit tests. Exits 0 on success.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

17 JVM unit tests across `data`, `service`, `voice`, `media`, `ui.input` packages. No instrumented tests yet.

## Project layout

```
rokid-telegram-native/
├── app/
│   ├── build.gradle.kts
│   ├── libs/
│   │   ├── tdlib.aar       (FaiBah/tdlib-android-prebuilt v1.8.65, ~38 MB)
│   │   └── concentus.jar   (pure-Java Opus encoder)
│   └── src/
│       ├── main/kotlin/com/wickedapp/rokidtg/
│       │   ├── MainActivity.kt         Single Activity, fragment-swap host
│       │   ├── TelegramApp.kt          Application + Timber init
│       │   ├── service/
│       │   │   ├── TelegramService.kt  Foreground service; owns TdLibClient + repos + monitors
│       │   │   ├── TdLibClient.kt      JNI wrapper, updates SharedFlow
│       │   │   ├── NetworkMonitor.kt   Maps WiFi/Cellular/BT transports → TDLib SetNetworkType
│       │   │   └── NotificationCenter.kt  Off-chat banner posts
│       │   ├── data/
│       │   │   ├── ChatRepo.kt         Live chat list + search
│       │   │   └── MessageRepo.kt      Per-chat history + pagination (service-cached)
│       │   ├── ui/
│       │   │   ├── ChatListFragment.kt
│       │   │   ├── ChatFragment.kt
│       │   │   ├── MediaViewerFragment.kt
│       │   │   ├── ComposerOverlay.kt  Voice→text + voice note + BT keyboard
│       │   │   ├── BannerHost.kt       Top-of-safe-area error/info pill
│       │   │   └── input/              SpriteBroadcast + InputRouter
│       │   ├── voice/
│       │   │   ├── VoiceHelperBridge.kt   localhost WebSocket server (ephemeral port + nonce)
│       │   │   ├── AudioCapturer.kt       8-channel AudioRecord, ch 0/1 kept
│       │   │   ├── VoiceNoteEncoder.kt    Concentus Opus + Ogg
│       │   │   └── OggWriter.kt           RFC 3533 + 7845 mux
│       │   └── media/
│       │       ├── MediaCache.kt          BitmapFactory 480px/RGB_565
│       │       └── MediaPlayerPool.kt     ExoPlayer slot for voice playback
│       ├── src/main/res/                Layouts, drawables, dimens, fonts, themes
│       ├── src/test/kotlin/...          17 JVM unit tests
│       └── src/debug/                   DesignPreviewActivity for typography/stroke checks
└── settings.gradle.kts, gradle/wrapper, gradlew, ...
```
