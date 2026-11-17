#!/usr/bin/env python3
"""Comagic Mobile bridge server.

Exposes the small HTTP/SSE API used by the Android/iOS clients, backed by the
`comagic run --format json` headless CLI instead of `comagic web`.

Only Python 3 standard library is required.
"""

from __future__ import annotations

import argparse
import base64
import hmac
import json
import os
import queue
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import uuid
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DEFAULT_COMAGIC = Path.home() / ".comagic" / "bin" / "comagic"
COMAGIC_BIN = os.environ.get("COMAGIC_BIN") or shutil.which("comagic") or str(DEFAULT_COMAGIC)
STATE_PATH = Path(
    os.environ.get("COMAGIC_MOBILE_STATE", str(Path.home() / ".comagic-mobile" / "state.json"))
).expanduser()
RUN_AUTO = os.environ.get("COMAGIC_RUN_AUTO") == "1"

AGENTS = [
    {
        "id": "build",
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
]


def log(message: str) -> None:
    print(f"[comagic-server] {message}", flush=True)


def now_ms() -> int:
    return int(time.time() * 1000)


def run_cli(args: list[str], timeout: float = 60, cwd: str | None = None) -> str:
    # Write stdout to a regular file: the comagic CLI truncates very large
    # payloads (e.g. session exports with inline images) when stdout is a pipe.
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as out:
        proc = subprocess.run(
            [COMAGIC_BIN, *args],
            stdout=out,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
            cwd=cwd,
        )
        out.seek(0)
        output = out.read()
    if proc.returncode != 0:
        detail = (proc.stderr or output or "").strip()[:500]
        raise RuntimeError(f"comagic {' '.join(args)} failed: {detail}")
    return output


class State:
    """Persistent session map: external mobile id -> comagic session id."""

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

    def find_by_real(self, real: str) -> str | None:
        with self.lock:
            for ext, meta in self.data["sessions"].items():
                if meta.get("real") == real:
                    return ext
        return None


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

        cmd = [COMAGIC_BIN, "run", "--format", "json", "--dir", directory]
        if real:
            cmd += ["-s", real]
        agent = meta.get("agent") or "build"
        cmd += ["--agent", agent]

        model = meta.get("model") or {}
        if model.get("id") and model.get("providerID"):
            cmd += ["--model", f"{model['providerID']}/{model['id']}"]
            if model.get("variant"):
                cmd += ["--variant", str(model["variant"])]
        if RUN_AUTO:
            cmd.append("--auto")
        for path in files:
            cmd += ["-f", path]
        if not real:
            title = text.strip().replace("\n", " ")[:40]
            if title:
                cmd += ["--title", title]
                self.state.put(ext, title=title)
        cmd.append(text or "请查看附件")

        log(f"run {ext}: {COMAGIC_BIN} {' '.join(cmd[1:])}")
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

        def read_stderr() -> None:
            assert proc.stderr is not None
            for line in proc.stderr:
                stderr_tail.append(line.rstrip())
                if len(stderr_tail) <= 5:
                    log(f"stderr {ext}: {line.rstrip()[:300]}")

        err_thread = threading.Thread(target=read_stderr, daemon=True)
        err_thread.start()

        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except Exception:
                continue
            self._handle_event(ext, event)

        code = proc.wait()
        err_thread.join(timeout=2)

        with self.lock:
            if self.runs.get(ext) is proc:
                self.runs.pop(ext, None)

        for path in files:
            try:
                os.unlink(path)
            except Exception:
                pass

        real = self.state.get(ext) or {}
        real_id = real.get("real")
        if code != 0 and not (code < 0):
            message = "\n".join(list(stderr_tail)[-5:]) or f"agent 退出码 {code}"
            self.hub.publish("session.error", {"sessionID": ext, "message": message})

        self.hub.publish("session.idle", {"sessionID": ext})

        if real_id:
            threading.Thread(target=self._refresh_meta, args=(ext, real_id), daemon=True).start()

    def _handle_event(self, ext: str, event: dict) -> None:
        sid = event.get("sessionID")
        meta = self.state.get(ext) or {}
        if sid and not meta.get("real"):
            self.state.put(ext, real=sid)

        kind = event.get("type")
        part = event.get("part") or {}
        if kind == "text":
            delta = part.get("text") or ""
            if delta:
                self.hub.publish("session.text.delta", {"sessionID": ext, "delta": delta})
        elif kind == "tool_use":
            tool = part.get("tool") or "tool"
            status = (part.get("state") or {}).get("status")
            if status == "error":
                error = (part.get("state") or {}).get("error") or "工具执行失败"
                self.hub.publish("session.tool.failed", {"sessionID": ext, "toolName": tool, "message": error})
            else:
                self.hub.publish("session.tool.called", {"sessionID": ext, "toolName": tool})
        elif kind in ("error", "session_error"):
            message = event.get("message") or (event.get("error") or {}).get("message") or "agent 出错"
            self.hub.publish("session.error", {"sessionID": ext, "message": message})

    def _refresh_meta(self, ext: str, real_id: str) -> None:
        try:
            doc = export_session(real_id)
        except Exception:
            return
        info = doc.get("info") or {}
        model = info.get("model")
        self.state.put(
            ext,
            title=info.get("title") or "",
            directory=info.get("directory") or "",
            agent=info.get("agent") or "build",
            model={
                "id": model.get("id"),
                "providerID": model.get("providerID"),
                "variant": model.get("variant"),
            } if model else None,
            cli_updated=(info.get("time") or {}).get("updated"),
        )


def export_session(real_id: str) -> dict:
    output = run_cli(["export", real_id], timeout=60)
    return json.loads(output)


def list_cli_sessions(limit: int = 100) -> list[dict]:
    output = run_cli(["session", "list", "--format", "json", "-n", str(limit)], timeout=60)
    try:
        data = json.loads(output)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def list_models() -> list[dict]:
    output = run_cli(["models", "--verbose"], timeout=60)
    decoder = json.JSONDecoder()
    models: list[dict] = []
    index = 0
    while index < len(output):
        start = output.find("{", index)
        if start < 0:
            break
        try:
            item, end = decoder.raw_decode(output, start)
        except Exception:
            index = start + 1
            continue
        index = end
        if not isinstance(item, dict):
            continue
        provider = item.get("providerID") or ""
        model_id = item.get("id") or ""
        if not provider or not model_id:
            continue
        models.append(
            {
                "id": model_id,
                "providerID": provider,
                "name": item.get("name") or model_id,
                "status": item.get("status") or "active",
                "enabled": True,
            }
        )
    return models


def session_ref(ext: str, meta: dict) -> dict:
    created = meta.get("created") or now_ms()
    updated = meta.get("updated") or created
    return {
        "id": ext,
        "title": meta.get("title") or "新会话",
        "agent": meta.get("agent") or "build",
        "model": meta.get("model"),
        "time": {"created": created, "updated": updated},
        "location": {"directory": meta.get("directory") or ""},
        "subpath": "",
    }


def clean_text(value: object) -> str:
    if not isinstance(value, str):
        return ""
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        try:
            decoded = json.loads(value)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return value


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ComagicMobileBridge/0.1"

    # injected by make_server
    state: State
    hub: EventHub
    runs: RunManager
    password: str
    username: str
    default_directory: str
    cli_lock = threading.Lock()

    def log_message(self, format: str, *args) -> None:  # noqa: A002
        log("%s - %s" % (self.address_string(), format % args))

    # ---------- helpers ----------

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

    def _send_json(self, status: int, payload: object) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
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

    # ---------- routing ----------

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
            self._send_json(200, {"healthy": True, "version": "bridge-0.1"})
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
            self._history(parts[1])
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
            agent = str(body.get("agent") or "build")
            meta = self.state.get(parts[2]) or {"directory": self.default_directory}
            self.state.put(parts[2], **{**meta, "agent": agent})
            self._send_empty()
            return
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "session" and parts[3] == "model":
            body = self._read_json()
            model = body.get("model") or {}
            if model.get("id") and model.get("providerID"):
                meta = self.state.get(parts[2]) or {"directory": self.default_directory}
                self.state.put(parts[2], **{**meta, "model": model})
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
            meta = self.state.get(ext) or {}
            real = meta.get("real")
            self.runs.interrupt(ext)
            if real:
                try:
                    run_cli(["session", "delete", real], timeout=30)
                except Exception as error:
                    self._send_json(500, {"error": str(error)})
                    return
            self.state.delete(ext)
            self._send_empty(200)
            return
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "session" and parts[2] == "message":
            self._send_empty()
            return
        self._send_json(404, {"error": "not found"})

    # ---------- endpoints ----------

    def _list_sessions(self, query: dict[str, list[str]]) -> None:
        limit = 50
        try:
            limit = int((query.get("limit") or ["50"])[0])
        except Exception:
            pass

        with self.cli_lock:
            try:
                cli_sessions = list_cli_sessions(limit=max(limit, 50))
            except Exception as error:
                log(f"session list failed: {error}")
                cli_sessions = []

        merged: dict[str, dict] = {}
        for item in cli_sessions:
            real = item.get("id")
            if not real:
                continue
            ext = self.state.find_by_real(real)
            if ext:
                meta = self.state.get(ext) or {}
                meta.update(
                    title=item.get("title") or meta.get("title") or "",
                    directory=item.get("directory") or meta.get("directory") or "",
                    cli_updated=item.get("updated") or meta.get("cli_updated"),
                    updated=item.get("updated") or meta.get("updated"),
                    created=item.get("created") or meta.get("created"),
                )
                merged[ext] = session_ref(ext, meta)
            else:
                meta = {
                    "real": real,
                    "title": item.get("title") or "",
                    "directory": item.get("directory") or "",
                    "agent": "build",
                    "model": None,
                    "created": item.get("created") or now_ms(),
                    "updated": item.get("updated") or now_ms(),
                }
                self.state.put(real, touch=False, **meta)
                merged[real] = session_ref(real, meta)

        for ext, meta in self.state.all():
            if ext in merged:
                continue
            if not meta.get("real") or meta.get("real") != ext:
                merged[ext] = session_ref(ext, meta)

        sessions = sorted(merged.values(), key=lambda s: s["time"]["updated"], reverse=True)
        self._send_json(200, {"data": sessions[:limit]})

    def _create_session(self) -> None:
        body = self._read_json()
        ext = "ses_mobile_" + uuid.uuid4().hex[:16]
        location = body.get("location") or {}
        directory = location.get("directory") or self.default_directory
        model = body.get("model")
        if isinstance(model, dict):
            model = {
                "id": model.get("id"),
                "providerID": model.get("providerID"),
                "variant": model.get("variant"),
            }
        else:
            model = None
        meta = self.state.put(
            ext,
            real=None,
            title="新会话",
            agent=body.get("agent") or "build",
            model=model,
            directory=directory,
            created=now_ms(),
        )
        self._send_json(200, {"data": session_ref(ext, meta)})

    def _history(self, ext: str) -> None:
        meta = self.state.get(ext) or {}
        real = meta.get("real") or ext
        if not real:
            self._send_json(200, [])
            return
        try:
            doc = export_session(real)
        except Exception:
            self._send_json(200, [])
            return

        info = doc.get("info") or {}
        model = info.get("model")
        self.state.put(
            ext,
            title=info.get("title") or meta.get("title") or "",
            directory=info.get("directory") or meta.get("directory") or "",
            agent=info.get("agent") or meta.get("agent") or "build",
            model={
                "id": model.get("id"),
                "providerID": model.get("providerID"),
                "variant": model.get("variant"),
            } if model else meta.get("model"),
        )

        messages = []
        for message in doc.get("messages") or []:
            info = message.get("info") or {}
            parts = message.get("parts") or []
            cleaned = []
            for part in parts:
                if part.get("synthetic"):
                    continue
                if part.get("type") == "text":
                    part = dict(part)
                    part["text"] = clean_text(part.get("text"))
                cleaned.append(part)
            messages.append({"info": info, "parts": cleaned})
        self._send_json(200, messages)

    def _prompt(self, ext: str) -> None:
        body = self._read_json()
        meta = self.state.get(ext)
        if meta is None:
            meta = {"real": ext if ext.startswith("ses_") else None,
                    "directory": self.default_directory, "agent": "build", "model": None}
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
                        fd, path = tempfile.mkstemp(prefix="comagic-image-", suffix=suffix)
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
                "state": str(Path.home() / ".local" / "share" / "comagic"),
                "config": str(Path.home() / ".config" / "comagic"),
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
            models = []
        providers: dict[str, dict] = {}
        for model in models:
            provider = providers.setdefault(
                model["providerID"],
                {"id": model["providerID"], "name": model["providerID"], "models": {}},
            )
            provider["models"][model["id"]] = model
        self._send_json(200, {"providers": list(providers.values()), "default": {}})

    # ---------- SSE ----------

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


def main() -> int:
    parser = argparse.ArgumentParser(description="Comagic mobile bridge server")
    parser.add_argument("--host", default=os.environ.get("COMAGIC_MOBILE_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("COMAGIC_MOBILE_PORT", "4907")))
    parser.add_argument("--password", default=os.environ.get("COMAGIC_MOBILE_PASSWORD"))
    parser.add_argument("--username", default=os.environ.get("COMAGIC_MOBILE_USERNAME", "comagic"))
    parser.add_argument("--directory", default=os.environ.get("COMAGIC_MOBILE_DIR", str(Path.cwd())))
    parser.add_argument("--no-qr", action="store_true", help="不打印配对二维码")
    args = parser.parse_args()

    if not Path(COMAGIC_BIN).exists() and not shutil.which(COMAGIC_BIN):
        print(f"找不到 comagic 可执行文件: {COMAGIC_BIN}", file=sys.stderr)
        return 1

    directory = str(Path(args.directory).expanduser().resolve())
    password = args.password or secrets.token_urlsafe(12)

    httpd = make_server(args.host, args.port, password, args.username, directory)
    ip = lan_ip() if args.host in ("0.0.0.0", "::") else args.host
    url = f"http://{ip}:{args.port}"

    print()
    print("Comagic Mobile bridge 已启动")
    print(f"  地址: {url}")
    print(f"  用户名: {args.username}")
    print(f"  密码: {password}")
    print(f"  默认目录: {directory}")
    print(f"  状态文件: {STATE_PATH}")
    print()
    print("手机 App 手动填写以上地址/密码即可配对。")
    if not args.no_qr:
        qr_script = Path(__file__).resolve().parent.parent / "scripts" / "pair-qr.sh"
        if qr_script.exists():
            try:
                subprocess.run(["bash", str(qr_script), url, password, directory], check=False)
            except Exception:
                pass
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
