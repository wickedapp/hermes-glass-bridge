# Rokid Glasses Telegram Client — Design Spec

**Date:** 2026-06-30  
**Target device:** Rokid RG-glasses (`model: RG_glasses`, Android 12 / YodaOS-Sprite, Android Go base, 480 × 640 OLED, serial `1906092624100227`)  
**Paired phone:** vivo X200 Ultra (Android)  
**Status:** **implemented — v1 in [PR #1](https://github.com/wickedapp/hermes-glass-bridge/pull/1)** (rokid-tg-client branch, 31 commits across 16 plan tasks + 7 task-level fix waves + 2 whole-branch fix waves; 17/17 JVM unit tests pass; on-device smoke green). See `docs/superpowers/plans/2026-06-30-rokid-glasses-telegram-client.md` and `.superpowers/sdd/progress.md` for the per-task ledger.

## Goal

A sideloaded bare-metal Android APK on the Rokid Glasses that gives the wearer a usable personal Telegram client — chat list, open chat, read text, view photos / videos, play voice notes, reply by typed text (BT keyboard), by speech-to-text (transcribed via the official Rokid `SpeechRecognition` pipeline), or by Telegram voice note.

The phone is a Bluetooth tether for internet, not a runtime brain. The glasses own the Telegram session.

## Non-goals (v1)

- No phone-side companion app, no Mac bridge, no cloud server we control.
- No iOS path (vivo is the paired phone; iOS BT-tethering for non-MFi peers is a separate problem).
- No multi-account.
- No incoming-voice-note auto-transcription (you play them — TDLib gives you the audio).
- No CXR-L / CXR-S SDK integration. We evaluated and rejected it: those SDKs are the correct path *only* when the glasses can't reach the internet themselves; with BT-PAN active, plain bare-metal apps have a real `bt-pan` network interface and don't need CXR.
- No on-device or cloud STT in our APK. ASR is delegated to the Sprite Ink `SpeechRecognition` runtime via a co-installed helper.

## Decisions log (locked in this conversation)

| # | Decision | Rationale |
|---|---|---|
| D1 | **Bare-metal Android APK on glasses.** Standard `adb install`, runs in the Sprite launcher. | The official "裸机开发" path is the simplest official path; CXR's only edge (no-internet glasses) is moot once BT-PAN is enabled. |
| D2 | **TDLib in-process for the Telegram protocol.** No Bot API. | Bot API can't see personal chats / DMs. TDLib is the only way. |
| D3 | **Single Android activity, fragment-swapped UI.** No Compose. | Android Go memory headroom is small; classic Views + ViewBinding wins on RAM + cold start. |
| D4 | **Foreground service owns TDLib.** UI binds on resume, unbinds on pause. | Survives UI destruction. Required for notifications on Android Go under Doze. |
| D5 | **Internet outside = vivo Bluetooth tethering.** Glasses see `bt-pan` as a normal network interface. | Glasses have no cellular; Hi Rokid's BT relay is private. BT-PAN from vivo is the only real option, accepted by user (always-paired). |
| D6 | **Voice → text uses Sprite Ink helper (Path 1).** A separate `voice-helper.aix` runs `SpeechRecognition`, ships transcripts over `ws://127.0.0.1:48761` to the bare-metal APK. | Rokid's blessed ASR is gated to the Sprite/Ink runtime via `JsaiService`. The helper is the only way to use it from a bare-metal app. No cloud bills, no extra model on disk. |
| D7 | **Voice notes captured & encoded in the bare-metal APK** via `AudioRecord` (8-ch mask `0x6000FC`, ch 0/1 kept) → MediaCodec Opus → TDLib `InputMessageVoiceNote`. | The audio doc gives us exactly this. ASR helper handles transcripts; voice notes are a separate path. |
| D8 | **Onboarding via seeded session.** TDLib login is done once on Mac; resulting `td.binlog` is `adb push`-ed into the glasses app's data dir. | Typing a phone number + SMS code on a 480-wide HUD is a one-time but miserable UX. Side-step it. |
| D9 | **No backup of session.** Losing/wiping the glasses = re-seed. | Session is sensitive; cloud-backing it would require infra we don't want. |

## References

- **Rokid design guidelines** — `docs/ROKID_DESIGN_GUIDELINES.md` (this repo). Authoritative for typography, color (`#40FF5E` on `#000000`), 480 × 400 safe area, strokes (≥ 1.5 px, 12 px radius), icons.
- **Bare-metal dev overview** — `custom.rokid.com/.../13083daf77dd40bf84cf5c59711e987a.html?documentId=2b2d054ae0404970a0811ccc1ff8e02e`
- **Key/button dev** — `…?documentId=d1c4a61a6ad548189c9409e7101bdbf3` (broadcast-based, `com.android.action.ACTION_SPRITE_*`, priority 100)
- **Audio recording dev** — `…?documentId=3c116dc4e81c4b218e8442d9971f8618` (8-ch `AudioRecord`, mask `0x6000FC`, 16 kHz PCM_16)
- **CXR L/S/M docs** (rejected path, retained for fallback) — `github.com/buildwithfenna/rokid-docs`
- **TDLib** — `github.com/tdlib/td` (build: `tdlib-android-x86_64+arm64-v8a`, JNI loaded by service)
- **Existing repo prior art**
  - `android-app/` — Hermes Terminal APK (WebView). Reuse: `MainActivity` scaffolding, Gradle config.
  - `rokid-aiui-mic-test/` — proves Sprite Ink `SpeechRecognition` works on this device. The voice helper starts from this layout.
  - `app/main.py` — the FastAPI bridge. **Not used** in this app. May be retired separately.
  - `rokid-telegram-phone-app/` — superseded; do not extend.

## Architecture

```
┌────────────────────── Rokid Glasses (Android Go 12) ───────────────────────┐
│                                                                            │
│   ┌─ MainActivity (single, fragment host) ───────────────────────────┐     │
│   │ ChatListFragment │ ChatFragment │ MediaViewerFragment │ Composer │     │
│   └─────────────────────────────────────────────────────────────────┘     │
│                              ▲ bind                                        │
│   ┌─ TelegramService (foreground, persistent) ──────────────────────┐     │
│   │ TdLibClient (JNI) │ MediaCache │ AudioCapturer │ VoiceNoteEnc   │     │
│   │ VoiceHelperBridge (WS server :48761) │ NotificationCenter        │     │
│   └─────────────────────────────────────────────────────────────────┘     │
│                              ▲ launch via am start                         │
│   ┌─ voice-helper.aix (Sprite Ink, separate package) ───────────────┐     │
│   │ SpeechRecognition (Rokid ASR) → WebSocket → 127.0.0.1:48761      │     │
│   └─────────────────────────────────────────────────────────────────┘     │
│                              ▲ network                                     │
│              wlan0 (home Wi-Fi)  OR  bt-pan (vivo tether)                  │
└────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼  MTProto/HTTPS
                            Telegram servers
```

## Components

| Component | Process | Threading | Notes |
|---|---|---|---|
| `MainActivity` | UI | UI | Single activity, owns `FragmentManager` and focus root. |
| Fragments (`ChatList`, `Chat`, `MediaViewer`, `ComposerOverlay`) | UI | UI | All interactive views `focusable=true` with explicit `nextFocus*`. |
| `TelegramService` | Foreground service (own process? **no** — same process to share TDLib instance cheaply) | service main + IO executor | Bound by UI on resume. Holds the persistent notification (channel `tg-ongoing`, importance `LOW`). |
| `TdLibClient` | inside service | TDLib's IO thread + 1 receiver | Wraps `Client.send(query, handler)`; updates fan out via `MutableSharedFlow<TdApi.Update>`. Storage caps: TDLib internal file cache hard-capped at **500 MB** via periodic `optimizeStorage(size=500_000_000)`; ours is a separate 150 MB Coil cache (next row). |
| `MediaCache` | service | IO | LRU at `/data/data/<pkg>/files/media/`, cap **150 MB**. Photos decoded at 480 px wide, `RGB_565`. |
| `MediaPlayerPool` | service | dedicated handler | Media3 `ExoPlayer` instances; audio attrs `USAGE_MEDIA / CONTENT_TYPE_SPEECH` for voice, `MOVIE` for videos. |
| `AudioCapturer` | service | capture thread | `AudioRecord.Builder()` with `sampleRate=16000`, `channelMask=0x6000FC`, `encoding=PCM_16BIT`. Drops ch 2–7, keeps 0/1. Permission `RECORD_AUDIO`. |
| `VoiceNoteEncoder` | service | encoder thread | `MediaCodec(audio/opus)`, 24 kbps mono → OGG → TDLib `InputMessageVoiceNote(path, duration, waveform)`. |
| `VoiceHelperBridge` | service | netty/Java-WebSocket | Loopback server `127.0.0.1:48761`. Protocol §"Voice helper protocol" below. |
| `InputRouter` | UI | UI | `BroadcastReceiver(priority=100)` for `ACTION_SPRITE_*` + `onKeyDown` for `KEYCODE_ENTER` / `KEYCODE_BACK`. Gesture map in §"Input map". |
| `NetworkMonitor` | UI + service | callback | `ConnectivityManager.NetworkCallback`; on any UP → `setNetworkType(WiFi)`, on none → `setNetworkType(None)`. |
| `NotificationCenter` | service | service main | Posts banners for `UpdateNewMessage` when target chat is not the open one. Respects TDLib `notificationSettings.muteFor`. |
| `voice-helper.aix` | separate process (Sprite Ink runtime) | Ink JS | ~100 LOC. Opens `SpeechRecognition({lang:'zh-CN', interimResults:true})`, opens WS to `127.0.0.1:48761`, ships transcripts, exits on `final` or 8 s no-result. |

**Language stack:** Kotlin 1.9, AGP 8.x, `minSdk 28`, `targetSdk 33`. Libraries: Coil 2.x, AndroidX Media3 1.x, Java-WebSocket 1.5.x, TDLib JNI. **No Jetpack Compose.**

**On-disk layout** under `/data/data/com.wickedapp.rokidtg/`:
```
files/tdlib/db/                ← TDLib chat/message database
files/tdlib/files/             ← TDLib's media cache
files/tdlib/td.binlog          ← session (seeded by adb push)
files/media/thumbs/            ← Coil 480-wide RGB_565 cache
files/media/voice/             ← downloaded OGG voice notes
files/logs/ring.log            ← Timber ring-buffer (10 MB)
```

## Voice helper protocol

WebSocket text frames, JSON. Helper is client, bare-metal APK is server. Single connection per session.

```
helper → server  {"type":"ready"}
helper → server  {"type":"interim","text":"…"}     // 0..N
helper → server  {"type":"final","text":"…"}       // 0..1
server → helper  {"type":"close"}                  // optional cancel
helper → server  {"type":"error","code":"…","msg":"…"}
```

Server times out the session if no `ready` within 1500 ms of `am start` or no `final` within 8 s of `ready`.

## Input map (from key/button doc + 2-finger touchpad gestures)

| Gesture (glasses) | Bare-metal action |
|---|---|
| Single-tap touchpad → `KEYCODE_ENTER` | Activate focused element |
| Two-finger swipe forward | Focus next / scroll down |
| Two-finger swipe back | Focus prev / scroll up |
| Two-finger tap | Open chat from list / open media |
| Two-finger double-tap | Toggle voice composer (start / send / cancel state machine) |
| Two-finger long-press → `ACTION_SETTINGS_KEY` | Open in-app settings |
| Double-click upper button → system back (not interceptable) | `onBackPressed` → fragment pop |
| Single-click upper button | system photo (cannot use in our app) |
| Long-press upper button | system video (cannot use) |
| Long-press touchpad → `ACTION_AI_START` | system AI (cannot use) |
| BT keyboard | standard Android — `EditText` consumes typed input, Enter sends |

## Data flow

Summary of the 11 user stories the v1 cut covers. Each is implemented as the listed TDLib call sequence; UI side-effects flow back via the service's `MutableSharedFlow<TdApi.Update>`.

1. Cold start uses seeded session; UI binds to service.
2. Chat list = `getChats(MainList, 20)` + live updates via `UpdateNewMessage`.
3. Open chat = `getChatHistory(chatId, 30)`; pagination on back-swipe.
4. Reply voice→text = `am start` voice helper → WS transcripts → composer → `sendMessage(InputMessageText)`.
5. Reply voice note = `AudioRecord(0x6000FC)` → MediaCodec Opus → `sendMessage(InputMessageVoiceNote)`.
6. Reply BT keyboard = `EditText` → Enter → `sendMessage(InputMessageText)`.
7. Play voice note = `downloadFile` → ExoPlayer with `CONTENT_TYPE_SPEECH`.
8. View photo = pick size ≥ 480 px wide → Coil decode at 480 / `RGB_565` → fullscreen.
9. View video = `downloadFile` → ExoPlayer fullscreen → audio via glasses speaker.
10. Notification = `UpdateNewMessage` for non-open chat → `NotificationManager.notify`.
11. Network = on `bt-pan` UP / `wlan0` UP → `setNetworkType(WiFi)`.

## Error handling

| Failure | User-visible | Recovery |
|---|---|---|
| Session lost | "Session ended. Re-pair via Mac." | App locks to instructions screen; new `td.binlog` required. |
| All connectivity lost | "Offline" pill | TDLib paused; auto-resume on UP. |
| TG servers unreachable | "Connecting…" pill | TDLib retries with backoff. |
| Send fails mid-flight | "↻" glyph on bubble | Auto-retry once; tap = manual retry; swipe-back = delete pending. |
| Voice helper fails to launch | "Voice helper not ready" + fallback to keyboard | Path 1 disabled for the session. |
| Helper returns no transcript in 8 s | "Didn't catch that" | Retry; 3 failures / 10 min ⇒ keyboard fallback. |
| Mic busy (system grabbed) | "Mic in use by system" | Auto-retry once after 500 ms. |
| TDLib disk > 80 % of 500 MB cap | "Storage…" indicator | `optimizeStorage`; if > 90 %, prompt to clear. |
| Image OOM risk | n/a (decode policy prevents) | n/a |
| Video codec unsupported | "↓" glyph instead of play | Single-tap = save to `Downloads/`. |
| Service killed by LMK | "Resuming…" L4 label | `startForegroundService` + reconnect; pull deltas. |
| Notification permission denied | inline one-time banner | unread dot per row instead of banners. |

## Testing strategy

| Layer | Where | Coverage |
|---|---|---|
| JVM unit | Mac, no device | TDLib wrapper, `VoiceHelperBridge`, decode policy, error state machines, focus traversal. Target ≥ 70 % on `tg-core`/`tg-data`/`tg-net`. |
| Instrumented | Glasses via adb (`connectedAndroidTest`) | Activity start, fragment swap, broadcast priority/abort, `AudioRecord` mask, ExoPlayer routing. |
| Sprite Ink unit | Mac, Ink toolchain | Helper packing + reconnect + WS protocol. |
| On-device smoke | Glasses via adb | `scripts/glasses-smoke.sh` — install APK, push `.aix`, push `td.binlog`, `am start`, fake `ACTION_SPRITE_*` broadcasts, `adb logcat` filter, log-buffer pull, assert markers. |
| Manual UX | Worn glasses | ASR quality in noise, outside on `bt-pan`, battery, glanceability, BT pair flow. |

CI: GitHub Actions for JVM unit + lint. No device CI for v1.

## Open items / TBDs

| Tag | Item | Resolution path |
|---|---|---|
| `[VERIFY:launch-intent]` | Exact intent shape to launch `voice-helper.aix` from the bare-metal APK via `am start`. | Day-1 implementation probe on connected device. Fallback if it fails: bake a one-line shell script that user runs from settings, or fall back to Path 4 (cloud STT). |
| `[VERIFY:bt-pan]` | Confirm `bt-pan` actually appears as a usable `NetworkCapabilities.NET_CAPABILITY_INTERNET` interface to bare-metal apps when vivo tethers. | Empirical: enable BT tethering on vivo, `adb shell dumpsys connectivity`. Done before v1 ship. |
| `[VERIFY:aix-distribution]` | How to `adb push` and install an `.aix` package. Sprite uses `.aix` like APKs but the install command isn't documented. | Read prior `.aix` push experience in `rokid-aiui-mic-test/` history or probe `pm install` behavior. |
| `[VERIFY:tts-binder]` | Whether `com.rokid.os.sprite.tts.TTS_SERVICE` can be bound from a bare-metal app for reading messages aloud. v1 doesn't need it; v2 does. | Defer to v2. |
| Outgoing voice-note format | Telegram requires OGG/Opus 16 kHz mono. Confirm MediaCodec's `audio/opus` output is OGG-containered correctly (or wrap with `OggMux`). | First implementation PR. |

## Out-of-scope (v2 candidates)

- Incoming voice-note transcription (call helper after download).
- TTS read-aloud of new messages (`com.rokid.os.sprite.tts.TTS_SERVICE`).
- Send photo / video from glasses (camera capture is system-owned; needs broadcast hook).
- Multi-account.
- Settings UI for notification mute, cache cap, language, font size override.
