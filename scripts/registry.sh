#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

export PATH="$HOME/.comagic/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

exec /usr/bin/python3 -u "$ROOT/server/server.py" \
  --host "${COMAGIC_REGISTRY_HOST:-0.0.0.0}" \
  --port "${COMAGIC_REGISTRY_PORT:-4908}" \
  --username "${COMAGIC_REGISTRY_USERNAME:-comagic}" \
  --password "${COMAGIC_REGISTRY_PASSWORD:-YFl4cVaQ8smozi4I}"
