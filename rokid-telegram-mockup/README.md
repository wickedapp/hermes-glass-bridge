# Rokid Telegram Mockup (Sprite Ink)

A throwaway UI mockup for operating Telegram on Rokid RG-glasses over the **production CXR companion direction**: phone owns Telegram/TDLib/network/ASR, glasses render HUD and send small controls through Rokid's companion channel.

## Why this exists

This mockup is deliberately **not** the production TDLib client. It is a fast, inspectable UI artifact built with the same official-style Rokid/Sprite Ink mini-program structure as the vendor scaffold in `artifacts/rokid-scaffold-original/`:

- `app.json` declares `appId`, title, version, and page list.
- `app.js` uses the standard `App({ ... })` entrypoint.
- `pages/main/main.ink` uses Ink markup, `export default { data, onKeyUp, ... }`, `setData`, and `bindtap`, matching the official sample pattern.
- It can be pushed as a dev folder under `/sdcard/aix/` and reloaded by the Sprite launcher, the same path used by `voice-helper/`.
- It models the CXR flow now proven on-device: phone companion sends `tg.chat_list` / `tg.conversation` / `tg.asr`, glasses send focus/action events back.

## Glass UI constraints used

Source: `docs/ROKID_DESIGN_GUIDELINES.md` and live ADB facts from the connected device.

| Constraint | Applied here |
|---|---|
| Device | Rokid `RG-glasses`, Android 12 |
| Display | 480 × 640 px, density 240 |
| Safe area | Critical UI inside the middle 480 × 400 band |
| Top/bottom strips | Only status/banner/composer hints |
| Color | Transparent black `#000000`, Rokid green `#40FF5E` |
| Strokes | 2 px stroked cards, 12 px radius; no big fills |
| Text | 16 px minimum; primary text 20/26, title 32/40 |
| Motion | Instant state changes only |
| Input | DPAD/keyboard/touch: Up/Down changes focus, Enter advances, Back returns |

## Interaction flow

1. **Chat list** — three 64 px rows, focused row marker, unread line indicator.
2. **Chat** — stroked message cards; peer messages left/40% stroke, own message right/80% stroke.
3. **Voice composer** — interim/final transcript states and a discrete meter (`▂ ▃ ▅ ▇`) instead of a filled waveform blob.
4. **Incoming banner** — top-strip stroked notification that does not cover the message stream.

## Run on connected glasses

```bash
cd /Volumes/DATA/Development/hermes-glass-bridge
scripts/push-telegram-mockup.sh
```

Because Rokid optical output is not exposed through `adb screencap`, visual verification still needs scrcpy mirror or wearing the glasses. Functional verification can use logs/dumpsys.

## Build a packaged `.aix`

```bash
cd /Volumes/DATA/Development/hermes-glass-bridge
mkdir -p artifacts/aix
(cd rokid-telegram-mockup && zip -r ../artifacts/aix/rokid-telegram-mockup.aix .)
```

## Production follow-up

When the layout feels right, port the decisions into the CXR-backed glasses APK: keep TDLib/session/network in the phone companion, and keep the glasses APK focused on HUD/input/display. The mockup should remain disposable.
