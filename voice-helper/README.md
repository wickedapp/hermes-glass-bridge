# voice-helper (Sprite Ink mini-app)

A tiny Rokid Sprite Ink mini-app whose only job is to run `SpeechRecognition` and ship the transcript to the bare-metal Telegram APK over a localhost WebSocket.

Lives in the Sprite Ink runtime — **separate process / separate installable** from `../rokid-telegram-native/`. The two are paired by:
- `MainActivity.getOrCreateBridge()` in the Android APK starts a `VoiceHelperBridge` on an ephemeral 127.0.0.1 port and generates a fresh `sessionNonce` (UUID) per session.
- The Android side launches this helper via `am start` and (will) pass `port` + `nonce` to it.
- This helper opens `ws://127.0.0.1:<port>`, sends `{"type":"ready","nonce":"<uuid>"}`, then streams `{"type":"interim","text":"..."}` / `{"type":"final","text":"..."}` as Rokid's ASR returns results, then exits.

The Android-side bridge rejects any `ready` frame whose nonce doesn't match, so other apps on the device can't inject fake transcripts.

## Files

```
voice-helper/
├── app.json                 manifest: appId "com.wickedapp.voicehelper"
├── app.js                   App() lifecycle stubs
└── pages/voice/voice.ink    SpeechRecognition + WebSocket + auto-exit
```

Modelled on `../rokid-aiui-mic-test/`, which proved on this device that the Sprite Ink runtime exposes `SpeechRecognition` wired to the on-device Rokid ASR pipeline (NXP RT600 + iFlytek via `JsaiService`).

## Push to glasses

```bash
../scripts/push-helper.sh
```

Pushes this directory to `/sdcard/aix/voice-helper` on the connected Rokid (serial `1906092624100227` by default; override with `SERIAL=<id>`) and asks the Sprite launcher to reload its aix index via `com.rokid.sprite.aix.RELOAD`.

If the launcher doesn't pick it up automatically, fallback is `adb shell am force-stop com.rokid.os.sprite.launcher` — it rescans on next launch.

## Status

- **Voice-helper APK + bridge protocol implemented and unit-tested** on the Android side (`VoiceHelperBridgeTest` asserts the nonce handshake + interim/final ordering).
- **End-to-end `am start` launch round-trip is `[VERIFY:launch-intent]`** — the intent shape (`com.rokid.os.sprite.launcher/.main.SpriteMainActivity --es appId com.wickedapp.voicehelper`) is plausible but hasn't been observed working in a session. Needs a manual on-device test.
- If the helper can't read intent extras (Sprite SDK capability unknown), the alternative is a file-based handshake — write `{port, nonce}` to a shared file before launching. `boundPort` and `sessionNonce` are exposed on the bridge for exactly this fallback.

## Related

- Spec: `../docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md` (D6 + Decisions log)
- Bridge code: `../rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/voice/VoiceHelperBridge.kt`
- Bridge tests: `../rokid-telegram-native/app/src/test/kotlin/com/wickedapp/rokidtg/voice/VoiceHelperBridgeTest.kt`
