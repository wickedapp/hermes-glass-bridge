# Rokid Dev Console / Hermes Glass Bridge

Local bridge for turning Rokid Glasses into a Hermes / Claude / Codex development console.

See the full development plan at `docs/DEV_CONSOLE_PLAN.md`.

## What it does

- Browser/Rokid-friendly AR terminal UI at `http://127.0.0.1:8765/`
- WebSocket command channel at `/ws/glass`
- `Hermes` mode: sends prompts to Hermes Agent API Server
- `Sessions` mode: lists/attaches/sends input to Mac tmux sessions for Claude/Codex/shell workflows
- `Claude` mode: runs Claude Code print mode (`claude -p`) in `~/HermesGlassProjects/<project>`
- `Codex` session support: start/list/capture Codex tasks through tmux
- `Terminal` mode: runs shell commands in `~/HermesGlassProjects/<project>` and streams output back to the HUD
- `Rokid AIUI voice`: uses glasses-side `SpeechRecognition`; final transcript is sent as text to the bridge


## Run

```bash
./scripts/run.sh
```

Open:

```text
http://127.0.0.1:8765/
```

## Env

```bash
export HERMES_API_BASE=http://127.0.0.1:8642/v1
export HERMES_GLASS_WORKSPACE=~/HermesGlassProjects
export HOST=127.0.0.1
export PORT=8765
```

The bridge reads `API_SERVER_KEY` from `~/.hermes/.env` automatically, or use:

```bash
export HERMES_API_KEY=<your-api-server-key>
```

## Android / Rokid APK

During USB development, use ADB reverse and the local loopback URL inside Rokid:

```bash
adb reverse tcp:8765 tcp:8765
adb reverse tcp:60973 tcp:60973   # optional preview app port
```

Then the Rokid WebView can use:

```text
http://127.0.0.1:8765/?glasses=1
```

Quick mirror launcher on this Mac:

```bash
~/Projects/hermes-glass-bridge/scripts/start-rokid-mirror.sh
```

or double-click `~/Desktop/Rokid Mirror.command`.

### Glasses controls

| Control | Action |
| --- | --- |
| `Enter` | send current input |
| `Tab` / `Ctrl+M` | cycle Hermes → Claude → Terminal |
| `Mic` / `Ctrl+Space` | start/stop voice recording |
| `Edit` / `Auto` or `Ctrl+A` | voice transcript mode: edit before send vs auto-send to Claude |
| `Ctrl+L` | clear HUD |
| `/open URL` | navigate the Rokid WebView to a preview URL |

Voice path on tested RG-glasses: WebView `MediaRecorder` → bridge `/stt` → local `faster-whisper` → transcript. Android `SpeechRecognizer` is not available on this device image.

A first Android wrapper app is included at `android-app/`. It is a real launchable app named **Hermes Terminal**. Open it on Rokid, enter your Mac bridge URL, then tap **Connect**. The app loads the HUD terminal UI in a WebView; tap **HUD** to hide the app chrome and make it feel like a full-screen glasses terminal.

Build:

```bash
cd android-app
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH
./gradlew assembleDebug
```

APK:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

Install when Rokid is connected with ADB:

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

Important: `127.0.0.1` inside the Rokid app means the glasses device itself, not your Mac. For real use, run the bridge on LAN/Tailscale:

```bash
./scripts/run-lan.sh
```

Then enter:

```text
http://<mac-lan-or-tailscale-ip>:8765/
```

## Rokid WebSocket protocol

For a native CXR-M app later, connect a WebSocket client to:

```text
ws://<mac-tailscale-ip>:8765/ws/glass
```

Send Hermes prompt:

```json
{"type":"agent_prompt","project":"default","text":"請確認眼鏡 terminal 已連上 Hermes"}
```

Send Claude Code prompt:

```json
{"type":"claude_prompt","project":"default","text":"build a snake game and run it on port 3000"}
```

Send terminal command:

```json
{"type":"shell_command","project":"default","command":"pwd && ls -la"}
```

The server streams events like `agent_delta`, `term_delta`, `agent_done`, and `term_done`.
