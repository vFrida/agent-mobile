#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${PORT:-4907}"
DIR="${1:-$PWD}"
LOG="$(mktemp -t comagic-web)"

if ! command -v comagic >/dev/null 2>&1; then
  echo "未找到 comagic 命令" >&2
  exit 1
fi

NOOPEN="$(mktemp -d -t comagic-noopen)"
printf '#!/bin/sh\nexit 0\n' > "$NOOPEN/open"
chmod +x "$NOOPEN/open"

echo "==> 启动 comagic web --hostname 0.0.0.0 --port ${PORT}（不打开浏览器）"
PATH="$NOOPEN:$PATH" comagic web --hostname 0.0.0.0 --port "$PORT" >"$LOG" 2>&1 &
SERVER_PID=$!

cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  rm -rf "$NOOPEN" "$LOG"
}
trap cleanup EXIT INT TERM

PASSWORD=""
for _ in $(seq 1 40); do
  PASSWORD="$(grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "$LOG" 2>/dev/null | head -1 || true)"
  [[ -n "$PASSWORD" ]] && break
  sleep 0.5
done

if [[ -z "$PASSWORD" ]]; then
  echo "没能从 comagic web 的输出里取到密码，原始输出：" >&2
  sed -e 's/\x1b\[[0-9;]*m//g' "$LOG" >&2
  exit 1
fi

IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
if [[ -z "$IP" ]]; then
  IP="127.0.0.1"
  echo "未取到局域网地址，二维码使用 127.0.0.1（仅 adb reverse 可用）" >&2
fi

URL="http://$IP:$PORT"
echo "==> 地址: ${URL}"
echo "==> 工作目录: ${DIR}"
echo
"$HERE/pair-qr.sh" "$URL" "$PASSWORD" "$DIR"

echo
echo "扫码后保持本进程运行；Ctrl-C 结束并关闭服务。"
wait "$SERVER_PID"
