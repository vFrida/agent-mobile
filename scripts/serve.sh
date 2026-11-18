#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

export PATH="$HOME/.comagic/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

exec /usr/bin/python3 -u "$ROOT/agents/comagic_agent.py" \
  --host "${COMAGIC_MOBILE_HOST:-0.0.0.0}" \
  --port "${COMAGIC_MOBILE_PORT:-4907}" \
  --directory "${COMAGIC_MOBILE_DIR:-$HOME}" \
  --no-qr
