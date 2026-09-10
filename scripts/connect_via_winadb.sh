#!/usr/bin/env bash
# 通过 Windows 上的 adb server 调试雷电模拟器（WSL2 推荐方式）
# 前置：先在 Windows 上以管理员双击 scripts/windows_expose_adb.bat
set -euo pipefail

WIN_HOST="${1:-$(ip route | awk '/default/{print $3; exit}')}"
export ADB_SERVER_SOCKET="tcp:${WIN_HOST}:5037"
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"

echo ">>> 使用 Windows ADB server: $ADB_SERVER_SOCKET"

if ! adb devices 2>/dev/null | grep -E "device$|emulator" >/dev/null; then
    echo "未找到设备，请确认："
    echo "  1) 雷电模拟器已开启「ADB 调试 → 开启本地连接」"
    echo "  2) Windows 上已运行 scripts/windows_expose_adb.bat（管理员）"
    echo "  3) 当前 WSL 默认网关 = $WIN_HOST"
    adb devices
    exit 1
fi

adb devices

cd "$(dirname "$0")/.."
echo ">>> 构建并安装 debug APK"
./gradlew :app:installDebug

DEVICE="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
echo ">>> 设备: $DEVICE"
echo ">>> 启动应用"
adb -s "$DEVICE" shell am start -n com.zzy.llmreader.debug/com.zzy.llmreader.MainActivity

echo ">>> Logcat（Ctrl+C 退出）"
sleep 1
PID="$(adb -s "$DEVICE" shell pidof com.zzy.llmreader.debug | tr -d '\r')"
if [ -n "$PID" ]; then
    adb -s "$DEVICE" logcat -v time --pid="$PID"
else
    adb -s "$DEVICE" logcat -v time | grep -iE "llmreader|androidruntime"
fi
