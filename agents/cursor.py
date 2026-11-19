#!/usr/bin/env python3
"""Comagic Mobile bridge for Cursor Agent CLI.

Exposes the same HTTP/SSE API as agents/comagic.py, backed by
`agent -p --output-format stream-json` instead of `comagic run`.

Requires the Cursor CLI (`agent` / `cursor-agent`) on PATH, and either
`agent login` or CURSOR_API_KEY. Only Python 3 standard library is used.
"""

from __future__ import annotations

import argparse
import atexit
import base64
import hmac
import json
import os
import queue
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DEFAULT_AGENT = shutil.which("agent") or shutil.which("cursor-agent") or str(
    Path.home() / ".local" / "bin" / "agent"
)
DEFAULT_USERNAME = "comagic"
DEFAULT_PASSWORD = "YFl4cVaQ8smozi4I"
AGENT_BIN = os.environ.get("CURSOR_AGENT_BIN") or DEFAULT_AGENT
STATE_PATH = Path(
    os.environ.get(
        "CURSOR_MOBILE_STATE",
        str(Path.home() / ".comagic-mobile" / "cursor-state.json"),
    )
).expanduser()
RUN_FORCE = os.environ.get("CURSOR_RUN_FORCE", "1") != "0"
# 长时间无输出视为卡死，主动结束并通知客户端，避免会话一直 busy（409）。
RUN_IDLE_TIMEOUT = float(os.environ.get("CURSOR_RUN_IDLE_TIMEOUT", "180"))
RUN_MAX_SECONDS = float(os.environ.get("CURSOR_RUN_TIMEOUT", "1800"))
DEFAULT_PORT = 4909

AGENTS = [
    {
        "id": "agent",
        "description": "默认模式，可以读写文件并执行命令",
        "mode": "primary",
        "hidden": False,
    },
    {
        "id": "plan",
        "description": "规划模式，只读分析，不修改文件",
        "mode": "primary",
        "hidden": False,
    },
    {
        "id": "ask",
        "description": "问答模式，只读解释与答疑",
        "mode": "primary",
        "hidden": False,
    },
]


def git_commit_count() -> str:
    repo = Path(__file__).resolve().parent.parent
    try:
        result = subprocess.run(
            ["git", "-C", str(repo), "rev-list", "--count", "HEAD"],
            capture_output=True,
            text=True,
            timeout=3,
        )
        count = result.stdout.strip()
        if result.returncode == 0 and count.isdigit():
            return count
    except Exception:
        pass
    return "unknown"


VERSION = git_commit_count()


def log(message: str) -> None:
    print(f"[cursor-server] {message}", flush=True)


def now_ms() -> int:
    return int(time.time() * 1000)


class State:
    """Persistent session map + message history for the mobile client."""

    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()
        self.data: dict[str, dict] = {"sessions": {}}
        self._load()

    def _load(self) -> None:
        try:
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
            self.data.setdefault("sessions", {})
        except FileNotFoundError:
            return
        except Exception as error:
            log(f"读取状态文件失败，忽略: {error}")

    def _save_locked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.path)

    def get(self, ext: str) -> dict | None:
        with self.lock:
            meta = self.data["sessions"].get(ext)
            return dict(meta) if meta else None

    def put(self, ext: str, touch: bool = True, **fields) -> dict:
        with self.lock:
            meta = self.data["sessions"].setdefault(ext, {})
            meta.update(fields)
            meta.setdefault("created", now_ms())
            meta.setdefault("messages", [])
            if touch or "updated" not in meta:
                meta["updated"] = now_ms()
            self._save_locked()
            return dict(meta)

    def delete(self, ext: str) -> None:
        with self.lock:
            self.data["sessions"].pop(ext, None)
            self._save_locked()

    def all(self) -> list[tuple[str, dict]]:
        with self.lock:
            return [(ext, dict(meta)) for ext, meta in self.data["sessions"].items()]

    def append_message(self, ext: str, message: dict) -> None:
        with self.lock:
            meta = self.data["sessions"].setdefault(ext, {})
            messages = meta.setdefault("messages", [])
            messages.append(message)
            meta["updated"] = now_ms()
            self._save_locked()

    def messages(self, ext: str) -> list[dict]:
        with self.lock:
            meta = self.data["sessions"].get(ext) or {}
            return list(meta.get("messages") or [])


class EventHub:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.subscribers: set[queue.Queue] = set()
        self.counter = 0

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=2000)
        with self.lock:
            self.subscribers.add(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self.lock:
            self.subscribers.discard(q)

    def publish(self, event_type: str, properties: dict) -> None:
        with self.lock:
            self.counter += 1
            event = {
                "id": f"evt_{self.counter}",
                "type": event_type,
                "properties": properties,
            }
        payload = json.dumps(event, ensure_ascii=False)
        with self.lock:
            subscribers = list(self.subscribers)
        for q in subscribers:
            try:
                q.put_nowait(payload)
            except queue.Full:
                pass


def tool_name_from_payload(tool_call: dict) -> str:
    for key in tool_call:
        if key.endswith("ToolCall") and isinstance(tool_call.get(key), dict):
            return key[: -len("ToolCall")].lower() or "tool"
    return "tool"


def tool_detail(tool_call: dict) -> tuple[str, str, str]:
    """Return (name, title, output) from a Cursor tool_call payload."""
    name = tool_name_from_payload(tool_call)
    title = ""
    output = ""
    for key, value in tool_call.items():
        if not (key.endswith("ToolCall") and isinstance(value, dict)):
            continue
        args = value.get("args") or {}
        result = value.get("result")
        title = (
            str(args.get("description") or "")
            or str(args.get("command") or "")
            or str(args.get("path") or args.get("filePath") or args.get("targetDirectory") or "")
            or name
        )
        if isinstance(result, dict):
            success = result.get("success")
            error = result.get("error")
            if isinstance(success, dict):
                output = str(
                    success.get("interleavedOutput")
                    or success.get("stdout")
                    or success.get("content")
                    or json.dumps(success, ensure_ascii=False)
                )[:4000]
            elif isinstance(error, dict):
                output = str(error.get("message") or json.dumps(error, ensure_ascii=False))[:4000]
            elif success is not None:
                output = str(success)[:4000]
        break
    return name, title, output


def list_models() -> list[dict]:
    proc = subprocess.run(
        [AGENT_BIN, "--list-models"],
        capture_output=True,
        text=True,
        timeout=60,
    )
    output = proc.stdout or ""
    models: list[dict] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.lower().startswith("available"):
            continue
        # "id - Display Name" or "id - Display Name (current, default)"
        match = re.match(r"^(\S+)\s+-\s+(.+)$", line)
        if not match:
            continue
        model_id, name = match.group(1), match.group(2)
        # "id - Display Name (current, default)" 标记了 CLI 当前默认模型
        is_default = bool(re.search(r"\((?:[^)]*\b(?:current|default)\b[^)]*)\)", name))
        name = re.sub(r"\s*\(.*?\)\s*$", "", name).strip() or model_id
        models.append(
            {
                "id": model_id,
                "providerID": "cursor",
                "name": name,
                "status": "active",
                "enabled": True,
                "default": is_default,
            }
        )
    return models


def pick_default_agent() -> str:
    """默认会话类型：第一个可见的 primary 模式。"""
    for agent in AGENTS:
        if agent.get("mode") == "primary" and not agent.get("hidden"):
            return str(agent.get("id"))
    return "agent"


def pick_default_model(models: list[dict]) -> dict | None:
    """CLI 标记的当前默认模型优先，其次 deepseek，最后第一个可用模型。"""
    if not models:
        return None
    preferred = (
        next((item for item in models if item.get("default")), None)
        or next(
            (
                item
                for item in models
                if any(
                    "deepseek" in str(item.get(key) or "").lower()
                    for key in ("id", "providerID", "name")
                )
            ),
            None,
        )
        or models[0]
    )
    return {"id": preferred.get("id"), "providerID": preferred.get("providerID")}


def session_ref(ext: str, meta: dict) -> dict:
    created = meta.get("created") or now_ms()
    updated = meta.get("updated") or created
    return {
        "id": ext,
        "title": meta.get("title") or "新会话",
        "agent": meta.get("agent") or "agent",
        "model": meta.get("model"),
        "time": {"created": created, "updated": updated},
        "location": {"directory": meta.get("directory") or ""},
        "subpath": "",
    }


def history_message(role: str, text: str, msg_id: str | None = None) -> dict:
    mid = msg_id or ("msg_" + uuid.uuid4().hex[:16])
    return {
        "info": {
            "id": mid,
            "role": role,
            "time": {"created": now_ms()},
        },
        "parts": [{"type": "text", "text": text}],
    }


class RunManager:
    def __init__(self, state: State, hub: EventHub):
        self.state = state
        self.hub = hub
        self.lock = threading.Lock()
        self.runs: dict[str, subprocess.Popen] = {}

    def running(self, ext: str) -> bool:
        with self.lock:
            proc = self.runs.get(ext)
            return proc is not None and proc.poll() is None

    def interrupt(self, ext: str) -> None:
        with self.lock:
            proc = self.runs.get(ext)
        if not proc or proc.poll() is not None:
            return
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
        except Exception:
            try:
                proc.terminate()
            except Exception:
                pass

    def stop_all(self) -> None:
        with self.lock:
            procs = list(self.runs.values())
        for proc in procs:
            if proc.poll() is None:
                try:
                    os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
                except Exception:
                    pass

    def start(self, ext: str, meta: dict, text: str, files: list[str]) -> None:
        directory = meta.get("directory") or str(Path.cwd())
        real = meta.get("real")
        agent_mode = (meta.get("agent") or "agent").lower()
        model = meta.get("model") or {}

        prompt_bits = [text] if text.strip() else []
        for path in files:
            prompt_bits.append(f"@{path}")
        prompt = "\n".join(prompt_bits) or "请查看附件"

        cmd = [
            AGENT_BIN,
            "-p",
            "--output-format",
            "stream-json",
            "--stream-partial-output",
            "--trust",
            "--workspace",
            directory,
        ]
        if RUN_FORCE:
            cmd.append("--force")
        if real:
            cmd += ["--resume", str(real)]
        if agent_mode in ("plan", "ask"):
            cmd += ["--mode", agent_mode]
        model_id = model.get("id") if isinstance(model, dict) else None
        if model_id and model_id != "auto":
            cmd += ["--model", str(model_id)]
        cmd.append(prompt)

        title = text.strip().replace("\n", " ")[:40]
        if title and not meta.get("title"):
            self.state.put(ext, title=title)

        user_msg = history_message("user", text or "（附件）")
        self.state.append_message(ext, user_msg)

        log(f"run {ext}: {AGENT_BIN} {' '.join(cmd[1:-1])} <prompt>")
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
                text=True,
                bufsize=1,
                cwd=directory,
                start_new_session=True,
            )
        except Exception as error:
            self.hub.publish("session.error", {"sessionID": ext, "message": str(error)})
            self.hub.publish("session.idle", {"sessionID": ext})
            return

        with self.lock:
            self.runs[ext] = proc

        threading.Thread(
            target=self._pump, args=(ext, proc, files), daemon=True
        ).start()

    def _pump(self, ext: str, proc: subprocess.Popen, files: list[str]) -> None:
        stderr_tail: deque[str] = deque(maxlen=30)
        assistant_chunks: list[str] = []
        finished = False
        result_text = ""
        result_error = False

        def read_stderr() -> None:
            assert proc.stderr is not None
            for line in proc.stderr:
                stderr_tail.append(line.rstrip())
                if len(stderr_tail) <= 5:
                    log(f"stderr {ext}: {line.rstrip()[:300]}")

        def finalize(code: int | None = None) -> None:
            nonlocal finished
            if finished:
                return
            finished = True
            with self.lock:
                if self.runs.get(ext) is proc:
                    self.runs.pop(ext, None)
            for path in files:
                try:
                    os.unlink(path)
                except Exception:
                    pass
            final_text = "".join(assistant_chunks).strip() or result_text.strip()
            if final_text:
                self.state.append_message(ext, history_message("assistant", final_text))
            if result_error or (code is not None and code != 0 and not (code < 0)):
                message = (
                    result_text
                    or "\n".join(list(stderr_tail)[-5:])
                    or f"cursor agent 退出码 {code}"
                )
                self.hub.publish("session.error", {"sessionID": ext, "message": str(message)})
            self.hub.publish("session.idle", {"sessionID": ext})

        err_thread = threading.Thread(target=read_stderr, daemon=True)
        err_thread.start()

        assert proc.stdout is not None
        lines: queue.Queue[str | None] = queue.Queue()
        started = time.time()
        last_output = started

        def read_stdout() -> None:
            assert proc.stdout is not None
            for line in proc.stdout:
                lines.put(line)
            lines.put(None)

        threading.Thread(target=read_stdout, daemon=True).start()

        while True:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                idle = time.time() - last_output
                total = time.time() - started
                if idle > RUN_IDLE_TIMEOUT or total > RUN_MAX_SECONDS:
                    reason = (
                        f"cursor agent 无响应（{int(idle)} 秒无输出），已终止"
                        if idle > RUN_IDLE_TIMEOUT
                        else f"cursor agent 超时（{int(total)} 秒），已终止"
                    )
                    log(f"{ext}: {reason}")
                    try:
                        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
                    except Exception:
                        try:
                            proc.kill()
                        except Exception:
                            pass
                    self.hub.publish("session.error", {"sessionID": ext, "message": reason})
                    finalize()
                    return
                if proc.poll() is not None:
                    # 进程已退出但队列尚未收到 EOF，再等一次
                    try:
                        line = lines.get(timeout=1)
                    except queue.Empty:
                        break
                else:
                    continue
            if line is None:
                break
            last_output = time.time()
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except Exception:
                continue
            self._handle_event(ext, event, assistant_chunks)
            if event.get("type") == "result":
                result_error = bool(event.get("is_error"))
                result_text = str(event.get("result") or "")
                # Terminal event: finish for the mobile client even if the CLI
                # process lingers (observed hang after stream-json result).
                finalize(1 if result_error else 0)
                threading.Thread(target=self._reap, args=(proc,), daemon=True).start()
                err_thread.join(timeout=1)
                return

        code = proc.wait()
        err_thread.join(timeout=2)
        finalize(code)

    def _reap(self, proc: subprocess.Popen) -> None:
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
            except Exception:
                try:
                    proc.kill()
                except Exception:
                    pass
            try:
                proc.wait(timeout=5)
            except Exception:
                pass

    def _handle_event(self, ext: str, event: dict, assistant_chunks: list[str]) -> None:
        sid = event.get("session_id")
        meta = self.state.get(ext) or {}
        if sid and not meta.get("real"):
            self.state.put(ext, real=sid)

        kind = event.get("type")
        subtype = event.get("subtype")

        if kind == "system" and subtype == "init":
            model_name = event.get("model")
            if model_name and not (meta.get("model") or {}).get("id"):
                self.state.put(
                    ext,
                    model={"id": "auto", "providerID": "cursor", "name": model_name},
                )
            return

        if kind == "assistant":
            # With --stream-partial-output: only timestamped deltas without
            # model_call_id carry new text. Other assistant events are duplicates.
            if not event.get("timestamp_ms") or event.get("model_call_id"):
                return
            message = event.get("message") or {}
            for block in message.get("content") or []:
                if block.get("type") == "text":
                    delta = block.get("text") or ""
                    if delta:
                        assistant_chunks.append(delta)
                        self.hub.publish(
                            "session.text.delta",
                            {"sessionID": ext, "delta": delta},
                        )
            return

        if kind == "tool_call":
            tool_call = event.get("tool_call") or {}
            name, title, output = tool_detail(tool_call)
            call_id = event.get("call_id") or tool_call.get("toolCallId") or ""
            payload = {
                "sessionID": ext,
                "toolName": name,
                "toolID": call_id,
                "title": title,
                "output": output if subtype == "completed" else "",
            }
            if subtype == "completed":
                result = None
                for key, value in tool_call.items():
                    if key.endswith("ToolCall") and isinstance(value, dict):
                        result = value.get("result")
                        break
                if isinstance(result, dict) and result.get("error"):
                    payload["message"] = str(
                        (result.get("error") or {}).get("message")
                        if isinstance(result.get("error"), dict)
                        else result.get("error")
                    )
                    self.hub.publish("session.tool.failed", payload)
                else:
                    self.hub.publish("session.tool.called", payload)
            else:
                self.hub.publish("session.tool.called", payload)
            return

        if kind == "result" and event.get("is_error"):
            message = event.get("result") or event.get("error") or "cursor agent 出错"
            self.hub.publish("session.error", {"sessionID": ext, "message": str(message)})
            return

        if kind in ("error", "session_error"):
            message = event.get("message") or (event.get("error") or {}).get("message") or "cursor agent 出错"
            self.hub.publish("session.error", {"sessionID": ext, "message": message})


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CursorMobileBridge/0.1"

    state: State
    hub: EventHub
    runs: RunManager
    password: str
    username: str
    default_directory: str
    cli_lock = threading.Lock()

    def log_message(self, format: str, *args) -> None:  # noqa: A002
        log("%s - %s" % (self.address_string(), format % args))

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            raw = base64.b64decode(header[6:]).decode("utf-8", "replace")
            user, _, password = raw.partition(":")
        except Exception:
            return False
        return hmac.compare_digest(user, self.username) and hmac.compare_digest(password, self.password)

    def _send_json(self, status: int, payload: object, headers: dict[str, str] | None = None) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def _send_empty(self, status: int = 204) -> None:
        self.send_response(status)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    def _path_parts(self) -> tuple[str, list[str], dict[str, list[str]]]:
        parsed = urllib.parse.urlsplit(self.path)
        parts = [urllib.parse.unquote(p) for p in parsed.path.split("/") if p]
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        return parsed.path, parts, query

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        if not self._authorized():
            self._send_json(401, {"error": "unauthorized"})
            return
        path, parts, query = self._path_parts()

        if path == "/global/health":
            self._send_json(200, {"healthy": True, "version": VERSION, "type": "cursor"})
            return
        if path == "/event":
            self._serve_events()
            return
        if path == "/api/session":
            self._list_sessions(query)
            return
        if path in ("/api/agent", "/agent"):
            self._send_json(200, {"data": AGENTS})
            return
        if path == "/config/providers":
            self._list_models()
            return
        if path == "/path":
            self._paths(query)
            return
        if path == "/api/fs/list":
            self._fs_list(query)
            return
        if len(parts) == 3 and parts[0] == "session" and parts[2] == "message":
            self._history(parts[1], query)
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if not self._authorized():
            self._send_json(401, {"error": "unauthorized"})
            return
        path, parts, _ = self._path_parts()

        if path == "/api/session":
            self._create_session()
            return
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "session" and parts[3] == "interrupt":
            self.runs.interrupt(parts[2])
            self._send_empty()
            return
        if len(parts) == 3 and parts[0] == "session" and parts[2] == "abort":
            self.runs.interrupt(parts[1])
            self._send_empty()
            return
        if len(parts) == 3 and parts[0] == "session" and parts[2] == "message":
            self._prompt(parts[1])
            return
        if len(parts) == 6 and parts[0] == "api" and parts[1] == "session" and parts[3] == "permission" and parts[5] == "reply":
            self._send_empty()
            return
        if len(parts) == 5 and parts[0] == "session" and parts[2] == "permission" and parts[4] == "reply":
            self._send_empty()
            return
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "session" and parts[3] == "agent":
            body = self._read_json()
            agent = str(body.get("agent") or "agent")
            meta = self.state.get(parts[2]) or {"directory": self.default_directory}
            self.state.put(parts[2], **{**meta, "agent": agent})
            self._send_empty()
            return
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "session" and parts[3] == "model":
            body = self._read_json()
            model = body.get("model") or {}
            if model.get("id"):
                meta = self.state.get(parts[2]) or {"directory": self.default_directory}
                normalized = {
                    "id": model.get("id"),
                    "providerID": model.get("providerID") or "cursor",
                    "variant": model.get("variant"),
                }
                self.state.put(parts[2], **{**meta, "model": normalized})
            self._send_empty()
            return
        self._send_json(404, {"error": "not found"})

    def do_DELETE(self) -> None:
        if not self._authorized():
            self._send_json(401, {"error": "unauthorized"})
            return
        _, parts, _ = self._path_parts()
        if len(parts) == 3 and parts[0] == "api" and parts[1] == "session":
            ext = parts[2]
            self.runs.interrupt(ext)
            self.state.delete(ext)
            self._send_empty(200)
            return
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "session" and parts[2] == "message":
            self._send_empty()
            return
        self._send_json(404, {"error": "not found"})

    def _list_sessions(self, query: dict[str, list[str]]) -> None:
        limit = 50
        try:
            limit = int((query.get("limit") or ["50"])[0])
        except Exception:
            pass
        sessions = [session_ref(ext, meta) for ext, meta in self.state.all()]
        sessions.sort(key=lambda s: s["time"]["updated"], reverse=True)
        self._send_json(200, {"data": sessions[:limit]})

    def _create_session(self) -> None:
        body = self._read_json()
        ext = "ses_cursor_" + uuid.uuid4().hex[:16]
        location = body.get("location") or {}
        directory = location.get("directory") or self.default_directory
        model = body.get("model")
        if isinstance(model, dict) and model.get("id"):
            model = {
                "id": model.get("id"),
                "providerID": model.get("providerID") or "cursor",
                "variant": model.get("variant"),
            }
        else:
            model = {"id": "auto", "providerID": "cursor"}
        meta = self.state.put(
            ext,
            real=None,
            title="新会话",
            agent=body.get("agent") or "agent",
            model=model,
            directory=directory,
            messages=[],
            created=now_ms(),
        )
        self._send_json(200, {"data": session_ref(ext, meta)})

    def _history(self, ext: str, query: dict[str, list[str]] | None = None) -> None:
        query = query or {}
        messages = self.state.messages(ext)
        limit = int((query.get("limit") or ["20"])[0] or 20)
        before = (query.get("before") or [""])[0]
        end = len(messages)
        if before:
            for index, message in enumerate(messages):
                if (message.get("info") or {}).get("id") == before:
                    end = index
                    break
        start = max(end - limit, 0) if limit > 0 else 0
        window = messages[start:end]
        self._send_json(
            200,
            window,
            headers={"X-History-Has-More": "1" if start > 0 else "0"},
        )

    def _prompt(self, ext: str) -> None:
        body = self._read_json()
        meta = self.state.get(ext)
        if meta is None:
            meta = {
                "real": None,
                "directory": self.default_directory,
                "agent": "agent",
                "model": {"id": "auto", "providerID": "cursor"},
            }
            self.state.put(ext, **meta)

        if self.runs.running(ext):
            self._send_json(409, {"error": "session is busy"})
            return

        text_parts: list[str] = []
        files: list[str] = []
        for part in body.get("parts") or []:
            kind = part.get("type")
            if kind == "text":
                text_parts.append(str(part.get("text") or ""))
            elif kind == "file" and str(part.get("mime") or "").startswith("image/"):
                url = str(part.get("url") or "")
                if url.startswith("data:") and "," in url:
                    try:
                        raw = base64.b64decode(url.split(",", 1)[1])
                        suffix = "." + (str(part.get("mime") or "image/jpeg").split("/")[-1])
                        fd, path = tempfile.mkstemp(prefix="cursor-image-", suffix=suffix)
                        with os.fdopen(fd, "wb") as handle:
                            handle.write(raw)
                        files.append(path)
                    except Exception as error:
                        log(f"image decode failed: {error}")

        text = "\n".join(t for t in text_parts if t.strip())
        self.runs.start(ext, meta, text, files)
        self._send_empty()

    def _paths(self, query: dict[str, list[str]]) -> None:
        directory = (query.get("directory") or [""])[0] or self.default_directory
        directory = os.path.abspath(os.path.expanduser(directory))
        home = str(Path.home())
        self._send_json(
            200,
            {
                "home": home,
                "state": str(Path.home() / ".comagic-mobile"),
                "config": str(Path.home() / ".cursor"),
                "worktree": directory,
                "directory": directory,
            },
        )

    def _fs_list(self, query: dict[str, list[str]]) -> None:
        base = (query.get("location[directory]") or [""])[0] or self.default_directory
        target = (query.get("path") or [""])[0] or base
        base = os.path.abspath(os.path.expanduser(base))
        if not os.path.isabs(target):
            target = os.path.join(base, target)
        target = os.path.abspath(os.path.expanduser(target))
        if not os.path.isdir(target):
            self._send_json(400, {"error": f"not a directory: {target}"})
            return
        entries = []
        try:
            with os.scandir(target) as iterator:
                for entry in iterator:
                    try:
                        is_dir = entry.is_dir()
                    except OSError:
                        is_dir = False
                    entries.append(
                        {"path": entry.path, "type": "directory" if is_dir else "file"}
                    )
        except OSError as error:
            self._send_json(400, {"error": str(error)})
            return
        entries.sort(key=lambda item: (item["type"] != "directory", item["path"].lower()))
        self._send_json(200, {"location": {"directory": base}, "data": entries})

    def _list_models(self) -> None:
        try:
            with self.cli_lock:
                models = list_models()
        except Exception as error:
            log(f"models failed: {error}")
            models = [{"id": "auto", "providerID": "cursor", "name": "Auto", "status": "active", "enabled": True}]
        providers: dict[str, dict] = {}
        for model in models:
            provider = providers.setdefault(
                model["providerID"],
                {"id": model["providerID"], "name": model["providerID"], "models": {}},
            )
            provider["models"][model["id"]] = model
        default = {"providerID": "cursor", "modelID": "auto"} if models else {}
        self._send_json(200, {"providers": list(providers.values()), "default": default})

    def _serve_events(self) -> None:
        q = self.hub.subscribe()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.close_connection = True
        try:
            while True:
                try:
                    payload = q.get(timeout=15)
                except queue.Empty:
                    self._chunk(b": ping\n\n")
                    continue
                self._chunk(f"data: {payload}\n\n".encode("utf-8"))
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            self.hub.unsubscribe(q)
            try:
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except Exception:
                pass

    def _chunk(self, data: bytes) -> None:
        self.wfile.write(f"{len(data):X}\r\n".encode("ascii") + data + b"\r\n")
        self.wfile.flush()


def make_server(host: str, port: int, password: str, username: str, directory: str) -> ThreadingHTTPServer:
    state = State(STATE_PATH)
    hub = EventHub()
    runs = RunManager(state, hub)

    handler = type(
        "BoundHandler",
        (Handler,),
        {
            "state": state,
            "hub": hub,
            "runs": runs,
            "password": password,
            "username": username,
            "default_directory": directory,
        },
    )
    httpd = ThreadingHTTPServer((host, port), handler)
    httpd.daemon_threads = True
    httpd.runs = runs  # type: ignore[attr-defined]
    return httpd


def lan_ip() -> str:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        return ip
    except Exception:
        return "127.0.0.1"


def start_registry_reporter(
    registry_url: str,
    registry_username: str,
    registry_password: str,
    server_url: str,
    username: str,
    password: str,
    directory: str,
    name: str,
) -> None:
    base = registry_url.rstrip("/")
    token = base64.b64encode(f"{registry_username}:{registry_password}".encode()).decode()
    state: dict[str, str | None] = {"id": None}

    def defaults() -> dict:
        """本 agent 的默认会话类型与默认模型，注册时上报给注册中心。"""
        try:
            models = list_models()
        except Exception as error:
            log(f"registry: 获取模型列表失败: {error}")
            models = []
        return {"defaultAgent": pick_default_agent(), "defaultModel": pick_default_model(models)}

    def call(method: str, path: str, payload: dict | None = None) -> tuple[int, bytes]:
        request = urllib.request.Request(
            base + path,
            method=method,
            data=json.dumps(payload).encode("utf-8") if payload is not None else None,
            headers={
                "Authorization": f"Basic {token}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read()

    def register() -> None:
        _, raw = call(
            "POST",
            "/api/register",
            {
                "name": name,
                "type": "cursor",
                "url": server_url,
                "username": username,
                "password": password,
                "directory": directory,
                "version": VERSION,
                **defaults(),
            },
        )
        state["id"] = json.loads(raw).get("id")
        log(f"registry: registered as {state['id']} ({server_url})")

    def loop() -> None:
        while True:
            try:
                server_id = state.get("id")
                if not server_id:
                    register()
                else:
                    try:
                        call("POST", f"/api/agents/{server_id}/heartbeat")
                    except urllib.error.HTTPError as error:
                        if error.code == 404:
                            state["id"] = None
                        else:
                            raise
            except Exception as error:
                log(f"registry: {error}")
            time.sleep(15)

    def deregister() -> None:
        server_id = state.get("id")
        if not server_id:
            return
        try:
            call("DELETE", f"/api/agents/{server_id}")
        except Exception:
            pass

    atexit.register(deregister)
    threading.Thread(target=loop, daemon=True).start()


def main() -> int:
    parser = argparse.ArgumentParser(description="Cursor mobile bridge server")
    parser.add_argument("--host", default=os.environ.get("CURSOR_MOBILE_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("CURSOR_MOBILE_PORT", str(DEFAULT_PORT))))
    parser.add_argument("--password", default=os.environ.get("CURSOR_MOBILE_PASSWORD") or DEFAULT_PASSWORD)
    parser.add_argument("--username", default=os.environ.get("CURSOR_MOBILE_USERNAME") or DEFAULT_USERNAME)
    parser.add_argument("--directory", default=os.environ.get("CURSOR_MOBILE_DIR", str(Path.cwd())))
    parser.add_argument("--no-qr", action="store_true", help="不打印配对二维码")
    parser.add_argument("--registry", default=os.environ.get("COMAGIC_REGISTRY") or os.environ.get("CURSOR_REGISTRY"))
    parser.add_argument("--registry-username", default=os.environ.get("COMAGIC_REGISTRY_USERNAME") or DEFAULT_USERNAME)
    parser.add_argument("--registry-password", default=os.environ.get("COMAGIC_REGISTRY_PASSWORD") or DEFAULT_PASSWORD)
    parser.add_argument("--name", default=os.environ.get("CURSOR_MOBILE_NAME") or f"cursor@{socket.gethostname()}")
    args = parser.parse_args()

    if not Path(AGENT_BIN).exists() and not shutil.which(AGENT_BIN):
        print(f"找不到 Cursor agent 可执行文件: {AGENT_BIN}", file=sys.stderr)
        print("请先安装 Cursor CLI，或设置 CURSOR_AGENT_BIN。", file=sys.stderr)
        return 1

    directory = str(Path(args.directory).expanduser().resolve())
    password = args.password or DEFAULT_PASSWORD

    httpd = make_server(args.host, args.port, password, args.username, directory)
    ip = lan_ip() if args.host in ("0.0.0.0", "::") else args.host
    url = f"http://{ip}:{args.port}"

    if args.registry:
        start_registry_reporter(
            registry_url=args.registry,
            registry_username=args.registry_username,
            registry_password=args.registry_password,
            server_url=url,
            username=args.username,
            password=password,
            directory=directory,
            name=args.name,
        )

    print()
    print("Cursor Mobile bridge 已启动")
    print(f"  地址: {url}")
    print(f"  用户名: {args.username}")
    print(f"  密码: {password}")
    print(f"  默认目录: {directory}")
    print(f"  Cursor CLI: {AGENT_BIN}")
    print(f"  状态文件: {STATE_PATH}")
    print(f"  自动批准工具: {'是' if RUN_FORCE else '否'}（CURSOR_RUN_FORCE=0 关闭）")
    print()
    print("需已执行 agent login，或设置 CURSOR_API_KEY。")
    print("手机 App 手动填写以上地址/密码即可配对。")
    if not args.no_qr:
        qr_script = Path(__file__).resolve().parent.parent / "scripts" / "pair-qr.sh"
        if qr_script.exists():
            try:
                rc = subprocess.run(["bash", str(qr_script), url, password, directory], check=False).returncode
                if rc != 0:
                    print("二维码生成失败，可直接用上方地址/密码手动配对。", file=sys.stderr)
            except Exception as exc:
                print(f"二维码生成失败: {exc}", file=sys.stderr)
    print("Ctrl-C 结束服务。")

    def shutdown(signum, frame):  # noqa: ARG001
        log("正在关闭…")
        httpd.runs.stop_all()  # type: ignore[attr-defined]
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    try:
        httpd.serve_forever(poll_interval=0.5)
    finally:
        httpd.runs.stop_all()  # type: ignore[attr-defined]
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
