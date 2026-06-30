# hermes-glass-bridge

Mac + Rokid Glasses projects under one roof. Three independent products live here, each in its own directory:

| Directory | Product | Status |
|---|---|---|
| `app/` + `android-app/` + `web/` | **Hermes Glass Terminal** — FastAPI bridge + Android WebView wrapper that turns the Rokid Glasses into a HUD terminal for Hermes Agent / Claude / Codex on the Mac. | Active (the original project; see `docs/DEV_CONSOLE_PLAN.md`). |
| `rokid-telegram-native/` + `voice-helper/` | **Rokid Telegram (native)** — sideloaded bare-metal Android APK on the glasses that talks to Telegram directly (TDLib), plus a Sprite Ink companion that does on-device voice-to-text via Rokid's native ASR. Internet comes from the vivo X200 Ultra over BT-PAN. | **v1 in PR #1.** Spec: `docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md`. |
| `rokid-telegram-app/` | Earlier glasses-side WebView prototype. | **Superseded.** Reference only. |
| `rokid-telegram-phone-app/` | Earlier phone-side WebView companion. | **Superseded.** Reference only. |
| `rokid-aiui-mic-test/` | Probe of the Rokid Sprite/Ink `SpeechRecognition` runtime. | Reference; pattern used by `voice-helper/`. |

## Product 1 — Hermes Glass Terminal

Local bridge that turns Rokid Glasses into a Hermes / Claude / Codex dev console.

- Browser/Rokid AR terminal UI at `http://127.0.0.1:8765/`
- WebSocket command channel at `/ws/glass`
- `Hermes` mode: sends prompts to Hermes Agent API Server
- `Sessions` mode: lists/attaches/sends input to Mac tmux sessions for Claude/Codex/shell workflows
- `Claude` mode: runs Claude Code print mode (`claude -p`) in `~/HermesGlassProjects/<project>`
- `Codex` session support: start/list/capture Codex tasks through tmux
- `Terminal` mode: runs shell commands in `~/HermesGlassProjects/<project>`
- `Rokid AIUI voice`: uses glasses-side `SpeechRecognition`; final transcript is sent as text to the bridge

### Run
```bash
./scripts/run.sh
```
Open `http://127.0.0.1:8765/`.

### Env
```bash
export HERMES_API_BASE=http://127.0.0.1:8642/v1
export HERMES_GLASS_WORKSPACE=~/HermesGlassProjects
export HOST=127.0.0.1
export PORT=8765
```

Reads `API_SERVER_KEY` from `~/.hermes/.env` automatically, or set `HERMES_API_KEY=<key>`.

### Glasses APK (Hermes Terminal)

```bash
adb reverse tcp:8765 tcp:8765
```
Then the Rokid WebView uses `http://127.0.0.1:8765/?glasses=1`. APK builds at `android-app/`. Full dev plan: `docs/DEV_CONSOLE_PLAN.md`.

## Product 2 — Rokid Telegram (native)

A sideloaded Android APK that runs on the Rokid RG-glasses and acts as a Telegram client (chat list, open chat, text + photo + video + voice playback, BT keyboard / voice-to-text / voice note replies, off-chat notifications). Pair the glasses to your phone over Bluetooth; the phone provides internet via standard BT-PAN tethering. No phone-side companion app needed at runtime.

### Quick start
```bash
# 1. Get a Telegram api_id / api_hash at https://my.telegram.org/apps
# 2. Write them into rokid-telegram-native/local.properties:
#       tg.apiId=12345
#       tg.apiHash=abc123...
# 3. Build & install the APK
cd rokid-telegram-native
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH \
  ./gradlew :app:installDebug

# 4. Push the Sprite Ink voice helper
cd ..
scripts/push-helper.sh

# 5. One-time TDLib session seed (typing your phone number on the glasses HUD is awful;
#    do it once on Mac and push the binlog into the app's data dir)
scripts/seed-session.sh +<your-phone-number>

# 6. Smoke test on the connected glasses
scripts/glasses-smoke.sh
```

Subdir READMEs: `rokid-telegram-native/README.md`, `voice-helper/README.md`. Design rules: `docs/ROKID_DESIGN_GUIDELINES.md`. Full spec + decisions log: `docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md`.

## Repo layout

```
hermes-glass-bridge/
├── app/                            Hermes bridge (FastAPI)
├── web/                            Hermes HUD UI
├── android-app/                    Hermes Terminal APK (WebView)
├── rokid-telegram-native/          ← Rokid Telegram, native APK + Sprite Ink helper
├── voice-helper/                   ← Sprite Ink voice→text companion (.aix)
├── rokid-telegram-app/             [SUPERSEDED] earlier glasses WebView prototype
├── rokid-telegram-phone-app/       [SUPERSEDED] earlier phone WebView companion
├── rokid-aiui-mic-test/            Sprite Ink ASR probe (reference)
├── scripts/
│   ├── run.sh / run-lan.sh         Hermes bridge launchers
│   ├── mirror-rokid.sh             scrcpy mirror (visual verification path on glasses)
│   ├── screenshot-rokid.sh         scrcpy screenshot
│   ├── seed-session.sh             ← TDLib login on Mac → adb push binlog
│   ├── push-helper.sh              ← push voice-helper.aix to glasses
│   └── glasses-smoke.sh            ← end-to-end smoke for the Telegram client
├── docs/
│   ├── DEV_CONSOLE_PLAN.md         Hermes Terminal dev plan
│   ├── ROKID_DESIGN_GUIDELINES.md  Glasses UI rules (480x400 safe area, #40FF5E on #000, etc.)
│   └── superpowers/
│       ├── specs/                  Design specs
│       └── plans/                  Implementation plans
└── HANDOFF_ROKID_TELEGRAM.md       [SUPERSEDED] old direction; see spec instead
```
