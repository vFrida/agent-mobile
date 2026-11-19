# Agent Mobile Bridge Server

**English** | [简体中文](README.zh-CN.md)

A local service that depends only on the Python 3 standard library: it turns the app's HTTP/SSE requests into `comagic run --format json` subprocesses, so `comagic web` is not required.

```
Android / iOS  ──HTTP+SSE──▶  agents/comagic.py  ──spawn──▶  comagic run --format json
                                      │
                                      └── state.json (mobile session ID ↔ comagic session ID)
```

## Registry (multiple development machines)

`server/server.py` is a registry plus reverse proxy. The bridge registers with it on startup; the app connects only to the registry, which forwards `/s/<server_id>/...` to the corresponding machine.

```
bridge (4907) ──register/heartbeat──▶ registry (4908)
app ──HTTP+SSE──▶ registry /s/<id>/... ──forward──▶ bridge
```

```bash
# 1) Start the registry
python3 server/server.py --port 4908

# 2) Register a bridge
python3 agents/comagic.py --port 4907 --registry http://<machine-ip>:4908

# 3) List registered servers
curl -u comagic:YFl4cVaQ8smozi4I http://<machine-ip>:4908/api/agents

# 4) In the app, use the proxy address as the server, with the registry credentials
#    http://<machine-ip>:4908/s/<server_id>
```

Registry endpoints (all require Basic Auth; default `comagic` / `YFl4cVaQ8smozi4I`):

| Endpoint | Description |
| --- | --- |
| `POST /api/register` | Register a bridge with `{name,type,url,username,password,directory,version,defaultAgent,defaultModel}`; `type` is `comagic` or `cursor`; `defaultAgent` / `defaultModel` are the agent's default session type and default model (`{id,providerID}`) |
| `POST /api/agents/{id}/heartbeat` | Heartbeat; marked offline after 60s |
| `DELETE /api/agents/{id}` | Deregister (called automatically when the bridge exits) |
| `GET /api/agents` | Registered servers (with online status, `defaultAgent`, `defaultModel`) |
| `GET /global/health` | Health check |

Bridge flags: `--registry` (`COMAGIC_REGISTRY`), `--registry-username`, `--registry-password`, `--name`.

> The registry stores each bridge's Basic Auth credentials in order to proxy requests; run it only on a trusted LAN.

## Startup

```bash
python3 agents/comagic.py --directory /Users/txc --port 4907
```

On first start it prints the address, the username `comagic` and the password, then calls `scripts/pair-qr.sh` to render a pairing QR code. Scan it with the app or enter the details manually. The password defaults to the fixed value `YFl4cVaQ8smozi4I`.

QR rendering tries `swift` (macOS), `python3 -c "import qrcode"` and `qrencode` in that order. On Linux, if none is available, run `pip3 install qrcode`.

Common flags / environment variables:

| Flag | Environment variable | Default |
| --- | --- | --- |
| `--host` | `COMAGIC_MOBILE_HOST` | `0.0.0.0` |
| `--port` | `COMAGIC_MOBILE_PORT` | `4907` |
| `--password` | `COMAGIC_MOBILE_PASSWORD` | `YFl4cVaQ8smozi4I` |
| `--directory` | `COMAGIC_MOBILE_DIR` | current directory |
| `--no-qr` | – | do not print the QR code |
| – | `COMAGIC_BIN` | `comagic` or `~/.comagic/bin/comagic` |
| – | `COMAGIC_MOBILE_STATE` | `~/.comagic-mobile/state.json` |
| – | `COMAGIC_RUN_AUTO=1` | add `--auto` to every `comagic run` (auto-approve permissions; use with care) |

## How it works

- Creating a session from the phone only returns a placeholder ID (`ses_mobile_...`); no model is started immediately.
- `comagic run --format json` starts on the first message. The real `ses_...` is taken from the output events and written to the `state.json` mapping.
- Later messages continue the same session with `-s <real-session-id>`.
- Assistant text, tool calls, errors, idle events and more are forwarded over SSE `/event`; the phone filters by `sessionID`.
- History is read with `comagic export <session-id>`; the session list uses `comagic session list --format json`.
- Mode and model are passed on each launch via `--agent` / `--model` (and `--variant`); switching is recorded server-side and applies to the next turn.
- Images are decoded to a temporary file and sent as an attachment with `comagic run -f <file>`; the file is deleted after the run.
- Voice input is still transcribed on-device by the system speech recognizer and sent as text.

> Note: `comagic export` truncates stdout to roughly 64 KB when it is a pipe and the content is large (inline images). This server avoids that by redirecting stdout to a temporary file.

## Compatible endpoints

| Purpose | Endpoint |
| --- | --- |
| Auth | `Authorization: Basic base64(comagic:<password>)` |
| Health | `GET /global/health` |
| Session list | `GET /api/session?limit=50&order=desc` |
| Create session | `POST /api/session` body `{agent, model, location{directory}}` |
| History | `GET /session/{id}/message` |
| Send message / image | `POST /session/{id}/message` body `{parts:[...]}` |
| Stream events | `GET /event` (SSE) |
| Interrupt | `POST /api/session/{id}/interrupt` or `POST /session/{id}/abort` |
| Delete session | `DELETE /api/session/{id}` |
| Path info | `GET /path?directory=<dir>` |
| Browse directories | `GET /api/fs/list?location[directory]=<base>&path=<dir>` |
| Mode list | `GET /api/agent` |
| Model list | `GET /config/providers` |
| Switch mode | `POST /api/session/{id}/agent` body `{agent}` |
| Switch model | `POST /api/session/{id}/model` body `{model{id,providerID,variant?}}` |

## Limitations

- Each message starts a CLI subprocess, so cold starts are slower than a long-running `comagic web`; only one running request per session is allowed.
- `comagic run` has no interactive permission prompt: permissions come from the comagic configuration. Enable `COMAGIC_RUN_AUTO=1` if you need automatic approval.
- The mode list is the built-in `build` / `plan`; the model list is parsed from `comagic models --verbose`.
- Session titles, agent and model are backfilled from `comagic export` after the first run completes.
- Web-only features such as message editing, rollback and fork (other than attachments) are not implemented.

## Security

- The service uses Basic Auth with the fixed default password `YFl4cVaQ8smozi4I`; use it only on trusted LANs and override with `--password` when needed.
- This service is equivalent to exposing a command-executing coding agent to anyone who can reach the port. Never map it to the public internet.
- The directory-browsing endpoint for new sessions can read the target machine's directory tree; only expose it to trusted devices.
- `--auto` approves permission requests automatically; use it only in trusted environments.
