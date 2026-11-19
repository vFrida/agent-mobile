# Agent Mobile — Feature Overview

**English** | [简体中文](features.zh-CN.md)

The mobile app (Android / iOS) connects to **agents** running on development machines, so you can start sessions, watch streamed output and approve commands from your phone. Multiple machines are discovered through a **registry**; the app only logs in to the registry and automatically gets the available agents.

---

## 1. Architecture

```
Android / iOS app
        │  login (registry IP / username / password)
        ▼
   Registry server/server.py (4908)
        │  /api/agents discovery
        │  /s/<agent_id>/... reverse proxy (including SSE)
        ▼
   Agent agents/comagic.py (4907) or agents/cursor.py (4909)
        │
        ▼
   comagic run --format json  /  agent -p --output-format stream-json
```

- **Registry**: stores each agent's name, type, version and online status, and reverse-proxies HTTP/SSE.
- **Agent**: turns app requests into CLI subprocess calls and manages session state; `type` is `comagic` or `cursor`.
- **App**: talks only to the registry, never directly to development machines.

---

## 2. Quick start

### 2.1 Start the registry

```bash
python3 server/server.py --port 4908
```

Default credentials: `comagic` / `YFl4cVaQ8smozi4I`.

### 2.2 Start an agent on a development machine and register it

```bash
# Comagic
python3 agents/comagic.py \
  --port 4907 \
  --directory ~ \
  --registry http://<registry-ip>:4908 \
  --no-qr

# Cursor (requires `agent` on PATH and logged in)
python3 agents/cursor.py \
  --port 4909 \
  --directory ~ \
  --registry http://<registry-ip>:4908 \
  --no-qr
# or: ./scripts/serve-cursor.sh
```

Check the online agents:

```bash
curl -s -u comagic:YFl4cVaQ8smozi4I http://<registry-ip>:4908/api/agents
```

### 2.3 Log in from the app

On first launch the login screen asks for:

- Registry IP (just the IP; `http://` and `:4908` are added automatically)
- Username / password

After login the app opens the **agents** home screen and automatically discovers and adds the available agents.

### 2.4 supervisor (optional, long-running)

```ini
[program:agent-registry]
command=/usr/bin/python3 -u /path/to/agent-mobile/server/server.py --host 0.0.0.0 --port 4908

[program:comagic-agent]
command=/path/to/agent-mobile/scripts/serve.sh
environment=HOME="/home/user",COMAGIC_REGISTRY="http://192.168.100.136:4908"

[program:cursor-agent]
command=/path/to/agent-mobile/scripts/serve-cursor.sh
environment=HOME="/home/user",COMAGIC_REGISTRY="http://192.168.100.136:4908",PATH="/home/user/.local/bin:%(ENV_PATH)s"
```

---

## 3. App features

### 3.1 Login and registry

- The app logs in to the registry first and stores credentials encrypted (Android EncryptedSharedPreferences, iOS Keychain).
- The registry IP field has a dropdown: add, remove and switch between multiple IPs.
- After login the root screen is **agents**.

### 3.2 Agents home

- Shows discovered agents: name, agent id, type, version and online status.
- Top-right actions:
  - **Refresh**: re-query the registry and probe each agent's health.
  - **Settings**: open the settings screen.
  - **Log out**: clear credentials and return to the login screen.
- Swipe an agent to reveal "Cancel / Delete".
  - Deleting only affects this device and adds the agent to a hidden list, so it will not reappear after refresh.
  - Settings offers "Restore deleted agents".
- Only **online** agents returned by the registry are shown, with type `comagic` / `cursor` (or no type).

### 3.3 Settings

- Registry IP and current account.
- Session options: **show hidden directories** (whether dot-directories appear when choosing a working directory for a new session).
- Restore deleted agents.
- Log out.

### 3.4 Sessions and chat

- Pick an agent to open its session list: search, refresh, create session, delete session (swipe).
- New session: browse the target machine's directories and pick a working directory.
- The chat screen supports:
  - streamed output and tool-call display
  - command approval (allow once / deny)
  - interrupting a run
  - switching agent mode (build / plan, etc.)
  - switching model
  - sending images
  - voice input
  - paginated history
  - automatic reconnect after disconnects
- Navigation: chat → session list → agents.

---

## 4. Registry API

All endpoints require Basic Auth.

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/register` | Register an agent: `{name,type,url,username,password,directory,version,defaultAgent,defaultModel}` |
| GET | `/api/agents` | Online agents (offline agents are omitted) |
| POST | `/api/agents/{id}/heartbeat` | Heartbeat; offline after 60s |
| DELETE | `/api/agents/{id}` | Deregister (called automatically when an agent exits) |
| GET | `/global/health` | Registry health check |
| ANY | `/s/{id}/...` | Reverse proxy to the agent (SSE supported) |

Registering the same URL again reuses the original id.

---

## 5. Agent API (accessed through the registry)

| Method | Path | Description |
| --- | --- | --- |
| GET | `/global/health` | Returns `{healthy, version}`; version is the git commit count |
| GET | `/event` | SSE event stream |
| GET | `/api/session` | Session list |
| POST | `/api/session` | Create a session (directory, agent, model) |
| GET | `/session/{id}/message` | Message history |
| POST | `/session/{id}/message` | Send a message |
| POST | `/session/{id}/interrupt` | Interrupt |
| POST | `/session/{id}/permission/{req}/reply` | Permission reply |
| POST | `/api/session/{id}/agent` | Switch agent mode |
| POST | `/api/session/{id}/model` | Switch model |
| GET | `/api/agent` | Available agent modes |
| GET | `/config/providers` | Available models |
| GET | `/path`, `/api/fs/list` | Directory info and browsing |

---

## 6. Configuration

### Agents (`agents/comagic.py` / `agents/cursor.py`)

| Flag | Environment variable (comagic / cursor) | Default |
| --- | --- | --- |
| `--host` | `COMAGIC_MOBILE_HOST` / `CURSOR_MOBILE_HOST` | `0.0.0.0` |
| `--port` | `COMAGIC_MOBILE_PORT` / `CURSOR_MOBILE_PORT` | `4907` / `4909` |
| `--username` | `COMAGIC_MOBILE_USERNAME` / `CURSOR_MOBILE_USERNAME` | `comagic` |
| `--password` | `COMAGIC_MOBILE_PASSWORD` / `CURSOR_MOBILE_PASSWORD` | `YFl4cVaQ8smozi4I` |
| `--directory` | `COMAGIC_MOBILE_DIR` / `CURSOR_MOBILE_DIR` | current directory |
| `--name` | `COMAGIC_MOBILE_NAME` / `CURSOR_MOBILE_NAME` | hostname / `cursor@hostname` |
| `--registry` | `COMAGIC_REGISTRY` (shared) | none (no registration) |
| `--registry-username` | `COMAGIC_REGISTRY_USERNAME` | `comagic` |
| `--registry-password` | `COMAGIC_REGISTRY_PASSWORD` | same as above |
| `--no-qr` | – | do not print the pairing QR code (for long-running use) |
| – | `CURSOR_AGENT_BIN` | `agent` / `cursor-agent` |
| – | `CURSOR_RUN_FORCE=0` | `--force` is added by default; set to `0` to disable |

### App

- Default registry: `http://192.168.100.136:4908`
- Multiple registry IPs are supported and managed from the login screen dropdown.
- Hidden-directory preference and deleted agents are stored on the device.

---

## 7. Security

- Both the registry and the agents use Basic Auth with a publicly documented default password; **use only on trusted LANs**.
- The registry stores each agent's credentials in order to proxy requests.
- The app stores credentials using the platform's encrypted storage.
- This service is equivalent to exposing a command-executing coding agent to anyone who can reach the port. Never map it to the public internet.

---

## 8. FAQ

**More agents than expected after refresh?**
Older versions kept stale ids from before a registry restart. Current versions clean them up during discovery; if the problem persists, log out and log in again.

**A deleted agent reappears after refresh?**
Current versions record the hidden id and skip it; restore it from Settings.

**Version shows `bridge-0.1`?**
That agent is still running old code. Restart it and the version becomes the git commit count (e.g. `42`).

**404 on the API?**
The registry now uses `/api/agents` (previously `/api/servers`). Update and restart both the agent and the app.
