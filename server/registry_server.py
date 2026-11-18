#!/usr/bin/env python3
"""Comagic server registry + reverse proxy.

comagic bridge server 启动时向本服务注册；手机 App 只需要连本服务，通过
`/s/<id>/...` 转发到对应的 bridge server（支持 SSE 流式转发）。
"""

from __future__ import annotations

import argparse
import base64
import hmac
import json
import queue
import socket
import threading
import time
import uuid
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

DEFAULT_USERNAME = "comagic"
DEFAULT_PASSWORD = "YFl4cVaQ8smozi4I"
OFFLINE_AFTER_MS = 60_000


def now_ms() -> int:
    return int(time.time() * 1000)


def log(message: str) -> None:
    print(f"[comagic-registry] {message}", flush=True)


class Registry:
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.servers: dict[str, dict] = {}

    def register(self, payload: dict) -> dict:
        with self.lock:
            url = str(payload.get("url") or "").rstrip("/")
            if not url:
                raise ValueError("url required")
            for server_id, entry in self.servers.items():
                if entry.get("url") == url:
                    entry.update(payload)
                    entry["url"] = url
                    entry["lastSeen"] = now_ms()
                    return {"id": server_id, **self._public(entry)}
            server_id = uuid.uuid4().hex[:12]
            entry = dict(payload)
            entry["url"] = url
            entry["id"] = server_id
            entry["lastSeen"] = now_ms()
            entry.setdefault("name", url)
            self.servers[server_id] = entry
            return {"id": server_id, **self._public(entry)}

    def heartbeat(self, server_id: str) -> bool:
        with self.lock:
            entry = self.servers.get(server_id)
            if entry is None:
                return False
            entry["lastSeen"] = now_ms()
            return True

    def remove(self, server_id: str) -> None:
        with self.lock:
            self.servers.pop(server_id, None)

    def get(self, server_id: str) -> dict | None:
        with self.lock:
            entry = self.servers.get(server_id)
            return dict(entry) if entry else None

    def list(self) -> list[dict]:
        with self.lock:
            return [self._public(entry) for entry in self.servers.values()]

    @staticmethod
    def _public(entry: dict) -> dict:
        return {
            "id": entry.get("id"),
            "name": entry.get("name") or entry.get("url"),
            "url": entry.get("url"),
            "directory": entry.get("directory") or "",
            "version": entry.get("version") or "",
            "lastSeen": entry.get("lastSeen") or 0,
            "online": now_ms() - int(entry.get("lastSeen") or 0) < OFFLINE_AFTER_MS,
        }


REGISTRY = Registry()


def basic_auth(username: str, password: str) -> str:
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    return f"Basic {token}"


def lan_ip() -> str:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        return ip
    except Exception:
        return "127.0.0.1"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    username = DEFAULT_USERNAME
    password = DEFAULT_PASSWORD

    def log_message(self, fmt: str, *args) -> None:
        log(f"{self.address_string()} - {fmt % args}")

    # ---------- helpers ----------

    def _authorized(self) -> bool:
        raw = self.headers.get("Authorization") or ""
        if not raw.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(raw[6:]).decode()
        except Exception:
            return False
        user, _, password = decoded.partition(":")
        return hmac.compare_digest(user, self.username) and hmac.compare_digest(password, self.password)

    def _require_auth(self) -> bool:
        if self._authorized():
            return True
        self._send_json(401, {"error": "unauthorized"}, headers={"WWW-Authenticate": 'Basic realm="comagic"'})
        return False

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

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

    # ---------- routes ----------

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path in ("/global/health", "/health"):
            self._send_json(200, {"healthy": True, "version": "registry-0.1", "servers": len(REGISTRY.list())})
            return

        if not self._require_auth():
            return

        if path == "/api/servers":
            self._send_json(200, {"data": REGISTRY.list()})
            return

        if path.startswith("/s/"):
            rest = path[len("/s/"):]
            server_id, _, tail = rest.partition("/")
            self._proxy(server_id, tail, parsed.query)
            return

        self._send_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if not self._require_auth():
            return

        if path == "/api/register":
            try:
                entry = REGISTRY.register(self._read_json())
            except ValueError as error:
                self._send_json(400, {"error": str(error)})
                return
            log(f"register {entry['id']} url={entry['url']}")
            self._send_json(200, entry)
            return

        if path.startswith("/api/servers/") and path.endswith("/heartbeat"):
            server_id = path[len("/api/servers/"):-len("/heartbeat")]
            if REGISTRY.heartbeat(server_id):
                self.send_response(204)
                self.send_header("Content-Length", "0")
                self.end_headers()
            else:
                self._send_json(404, {"error": "unknown server"})
            return

        if path.startswith("/s/"):
            rest = path[len("/s/"):]
            server_id, _, tail = rest.partition("/")
            self._proxy(server_id, tail, parsed.query)
            return

        self._send_json(404, {"error": "not found"})

    def do_PUT(self) -> None:
        self.do_POST()

    def do_PATCH(self) -> None:
        self.do_POST()

    def do_DELETE(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if not self._require_auth():
            return

        if path.startswith("/api/servers/"):
            server_id = path[len("/api/servers/"):]
            REGISTRY.remove(server_id)
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if path.startswith("/s/"):
            rest = path[len("/s/"):]
            server_id, _, tail = rest.partition("/")
            self._proxy(server_id, tail, parsed.query)
            return

        self._send_json(404, {"error": "not found"})

    # ---------- proxy ----------

    def _proxy(self, server_id: str, tail: str, query: str) -> None:
        entry = REGISTRY.get(server_id)
        if entry is None or not entry.get("url"):
            self._send_json(404, {"error": f"unknown server: {server_id}"})
            return

        target = urlparse(entry["url"])
        path = "/" + tail if tail else "/"
        if query:
            path += "?" + query

        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length > 0 else None

        headers = {
            "Authorization": basic_auth(
                entry.get("username") or DEFAULT_USERNAME,
                entry.get("password") or "",
            ),
            "Host": target.netloc,
        }
        for name in ("Content-Type", "Accept", "Accept-Encoding", "Last-Event-ID"):
            value = self.headers.get(name)
            if value:
                headers[name] = value

        try:
            conn = HTTPConnection(target.hostname, target.port or 80, timeout=600)
            conn.request(self.command, path, body=body, headers=headers)
            response = conn.getresponse()
        except Exception as error:
            self._send_json(502, {"error": f"upstream failed: {error}"})
            return

        try:
            self.send_response(response.status)
            content_type = response.getheader("Content-Type")
            if content_type:
                self.send_header("Content-Type", content_type)
            for name in ("Cache-Control", "X-History-Has-More"):
                value = response.getheader(name)
                if value:
                    self.send_header(name, value)
            self.send_header("Access-Control-Allow-Origin", "*")

            content_length = response.getheader("Content-Length")
            streaming = content_length is None
            if streaming:
                self.send_header("Transfer-Encoding", "chunked")
            else:
                self.send_header("Content-Length", content_length)
            self.end_headers()
            self.close_connection = True

            if streaming:
                while True:
                    chunk = response.read1(8192)
                    if not chunk:
                        break
                    self.wfile.write(f"{len(chunk):X}\r\n".encode("ascii") + chunk + b"\r\n")
                    self.wfile.flush()
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            else:
                while True:
                    chunk = response.read(8192)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            try:
                response.close()
                conn.close()
            except Exception:
                pass


def make_server(host: str, port: int, username: str, password: str) -> ThreadingHTTPServer:
    handler = type(
        "BoundHandler",
        (Handler,),
        {"username": username, "password": password},
    )
    return ThreadingHTTPServer((host, port), handler)


def main() -> int:
    parser = argparse.ArgumentParser(description="Comagic mobile server registry")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=4908)
    parser.add_argument("--username", default=DEFAULT_USERNAME)
    parser.add_argument("--password", default=DEFAULT_PASSWORD)
    args = parser.parse_args()

    httpd = make_server(args.host, args.port, args.username, args.password)
    ip = lan_ip() if args.host in ("0.0.0.0", "::") else args.host
    print()
    print("Comagic 注册中心已启动")
    print(f"  地址: http://{ip}:{args.port}")
    print(f"  用户名: {args.username}")
    print(f"  密码: {args.password}")
    print("  注册接口: POST /api/register")
    print("  服务器列表: GET /api/servers")
    print("  转发: /s/<server_id>/...")
    print()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print()
        log("bye")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
