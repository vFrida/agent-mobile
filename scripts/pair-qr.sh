#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
URL="${1:-}"
PASS="${2:-}"
DIR="${3:-$PWD}"

if [[ "$URL" == "--ip" ]]; then
  ip="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
  [[ -n "$ip" ]] && echo "http://$ip:${PORT:-4907}"
  exit 0
fi

if [[ -z "$URL" || -z "$PASS" ]]; then
  cat <<EOF
用法: ./scripts/pair-qr.sh <url> <password> [directory]

  先在开发机上启动服务:
    comagic web --hostname 0.0.0.0 --port 4907

  再取本机局域网地址:
    ./scripts/pair-qr.sh --ip

  最后生成二维码（密码用终端里打印的那一串）:
    ./scripts/pair-qr.sh http://\$(./scripts/pair-qr.sh --ip | cut -d/ -f3) <password> /Users/txc

  手机 App 点"扫码配对"即可。
EOF
  exit 1
fi

PAYLOAD="$(python3 - "$URL" "$PASS" "$DIR" <<'PY'
import sys, urllib.parse
url, password, directory = sys.argv[1], sys.argv[2], sys.argv[3]
params = {"url": url, "pass": password}
if directory and directory != "/":
    params["dir"] = directory
print("comagic://pair?" + urllib.parse.urlencode(params))
PY
)"

echo "payload: $PAYLOAD"
echo

render() {
  if command -v swift >/dev/null 2>&1; then
    swift "$HERE/qr.swift" "$PAYLOAD" L && return 0
  fi

  if python3 -c "import qrcode" >/dev/null 2>&1; then
    python3 "$HERE/qr.py" "$PAYLOAD" L && return 0
  fi

  if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ANSIUTF8 -l L "$PAYLOAD" && return 0
  fi

  return 1
}

if ! render; then
  cat >&2 <<EOF
无法在当前环境生成二维码（缺少 swift / python qrcode / qrencode）。
可执行以下任一命令后重试：
  pip3 install qrcode
  # 或  apt-get install qrencode
也可直接让手机 App 手动填写上面的地址和密码配对。
EOF
fi
