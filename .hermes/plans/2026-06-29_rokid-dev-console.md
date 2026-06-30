# Rokid Dev Console Implementation Plan

> **For Hermes:** This plan is actionable, but the current user explicitly asked to continue after writing it, so implementation may begin immediately.

**Goal:** Turn the current Rokid Hermes voice test app into a Dev Console that controls Hermes, Claude Code, Codex, and lightweight shell sessions on a Mac/Mac mini.

**Architecture:** Keep a single Rokid app with modes instead of two separate apps. The Rokid app is a thin voice/keyboard/display client; the bridge on Mac/Mac mini owns Hermes API, Claude/Codex/tmux sessions, shell execution, and workspace access.

**Tech Stack:** FastAPI + WebSocket bridge, Python asyncio subprocesses, tmux for interactive Claude/Codex sessions, Rokid Ink app using AIUI `SpeechRecognition`.

---

## Task 1: Document Product Scope

**Objective:** Write the durable product/development plan.

**Files:**
- Create: `docs/DEV_CONSOLE_PLAN.md`
- Create: `.hermes/plans/2026-06-29_rokid-dev-console.md`

**Verification:** Read the docs and confirm they cover Hermes mode, sessions mode, shell mode, bridge responsibilities, security, and Mac mini deployment.

---

## Task 2: Add Bridge Session Manager MVP

**Objective:** Let the bridge list, attach, and send input to tmux sessions for Claude/Codex/shell workflows.

**Files:**
- Modify: `app/main.py`

**Protocol:**

Client → bridge:

```json
{"type":"session_list"}
{"type":"session_attach","session_id":"claude-hermes-glass-ui"}
{"type":"session_send","session_id":"claude-hermes-glass-ui","text":"continue"}
{"type":"session_start_claude","project":"hermes-glass-bridge","session_id":"claude-hermes-glass","text":"continue implementation"}
{"type":"session_start_codex","project":"hermes-glass-bridge","session_id":"codex-bridge","text":"run tests"}
```

Bridge → client:

```json
{"type":"session_list","sessions":[...]}
{"type":"session_attached","session":{...},"output":"..."}
{"type":"session_sent","session_id":"..."}
{"type":"session_output","session_id":"...","output":"..."}
{"type":"session_error","text":"..."}
```

**Verification:** Use a Python websocket client to send `session_list` and expect a JSON response even when no tmux sessions exist.

---

## Task 3: Update Rokid App to Render Sessions

**Objective:** Add Sessions Mode UI and render bridge `session_list` / `session_attached` messages.

**Files:**
- Modify: `rokid-aiui-mic-test/pages/index/index.ink`

**Initial UX:**

```text
Rokid Dev Console
mode: Hermes | Sessions | Shell
bridge: connected
asr: listening

Sessions:
1. claude-foo waiting
2. codex-bar running
```

**Voice routing MVP:**
- If final transcript includes `session`, `sessions`, `列出`, or `會話`, send `session_list`.
- Otherwise route to Hermes mode as before.

**Verification:** App connects to bridge and can display session list when command is triggered.

---

## Task 4: Package and Test on Rokid

**Objective:** Deploy an AIX package and verify bridge/session protocol on device.

**Commands:**

```bash
cd /Users/wickedapp/Projects/hermes-glass-bridge
python3 -m py_compile app/main.py
# package AIX and launch with adb
```

**Verification:**
- `bridge: connected`
- `ASR: listening`
- Saying “列出 sessions” updates the HUD with session list.

---

## Task 5: Next Increment After MVP

**Objective:** Add attach/send controls and Bluetooth keyboard support.

**Planned additions:**
- `/mode sessions`
- `/attach <n>`
- `/send <text>`
- keyboard input box
- Enter send, Esc clear, Ctrl+L clear

---
