#!/usr/bin/env bash
# 连接雷电模拟器并安装/启动 debug APK
# 默认雷电 adb 端口 5555 (单开); 多开为 5556, 5558, 5560 ...
set -euo pipefail

PORT="${1:-5555}"
HOST="${2:-127.0.0.1}"
TARGET="$HOST:$PORT"

if ! command -v adb >/dev/null; then
    echo "未找到 adb，请先执行 scripts/setup_android_sdk.sh 并配置 PATH" >&2
    exit 1
fi

echo ">>> 启动 adb server"
adb start-server >/dev/null

echo ">>> 连接 $TARGET"
adb connect "$TARGET"

echo ">>> 等待设备上线"
adb -s "$TARGET" wait-for-device

echo ">>> 已连接设备:"
adb devices

cd "$(dirname "$0")/.."
echo ">>> 构建 + 安装 debug APK"
./gradlew :app:installDebug

echo ">>> 启动应用"
adb -s "$TARGET" shell am start -n com.zzy.llmreader.debug/com.zzy.llmreader.MainActivity

echo ">>> 实时日志 (Ctrl+C 退出)"
adb -s "$TARGET" logcat -v time --pid="$(adb -s "$TARGET" shell pidof com.zzy.llmreader.debug | tr -d '\r')" 2>/dev/null \
    || adb -s "$TARGET" logcat -v time | grep -iE "llmreader|androidruntime"
