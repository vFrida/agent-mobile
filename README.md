# Agent Mobile

**English** | [简体中文](README.zh-CN.md)

A mobile client for the Comagic CLI. Connect to the service running on your development machine, then send messages, watch streamed output and approve tool calls from your phone. On the development machine you can either use `comagic web`, or the bundled, web-free [bridge server](server/README.md).

## Layout

```
agent-mobile/
├── android/        Android client (Kotlin + Compose, Gradle)
├── ios/            iOS client (SwiftUI, XcodeGen)
├── agents/         bridge: comagic.py / cursor.py
├── server/         registry (multi-machine discovery and reverse proxy)
├── scripts/        pairing and autostart (serve.sh / serve-cursor.sh)
└── docs/           feature overview and icons
```

## Autostart (macOS)

Use `scripts/serve.sh` plus a LaunchAgent to start the bridge service at login (with a fixed password, so you do not have to re-pair every time):

```bash
# Register the LaunchAgent (skip if already registered)
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.comagic.mobile-server.plist

tail -f ~/Library/Logs/comagic-mobile/server.log
```

- Fixed credentials: `comagic` / `YFl4cVaQ8smozi4I` (pre-filled when adding a server in the app). Override with `COMAGIC_MOBILE_USERNAME` / `COMAGIC_MOBILE_PASSWORD`.
- The registry `com.comagic.mobile-registry` (`scripts/registry.sh`, port 4908) also starts at login. The bridge registers with it, and the app can proxy everything through `http://<IP>:4908/s/<server_id>`.
- The service listens on port `4907` at `0.0.0.0` and starts in `$HOME`. Override with `COMAGIC_MOBILE_PORT` / `COMAGIC_MOBILE_HOST` / `COMAGIC_MOBILE_DIR`.
- Crashes are restarted automatically (`KeepAlive`). To stop:
  ```bash
  launchctl bootout gui/$(id -u)/com.comagic.mobile-server
  launchctl bootout gui/$(id -u)/com.comagic.mobile-registry
  ```
- The Cursor agent can autostart too: `~/Library/LaunchAgents/com.comagic.mobile-cursor-agent.plist` (`scripts/serve-cursor.sh`, port 4909).
- This starts at login. To start before login, put the plists in `/Library/LaunchDaemons` and load them as root.

## Pairing

### Option 1: bundled bridge server (recommended, no `comagic web` dependency)

```bash
# Comagic CLI
python3 agents/comagic.py --directory /Users/txc --port 4907

# or the Cursor Agent CLI (requires `agent login` / CURSOR_API_KEY)
python3 agents/cursor.py --directory /Users/txc --port 4909
```

On first start it prints the address, the username `comagic` and the password, then renders a pairing QR code. The password defaults to the fixed value `YFl4cVaQ8smozi4I` (override with `--password` or `COMAGIC_MOBILE_PASSWORD` / `CURSOR_MOBILE_PASSWORD`). See [server/README.md](server/README.md) for details.

> Permission approval from the phone needs the `comagic web` backend (option 2). The run-based bridge auto-rejects permission requests; enable `COMAGIC_RUN_AUTO=1` if you want it to approve automatically.

### Option 2: use `comagic web` directly

```bash
# One terminal: start the service and print the QR code (captures the password and LAN address automatically)
./scripts/pair.sh /Users/txc

# Or if the service is already running
./scripts/pair-qr.sh http://192.168.1.10:4907 <password printed in the terminal> /Users/txc
```

In the app, tap "Scan QR code" and scan it, or enter the address, username `comagic` and password manually.

When creating a session, the app first opens a directory browser so you can pick the working directory on the target machine, then creates the session.

The "Servers" action in the session list manages paired machines: list, switch the current server, add, edit or delete. Passwords are stored in the platform's encrypted storage (Android EncryptedSharedPreferences / iOS Keychain).

`comagic web` opens a browser by default; `pair.sh` suppresses that with a temporary `open` shim. To start it yourself without a browser:

```bash
D=$(mktemp -d) && printf '#!/bin/sh\nexit 0\n' > "$D/open" && chmod +x "$D/open"
PATH="$D:$PATH" comagic web --hostname 0.0.0.0 --port 4907
```

## Android

```bash
cd android
./build.sh              # debug build
./build.sh test         # JVM unit tests
./build.sh install --launch
./build.sh run          # install, adb reverse 4907, then launch
```

Requirements: JDK 17+, Android SDK (the build script generates `local.properties`).

Debug and release share the same local debug signing key as mosu. `versionCode` is the git commit count and `versionName` is `1.0.<commit count>`.

## iOS

```bash
cd ios
xcodegen generate       # re-run after editing project.yml
open AgentMobile.xcodeproj
# or from the command line
xcodebuild -scheme AgentMobile -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build
```

Requirements: Xcode 15+, [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

The iOS unit tests read `/tmp/comagic_test_env.json` (`{"url":..., "pass":...}`) and connect to a real service; they are skipped when the file is missing.

## Server protocol essentials

| Purpose | Endpoint |
| --- | --- |
| Auth | `Authorization: Basic base64(comagic:YFl4cVaQ8smozi4I)` |
| Session list | `GET /api/session?limit=50&order=desc` |
| Create session | `POST /api/session` body `{agent, model{id,providerID,variant}, location{directory}}` |
| Delete session | `DELETE /api/session/{id}` |
| Mode list | `GET /api/agent?location[directory]=<dir>` (returns `mode=primary`, non-hidden modes) |
| Model list | `GET /config/providers?directory=<dir>` (flattens `providers[].models`) |
| Switch mode | `POST /api/session/{id}/agent` body `{agent}` |
| Switch model | `POST /api/session/{id}/model` body `{model{id,providerID,variant?}}` |
| History | `GET /session/{id}/message` |
| Send message | `POST /session/{id}/message` body `{parts:[{type:"text",text}] or parts:[{type:"file",mime,filename,url:"data:..."},{type:"text",text}], agent, model{providerID,modelID}}` |
| Stream events | `GET /event` (SSE, `{id,type,properties}`) |
| Interrupt | `POST /session/{id}/abort` |

Note: `/api/session/{id}/prompt` only enqueues and never executes; clients must not use it to send messages.

Below the chat input you can pick the mode and model like on the web. Switching syncs to the current session (`/api/session/{id}/agent|model`).

Images are compressed to JPEG with a 1600px longest edge, then sent as a data URL in a `file` part. Voice input uses the system speech recognizer (Android `SpeechRecognizer` / iOS `SFSpeechRecognizer`); the transcript is placed in the input box and sent as text — audio is never uploaded.

## Security

- Passwords are stored in Android EncryptedSharedPreferences / iOS Keychain.
- `comagic web --hostname 0.0.0.0` exposes an agent that can execute shell commands to the LAN. Use it only on trusted networks and switch back to `127.0.0.1` when done.
- QR codes contain the password; do not screenshot and share them. The `comagic web` password changes on every restart.
