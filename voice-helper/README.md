# voice-helper (Sprite Ink fallback)

`voice-helper/` is a small Rokid Sprite Ink mini-app used as a **fallback/debug** dictation path for Rokid TG.

The current product direction is:

1. `rokid-telegram-native/` stays on the glasses and owns Telegram/TDLib/UI/send flow.
2. `rokid-voice-companion/` is the preferred production dictation provider over CXR.
3. `voice-helper/` remains useful for proving Rokid on-device `SpeechRecognition` behavior and for fallback experiments.

## How it works

```text
Rokid TG native APK
└─ VoiceHelperBridge starts a localhost WebSocket server
   └─ launches this Sprite Ink helper
      └─ helper calls Rokid SpeechRecognition
         └─ helper streams ready/interim/final JSON frames back to the APK
```

The Android bridge uses an ephemeral port and per-session nonce so stale or unrelated helper frames are ignored.

## Files

```text
voice-helper/
├── app.json
├── app.js
└── pages/voice/voice.ink
```

`pages/voice/voice.ink` contains the Rokid `SpeechRecognition` + WebSocket logic.

## Push to glasses

From repo root:

```bash
./scripts/push-helper.sh
```

This pushes the directory to:

```text
/sdcard/aix/voice-helper
```

and broadcasts:

```text
com.rokid.sprite.aix.RELOAD
```

If the launcher does not rescan automatically, force-stop the Sprite launcher and relaunch it.

## Verification hints

Useful native APK log tags:

```bash
adb logcat -d -s VoiceBridge PhoneDictation TG AndroidRuntime
```

Healthy fallback indicators include:

```text
VoiceBridge: onOpen
VoiceBridge: onMessage {"type":"ready"...}
VoiceBridge: onMessage {"type":"interim"...}
VoiceBridge: onMessage {"type":"final"...}
```

Also verify foreground after dictation. A transcript in logs is not enough if the helper Activity remains visible instead of returning to the Telegram chat page.

## Common pitfalls

- The glasses WLAN/IP can change, which can break helper WebSocket reachability.
- Sprite helper pages can steal foreground from the native APK; the product UI must be brought back to the chat page on ready/final.
- If helper WebSocket readiness fails, do not keep adding hardcoded IPs forever. Prefer the phone companion path for production.
