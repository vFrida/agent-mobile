#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

exec /usr/bin/python3 -u "$ROOT/agents/cursor.py" \
  --host "${CURSOR_MOBILE_HOST:-0.0.0.0}" \
  --port "${CURSOR_MOBILE_PORT:-4909}" \
  --directory "${CURSOR_MOBILE_DIR:-$HOME}" \
  --no-qr
