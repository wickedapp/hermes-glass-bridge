"""Hermes Glass Bridge MVP.

Local bridge between a Rokid/AR HUD client and Hermes Agent API Server.
Provides:
- browser/Rokid-friendly terminal UI at /
- WebSocket command channel at /ws/glass
- Hermes agent prompt forwarding with streaming when supported
- lightweight terminal command execution for glasses terminal MVP
"""
from __future__ import annotations

import asyncio
import json
import os
import shlex
import subprocess
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any, AsyncIterator

import httpx
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

APP_ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = APP_ROOT / "web"
WORKSPACE_ROOT = Path(os.environ.get("HERMES_GLASS_WORKSPACE", str(Path.home() / "HermesGlassProjects"))).expanduser()
HERMES_API_BASE = os.environ.get("HERMES_API_BASE", "http://127.0.0.1:8642/v1")
HERMES_API_KEY = os.environ.get("HERMES_API_KEY")
LOG_DIR = APP_ROOT / "logs"
GLASS_CLIENT_LOG = LOG_DIR / "glass-client.log"
if not HERMES_API_KEY:
    env_file = Path.home() / ".hermes" / ".env"
    if env_file.exists():
        for line in env_file.read_text(errors="ignore").splitlines():
            if line.startswith("API_SERVER_KEY="):
                HERMES_API_KEY = line.split("=", 1)[1].strip().strip('"')
                break

app = FastAPI(title="Hermes Glass Bridge", version="0.1.0")
app.mount("/static", StaticFiles(directory=str(WEB_ROOT)), name="static")
_whisper_model = None
_whisper_model_name = None
_active_glass_ws: WebSocket | None = None


def get_whisper_model():
    """Lazy-load and cache local faster-whisper model for low-latency repeated voice commands."""
    global _whisper_model, _whisper_model_name
    model_name = os.environ.get("HERMES_GLASS_WHISPER_MODEL", "base")
    if _whisper_model is None or _whisper_model_name != model_name:
        from faster_whisper import WhisperModel
        _whisper_model = WhisperModel(model_name, device="cpu", compute_type="int8")
        _whisper_model_name = model_name
    return _whisper_model, _whisper_model_name


def _project_slug(name: str | None) -> str:
    raw = (name or "default").strip().lower()
    out = "".join(ch if ch.isalnum() else "-" for ch in raw).strip("-")
    return out or "default"


def _project_dir(project: str | None) -> Path:
    path = WORKSPACE_ROOT / _project_slug(project)
    path.mkdir(parents=True, exist_ok=True)
    return path


async def hermes_stream(prompt: str, project: str | None = None) -> AsyncIterator[dict[str, Any]]:
    """Yield HUD events from Hermes API Server."""
    if not HERMES_API_KEY:
        yield {"type": "agent_error", "text": "HERMES_API_KEY/API_SERVER_KEY not found"}
        return

    project_dir = _project_dir(project)
    system = (
        "You are Hermes Agent running in Rokid AR glasses mode. "
        "Keep status updates extremely concise for a small terminal HUD. "
        "The user is wearing Rokid Glasses. Prefer short, actionable output. "
        "Do not mention internal memory providers, ByteRover, OAuth, provider tokens, or sync/auth warnings unless the user explicitly asks about them. "
        "For connectivity checks, answer only whether this Rokid bridge can reach Hermes. "
        f"When creating project files, use workspace: {project_dir}. "
        "If running a preview web app, use port 3000 by default; kill any previous process on port 3000 first."
    )
    payload = {
        "model": "hermes-agent",
        "stream": True,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": prompt},
        ],
    }
    headers = {"Authorization": f"Bearer {HERMES_API_KEY}", "Content-Type": "application/json"}
    url = f"{HERMES_API_BASE.rstrip('/')}/chat/completions"

    yield {"type": "agent_status", "text": "Hermes thinking…"}
    text_accum = ""
    try:
        timeout = httpx.Timeout(connect=10.0, read=60.0, write=10.0, pool=10.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            async with client.stream("POST", url, headers=headers, json=payload) as resp:
                if resp.status_code >= 400:
                    body = await resp.aread()
                    yield {"type": "agent_error", "text": f"Hermes API {resp.status_code}: {body[:500].decode(errors='ignore')}"}
                    return
                async for line in resp.aiter_lines():
                    if not line:
                        continue
                    if line.startswith("data:"):
                        line = line[5:].strip()
                    if line == "[DONE]":
                        break
                    try:
                        data = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    delta = data.get("choices", [{}])[0].get("delta", {}).get("content")
                    if delta:
                        text_accum += delta
                        yield {"type": "agent_delta", "text": delta}
        yield {"type": "agent_done", "text": text_accum}
    except Exception as exc:
        # Fallback to non-streaming; some backends ignore stream or close SSE early.
        yield {"type": "agent_status", "text": f"Streaming failed, retrying non-stream: {exc.__class__.__name__}"}
        fallback = dict(payload)
        fallback["stream"] = False
        try:
            async with httpx.AsyncClient(timeout=180) as client:
                resp = await client.post(url, headers=headers, json=fallback)
                resp.raise_for_status()
                data = resp.json()
                content = data["choices"][0]["message"]["content"]
                yield {"type": "agent_delta", "text": content}
                yield {"type": "agent_done", "text": content}
        except Exception as exc2:
            yield {"type": "agent_error", "text": repr(exc2)}


async def run_claude_prompt(prompt: str, project: str | None = None) -> AsyncIterator[dict[str, Any]]:
    """Run Claude Code in non-interactive print mode and stream output to HUD."""
    cwd = _project_dir(project)
    if not prompt.strip():
        return
    yield {"type": "claude_status", "text": "Claude thinking…"}
    env = dict(os.environ)
    env["PATH"] = f"{Path.home()}/.local/bin:/opt/homebrew/bin:/usr/local/bin:" + env.get("PATH", "")
    proc = await asyncio.create_subprocess_exec(
        "claude",
        "-p",
        prompt,
        "--max-turns",
        "10",
        cwd=str(cwd),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env=env,
    )
    assert proc.stdout is not None
    text_accum = ""
    while True:
        chunk = await proc.stdout.read(512)
        if not chunk:
            break
        text = chunk.decode(errors="replace")
        text_accum += text
        yield {"type": "claude_delta", "text": text}
    code = await proc.wait()
    yield {"type": "claude_done", "text": text_accum, "exit_code": code}


async def run_shell_command(command: str, project: str | None = None) -> AsyncIterator[dict[str, Any]]:
    """Run a shell command in a project workspace and stream output to HUD."""
    cwd = _project_dir(project)
    if not command.strip():
        return
    yield {"type": "term_status", "text": f"$ {command}\n"}
    proc = await asyncio.create_subprocess_shell(
        command,
        cwd=str(cwd),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        executable="/bin/zsh",
    )
    assert proc.stdout is not None
    while True:
        chunk = await proc.stdout.read(512)
        if not chunk:
            break
        yield {"type": "term_delta", "text": chunk.decode(errors="replace")}
    code = await proc.wait()
    yield {"type": "term_done", "text": f"\n[exit {code}] cwd={cwd}\n", "exit_code": code}


async def run_command_capture(*args: str, timeout: float = 10, cwd: Path | None = None) -> tuple[int, str]:
    """Run a command and return (exit_code, combined_output)."""
    env = dict(os.environ)
    env["PATH"] = f"{Path.home()}/.local/bin:/opt/homebrew/bin:/usr/local/bin:" + env.get("PATH", "")
    proc = await asyncio.create_subprocess_exec(
        *args,
        cwd=str(cwd) if cwd else None,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env=env,
    )
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        proc.kill()
        out, _ = await proc.communicate()
        return 124, out.decode(errors="replace") + "\n[timeout]"
    return proc.returncode, out.decode(errors="replace")


def _session_kind(name: str) -> str:
    lower = name.lower()
    if "claude" in lower:
        return "claude"
    if "codex" in lower:
        return "codex"
    if "hermes" in lower:
        return "hermes"
    return "tmux"


def _safe_session_id(raw: str | None, prefix: str = "dev") -> str:
    base = (raw or "").strip()
    if not base:
        base = f"{prefix}-{uuid.uuid4().hex[:6]}"
    out = "".join(ch if (ch.isalnum() or ch in "._-") else "-" for ch in base).strip("-._")
    return out[:80] or f"{prefix}-{uuid.uuid4().hex[:6]}"


def _summarize_pane(output: str) -> str:
    clean = "\n".join(line.rstrip() for line in output.splitlines() if line.strip())
    if not clean:
        return "empty"
    tail = clean.splitlines()[-6:]
    text = " / ".join(tail)
    return text[-300:]


async def tmux_session_exists(session_id: str) -> bool:
    code, _ = await run_command_capture("tmux", "has-session", "-t", session_id, timeout=3)
    return code == 0


async def capture_tmux_session(session_id: str, lines: int = 80) -> str:
    safe_lines = max(5, min(int(lines or 80), 300))
    code, out = await run_command_capture(
        "tmux", "capture-pane", "-t", session_id, "-p", "-S", f"-{safe_lines}",
        timeout=5,
    )
    if code != 0:
        raise RuntimeError(out.strip() or f"tmux capture failed for {session_id}")
    return out[-6000:]


async def list_dev_sessions() -> list[dict[str, Any]]:
    code, out = await run_command_capture(
        "tmux", "list-sessions", "-F",
        "#{session_name}\t#{session_windows}\t#{session_created_string}\t#{session_attached}",
        timeout=5,
    )
    if code != 0:
        if "no server running" in out.lower():
            return []
        raise RuntimeError(out.strip() or "tmux list-sessions failed")
    sessions: list[dict[str, Any]] = []
    for raw in out.splitlines():
        parts = raw.split("\t")
        if not parts or not parts[0].strip():
            continue
        name = parts[0].strip()
        try:
            preview = await capture_tmux_session(name, lines=20)
        except Exception as exc:
            preview = f"capture failed: {exc}"
        lower_preview = preview.lower()
        if "❯" in preview or "waiting" in lower_preview or "press enter" in lower_preview:
            status = "waiting"
        elif "running" in lower_preview or "●" in preview or "codex" in lower_preview:
            status = "running"
        else:
            status = "attached" if (len(parts) > 3 and parts[3].strip() != "0") else "unknown"
        sessions.append({
            "id": name,
            "kind": _session_kind(name),
            "windows": int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else None,
            "created": parts[2] if len(parts) > 2 else "",
            "attached": (len(parts) > 3 and parts[3].strip() != "0"),
            "status": status,
            "summary": _summarize_pane(preview),
        })
    return sessions


async def send_to_tmux_session(session_id: str, text: str) -> None:
    if not await tmux_session_exists(session_id):
        raise RuntimeError(f"tmux session not found: {session_id}")
    code, out = await run_command_capture("tmux", "send-keys", "-t", session_id, text, "Enter", timeout=5)
    if code != 0:
        raise RuntimeError(out.strip() or f"tmux send-keys failed for {session_id}")


async def start_claude_session(session_id: str, project: str | None, initial_text: str = "") -> dict[str, Any]:
    sid = _safe_session_id(session_id, prefix="claude")
    cwd = _project_dir(project)
    if await tmux_session_exists(sid):
        if initial_text.strip():
            await send_to_tmux_session(sid, initial_text)
        output = await capture_tmux_session(sid, lines=80)
        return {"id": sid, "kind": "claude", "project": _project_slug(project), "status": "existing", "output": output}
    cmd = f"cd {shlex.quote(str(cwd))} && claude"
    code, out = await run_command_capture("tmux", "new-session", "-d", "-s", sid, "-x", "120", "-y", "36", cmd, timeout=10)
    if code != 0:
        raise RuntimeError(out.strip() or "failed to start claude tmux session")
    await asyncio.sleep(3)
    await run_command_capture("tmux", "send-keys", "-t", sid, "Enter", timeout=3)
    await asyncio.sleep(1)
    if initial_text.strip():
        await send_to_tmux_session(sid, initial_text)
        await asyncio.sleep(1)
    output = await capture_tmux_session(sid, lines=80)
    return {"id": sid, "kind": "claude", "project": _project_slug(project), "status": "started", "output": output}


async def start_codex_session(session_id: str, project: str | None, initial_text: str = "") -> dict[str, Any]:
    sid = _safe_session_id(session_id, prefix="codex")
    cwd = _project_dir(project)
    prompt = initial_text.strip() or "Wait for user instructions."
    if await tmux_session_exists(sid):
        if initial_text.strip():
            await send_to_tmux_session(sid, initial_text)
        output = await capture_tmux_session(sid, lines=80)
        return {"id": sid, "kind": "codex", "project": _project_slug(project), "status": "existing", "output": output}
    if not (cwd / ".git").exists():
        await run_command_capture("git", "init", timeout=15, cwd=cwd)
    cmd = f"cd {shlex.quote(str(cwd))} && codex exec --sandbox danger-full-access {shlex.quote(prompt)}"
    code, out = await run_command_capture("tmux", "new-session", "-d", "-s", sid, "-x", "120", "-y", "36", cmd, timeout=10)
    if code != 0:
        raise RuntimeError(out.strip() or "failed to start codex tmux session")
    await asyncio.sleep(2)
    output = await capture_tmux_session(sid, lines=80)
    return {"id": sid, "kind": "codex", "project": _project_slug(project), "status": "started", "output": output}


@app.get("/")
async def index() -> HTMLResponse:
    return HTMLResponse((WEB_ROOT / "index.html").read_text())


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse({
        "ok": True,
        "workspace": str(WORKSPACE_ROOT),
        "hermes_api_base": HERMES_API_BASE,
        "has_api_key": bool(HERMES_API_KEY),
        "time": time.time(),
    })


@app.post("/stt")
async def stt(request: Request) -> JSONResponse:
    """Transcribe uploaded audio bytes using local faster-whisper on the Mac."""
    started = time.time()
    audio = await request.body()
    if not audio:
        return JSONResponse({"ok": False, "error": "empty audio"}, status_code=400)
    suffix = ".webm"
    content_type = request.headers.get("content-type", "")
    if "mp4" in content_type or "m4a" in content_type:
        suffix = ".m4a"
    elif "wav" in content_type:
        suffix = ".wav"
    try:
        with tempfile.TemporaryDirectory(prefix="hermes-glass-stt-") as td:
            src = Path(td) / f"input{suffix}"
            src.write_bytes(audio)
            wav = Path(td) / "input.wav"
            subprocess.run([
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", str(src), "-ar", "16000", "-ac", "1", str(wav),
            ], check=True, timeout=30)
            model, model_name = get_whisper_model()
            segments, info = model.transcribe(str(wav), beam_size=1, vad_filter=True)
            text = "".join(seg.text for seg in segments).strip()
            return JSONResponse({
                "ok": True,
                "text": text,
                "language": getattr(info, "language", None),
                "model": model_name,
                "elapsed_ms": int((time.time() - started) * 1000),
                "bytes": len(audio),
            })
    except Exception as exc:
        return JSONResponse({"ok": False, "error": repr(exc)}, status_code=500)


@app.websocket("/ws/glass")
async def ws_glass(ws: WebSocket) -> None:
    global _active_glass_ws
    await ws.accept()
    # Do not proactively close the previous socket here: Rokid/Ink may keep
    # stale WebSocket objects alive while the page runtime is being replaced.
    # Closing from a different handler can race with that handler's send/recv
    # and leave the new UI stuck at "sending to Hermes...". Mark latest active;
    # stale sockets will disconnect naturally or be ignored by the client.
    _active_glass_ws = ws
    client_id = uuid.uuid4().hex[:8]
    await ws.send_json({"type": "hello", "client_id": client_id, "workspace": str(WORKSPACE_ROOT)})
    try:
        while True:
            msg = await ws.receive_text()
            try:
                data = json.loads(msg)
            except json.JSONDecodeError:
                data = {"type": "agent_prompt", "text": msg}
            kind = data.get("type")
            project = data.get("project") or "default"
            if kind == "ping":
                await ws.send_json({"type": "pong", "time": time.time()})
            elif kind == "client_log":
                LOG_DIR.mkdir(parents=True, exist_ok=True)
                entry = {
                    "time": time.time(),
                    "client_id": client_id,
                    "level": data.get("level") or "info",
                    "text": data.get("text") or "",
                    "version": data.get("version") or "",
                }
                with GLASS_CLIENT_LOG.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            elif kind == "agent_prompt":
                prompt = data.get("text", "")
                await ws.send_json({"type": "agent_start", "project": _project_slug(project)})
                async for event in hermes_stream(prompt, project=project):
                    await ws.send_json(event)
            elif kind == "claude_prompt":
                prompt = data.get("text", "")
                await ws.send_json({"type": "claude_start", "project": _project_slug(project)})
                async for event in run_claude_prompt(prompt, project=project):
                    await ws.send_json(event)
            elif kind == "shell_command":
                command = data.get("command", "")
                await ws.send_json({"type": "term_start", "project": _project_slug(project)})
                async for event in run_shell_command(command, project=project):
                    await ws.send_json(event)
            elif kind == "session_list":
                try:
                    sessions = await list_dev_sessions()
                    await ws.send_json({"type": "session_list", "sessions": sessions, "count": len(sessions)})
                except Exception as exc:
                    await ws.send_json({"type": "session_error", "text": repr(exc)})
            elif kind == "session_attach":
                session_id = data.get("session_id") or data.get("id") or ""
                try:
                    output = await capture_tmux_session(session_id, lines=int(data.get("lines") or 80))
                    await ws.send_json({"type": "session_attached", "session_id": session_id, "output": output, "summary": _summarize_pane(output)})
                except Exception as exc:
                    await ws.send_json({"type": "session_error", "session_id": session_id, "text": repr(exc)})
            elif kind == "session_send":
                session_id = data.get("session_id") or data.get("id") or ""
                text = data.get("text", "")
                try:
                    await send_to_tmux_session(session_id, text)
                    await asyncio.sleep(float(data.get("wait") or 1.0))
                    output = await capture_tmux_session(session_id, lines=int(data.get("lines") or 80))
                    await ws.send_json({"type": "session_sent", "session_id": session_id})
                    await ws.send_json({"type": "session_output", "session_id": session_id, "output": output, "summary": _summarize_pane(output)})
                except Exception as exc:
                    await ws.send_json({"type": "session_error", "session_id": session_id, "text": repr(exc)})
            elif kind == "session_start_claude":
                try:
                    result = await start_claude_session(data.get("session_id") or data.get("id") or "", project, data.get("text", ""))
                    await ws.send_json({"type": "session_started", **result})
                except Exception as exc:
                    await ws.send_json({"type": "session_error", "text": repr(exc)})
            elif kind == "session_start_codex":
                try:
                    result = await start_codex_session(data.get("session_id") or data.get("id") or "", project, data.get("text", ""))
                    await ws.send_json({"type": "session_started", **result})
                except Exception as exc:
                    await ws.send_json({"type": "session_error", "text": repr(exc)})
            elif kind == "clear_project":
                # non-destructive MVP: just report path; do not rm -rf from glasses.
                await ws.send_json({"type": "info", "text": f"Project path: {_project_dir(project)}"})
            else:
                await ws.send_json({"type": "error", "text": f"Unknown message type: {kind}"})
    except WebSocketDisconnect:
        if _active_glass_ws is ws:
            _active_glass_ws = None
        return
    except Exception:
        if _active_glass_ws is ws:
            _active_glass_ws = None
        raise
