#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PROJECT="ComagicMobile.xcodeproj"
SCHEME="ComagicMobile"
DD="DerivedData"
APP="$DD/Build/Products/Debug-iphonesimulator/ComagicMobile.app"
BUNDLE_ID="net.pdd.comagic.mobile"
DEST="${DEST:-platform=iOS Simulator,name=iPhone 18 Pro}"
ACTION="${1:-build}"

usage() {
  cat <<'EOF'
用法: ./build.sh [build|test|run|gen|clean]

  build   编模拟器 Debug（默认）
  test    跑单元测试
  run     编译、装到模拟器并启动
  gen     重新生成 xcodeproj（改过 project.yml 后用）
  clean   清理 DerivedData

环境变量: DEST 覆盖目标模拟器，例如 DEST='platform=iOS Simulator,name=iPhone 17'
EOF
}

gen() {
  command -v xcodegen >/dev/null 2>&1 || { echo "缺少 xcodegen: brew install xcodegen" >&2; exit 1; }
  xcodegen generate
}

build() {
  [[ -d "$PROJECT" ]] || gen
  xcodebuild -project "$PROJECT" -scheme "$SCHEME" -destination "$DEST" \
    -configuration Debug -derivedDataPath "$DD" build 2>&1 | tail -3
}

case "$ACTION" in
  build) build ;;
  gen)   gen ;;
  test)  [[ -d "$PROJECT" ]] || gen
         xcodebuild -project "$PROJECT" -scheme "$SCHEME" -destination "$DEST" \
           -derivedDataPath "$DD" test 2>&1 | grep -E "Test Case|TEST (SUCCEEDED|FAILED)|error:" | tail -20 ;;
  run)   build
         xcrun simctl boot "iPhone 18 Pro" 2>/dev/null || true
         xcrun simctl install booted "$APP"
         xcrun simctl launch booted "$BUNDLE_ID" ;;
  clean) rm -rf "$DD" ;;
  -h|--help|help) usage ;;
  *)     usage; exit 1 ;;
esac
