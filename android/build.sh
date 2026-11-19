#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MODULE=":app"
PORT="${PORT:-4907}"

SERIAL="${SERIAL:-}"
TASK="assembleDebug"
INSTALL=0
LAUNCH=0
REVERSE=0
RUN_TESTS=0
CLEAN=0

usage() {
  cat <<'EOF'
用法: ./build.sh [选项] [debug|release|install|run|test|clean]

  debug     编 debug APK（默认）
  release   编 release APK（与 debug 共用同一签名证书）
  test      跑 JVM 单测（COMAGIC_TEST_URL / COMAGIC_TEST_PASSWORD 指向真实服务）
  clean     清理构建产物
  install   编 debug 并安装到设备
  run       编 debug、安装、adb reverse 4907 并启动 App
  all       编 debug + 单测 + 安装

选项:
  --reverse[=PORT]  安装后执行 adb reverse tcp:PORT tcp:PORT（默认 4907）
  --launch          安装后启动 MainActivity
  --serial=S        指定设备序列号
  -h, --help        显示帮助

示例:
  ./build.sh run
  ./build.sh install --reverse --launch
  PORT=5000 ./build.sh install --reverse=5000
EOF
}

ACTION=""
for arg in "$@"; do
  case "$arg" in
    debug)      ACTION="debug" ;;
    release)    ACTION="release" ;;
    test)       ACTION="test" ;;
    clean)      ACTION="clean" ;;
    install)    ACTION="install"; INSTALL=1 ;;
    run)        ACTION="run"; INSTALL=1; REVERSE=1; LAUNCH=1 ;;
    all)        ACTION="all"; INSTALL=1; RUN_TESTS=1 ;;
    --reverse=*) REVERSE=1; PORT="${arg#*=}" ;;
    --reverse)  REVERSE=1 ;;
    --launch)   LAUNCH=1 ;;
    --serial=*) SERIAL="${arg#*=}" ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "未知参数: $arg" >&2; usage; exit 2 ;;
  esac
done
ACTION="${ACTION:-debug}"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "未找到 JDK：请设置 JAVA_HOME（需要 17+）" >&2
  exit 1
fi
export JAVA_HOME

if [[ ! -f local.properties ]]; then
  SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if [[ -d "$SDK_DIR" ]]; then
    echo "sdk.dir=$SDK_DIR" > local.properties
    echo "已生成 local.properties: sdk.dir=$SDK_DIR"
  else
    echo "找不到 Android SDK：请设置 ANDROID_HOME 或手动写 local.properties" >&2
    exit 1
  fi
fi

ADB_BIN="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}/platform-tools/adb"
[[ -x "$ADB_BIN" ]] || ADB_BIN="adb"

gradle_cmd() {
  if [[ -x ./gradlew ]]; then
    ./gradlew "$@"
  elif command -v gradle >/dev/null 2>&1; then
    gradle "$@"
  else
    echo "既没有 gradlew 也没有 gradle，请先执行: gradle wrapper --gradle-version 8.14.5" >&2
    exit 1
  fi
}

adb_cmd() {
  if [[ -n "$SERIAL" ]]; then
    "$ADB_BIN" -s "$SERIAL" "$@"
  else
    "$ADB_BIN" "$@"
  fi
}

case "$ACTION" in
  debug)   TASK="assembleDebug" ;;
  release) TASK="assembleRelease" ;;
  install) TASK="assembleDebug"; INSTALL=1 ;;
  run)     TASK="assembleDebug"; INSTALL=1; REVERSE=1; LAUNCH=1 ;;
  test)    TASK="testDebugUnitTest"; RUN_TESTS=1 ;;
  clean)   TASK="clean"; CLEAN=1 ;;
  all)     TASK="assembleDebug"; RUN_TESTS=1; INSTALL=1 ;;
esac

echo "==> Gradle: $TASK"
gradle_cmd ":app:$TASK" --console=plain

if [[ "$RUN_TESTS" == "1" && "$ACTION" != "test" ]]; then
  echo "==> Gradle: testDebugUnitTest"
  gradle_cmd :app:testDebugUnitTest --console=plain
fi

if [[ "$INSTALL" == "1" ]]; then
  APK="app/build/outputs/apk/debug/app-debug.apk"
  [[ -f "$APK" ]] || { echo "找不到 $APK" >&2; exit 1; }
  echo "==> 设备:"
  adb_cmd devices
  echo "==> 安装 $APK"
  adb_cmd install -r "$APK"
  if [[ "$REVERSE" == "1" ]]; then
    echo "==> adb reverse tcp:$PORT"
    adb_cmd reverse "tcp:$PORT" "tcp:$PORT"
  fi
  if [[ "$LAUNCH" == "1" ]]; then
    echo "==> 启动 net.agents.mobile/.MainActivity"
    adb_cmd shell am start -n net.agents.mobile/.MainActivity
  fi
fi

if [[ "$CLEAN" != "1" && "$ACTION" != "test" ]]; then
  APK="app/build/outputs/apk/debug/app-debug.apk"
  [[ -f "$APK" ]] && echo "==> 产物: $APK ($(du -h "$APK" | cut -f1))"
fi
