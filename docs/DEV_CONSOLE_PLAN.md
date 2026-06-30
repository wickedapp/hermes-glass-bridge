# Rokid Dev Console Development Plan

## Goal

Build a single **Rokid Dev Console** app, not two separate apps, for controlling local/Mac-mini AI development sessions while away from the computer.

The app is not primarily a full terminal emulator. It is an **AI coding session console** for:

1. Hermes Agent
2. Claude Code sessions
3. Codex tasks/sessions
4. Lightweight shell commands

## Core User Scenario

The user is wearing Rokid Glasses and wants to continue development without being at the computer:

```text
Rokid Glasses App
  - voice input via Rokid AIUI SpeechRecognition
  - optional Bluetooth keyboard input
  - session/status display

        ↓ WSS/WS bridge

Mac / Mac mini
  - Hermes Glass Bridge
  - Hermes API Server
  - Claude Code CLI in tmux sessions
  - Codex CLI tasks/processes
  - project workspaces
```

The glasses app is a remote control surface. The actual coding agents run on the Mac/Mac mini.

## Current Working State

Already proven:

- Rokid AIUI `SpeechRecognition` works on-device.
- Final transcript is produced on the glasses side.
- App can connect to bridge via WebSocket over LAN.
- Bridge can call Hermes API.
- Hermes response can be displayed on Rokid.
- ASR can run continuously without tap/click.

Current implementation paths:

- Bridge: `app/main.py`
- Rokid Ink app: `rokid-aiui-mic-test/pages/index/index.ink`
- Package artifacts: `artifacts/rokid-aiui-hermes-terminal.aix`

## Product Shape

One app with multiple modes:

```text
Rokid Dev Console
  ├── Hermes Mode
  ├── Sessions Mode
  │   ├── Claude sessions
  │   ├── Codex tasks
  │   └── tmux/shell sessions
  └── Shell Mode
```

Do **not** start by building a full VT100 terminal emulator. That is too much surface area for glasses and not needed for the main use case.

## Mode Details

### 1. Hermes Mode

Purpose:

- Ask Hermes questions.
- Let Hermes inspect local state.
- Let Hermes coordinate development work.
- Summarize Claude/Codex sessions.

Input:

```json
{"type":"agent_prompt","project":"rokid-glasses","text":"..."}
```

Output:

```json
{"type":"agent_start"}
{"type":"agent_delta","text":"..."}
{"type":"agent_done","text":"..."}
```

### 2. Sessions Mode

Purpose:

- List running Claude/Codex/tmux sessions.
- Attach to one session.
- Send follow-up prompts into the session.
- Show recent captured output.

Initial UX:

```text
Sessions
1. claude-hermes-glass-ui    waiting
2. codex-bridge-protocol     running
3. shell-dev                 attached
```

Key bridge messages:

```json
{"type":"session_list"}
{"type":"session_attach","session_id":"claude-hermes-glass-ui"}
{"type":"session_send","session_id":"claude-hermes-glass-ui","text":"continue with keyboard input"}
```

### 3. Codex Mode / Tasks

Codex is better handled as task runner first, not full TUI:

```json
{"type":"codex_start","project":"some-project","text":"fix tests and report"}
{"type":"session_attach","session_id":"codex-some-project-..."}
```

Start with process/tmux capture; later add structured status.

### 4. Shell Mode

Lightweight shell commands only:

```json
{"type":"shell_command","project":"rokid-glasses","command":"git status"}
```

No full vim/nano/curses support in the first version.

## Bridge Responsibilities

Bridge should provide a stable app protocol and keep secrets/tools on the Mac:

1. WebSocket protocol for Rokid app.
2. Hermes API adapter.
3. Claude Code session manager using tmux.
4. Codex task/session manager using tmux/processes.
5. Shell command runner.
6. Workspace/project registry.
7. Device auth before public deployment.
8. Status summarization for small HUD.

## Security Model

Before public/Mac mini deployment:

- Do not put Hermes API key in the Rokid app.
- Add bridge token/device auth.
- Expose only bridge over HTTPS/WSS.
- Keep Hermes API private on Mac mini localhost.
- Restrict workspaces to `~/HermesGlassProjects` and explicitly allowed project dirs.

## Deployment Roadmap

### Phase 1 — Local Dev Console MVP

- Clean UI from debug page into Dev Console display.
- Add bridge `session_list`, `session_attach`, `session_send`.
- Display session list on glasses.
- Send voice/keyboard follow-up into selected Claude tmux session.

### Phase 2 — Claude Session Control

- Create/list/attach Claude tmux sessions.
- Capture recent output.
- Send user prompts via `tmux send-keys`.
- Detect waiting/running heuristically from captured pane.

### Phase 3 — Codex Task Control

- Start Codex tasks in tmux/background.
- Poll/capture output.
- Show completed/running/error status.

### Phase 4 — Better App UX

- Mode switch: Hermes / Sessions / Shell.
- Bluetooth keyboard input.
- Voice command routing.
- Message history rendering.
- Endpoint config instead of hardcoded LAN IP.

### Phase 5 — Mac mini Deployment

- Move bridge to Mac mini.
- Run Hermes API server on Mac mini.
- Add Cloudflare Tunnel/Tailscale Funnel.
- Change app bridge URL to public WSS endpoint.
- Add device auth.

## Immediate Next Implementation

1. Add session manager utilities to bridge.
2. Add WebSocket message types:
   - `session_list`
   - `session_attach`
   - `session_send`
   - `session_start_claude`
   - `session_start_codex`
3. Update Rokid Ink app to understand `session_list` and render sessions.
4. Verify with local WebSocket tests.
5. Package/deploy to Rokid after bridge tests pass.

## Validation

Local bridge tests:

```bash
python3 -m py_compile app/main.py
python3 tests/ws_smoke.py
```

Manual WebSocket test:

```python
import websockets, asyncio, json
# connect ws://127.0.0.1:8765/ws/glass
# send {"type":"session_list"}
```

Rokid validation:

1. App shows `bridge: connected`.
2. Say “列出 sessions”.
3. App sends `session_list`.
4. HUD displays tmux/Claude/Codex sessions.
5. Attach and send follow-up in next increment.
