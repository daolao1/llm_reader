#!/usr/bin/env bash
# 通过 Windows 雷电自带的 adb.exe 直接调试（WSL2 最稳方案）
# 雷电启动后已占用 emulator 设备，复用它的 adb.exe 即可，无需网络转发。
set -euo pipefail

ADB="${LDPLAYER_ADB:-/mnt/d/leidian/LDPlayer9/adb.exe}"

if [[ ! -x "$ADB" ]]; then
    echo "未找到雷电 adb.exe: $ADB" >&2
    echo "请通过环境变量指定: LDPLAYER_ADB=/mnt/x/your-path/adb.exe $0" >&2
    exit 1
fi

echo ">>> 使用 $ADB"
"$ADB" start-server >/dev/null 2>&1 || true
"$ADB" devices

DEVICE="$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device"{print $1; exit}')"
if [[ -z "${DEVICE:-}" ]]; then
    echo "未发现 online 设备，请先在雷电中开启 ADB 调试。" >&2
    exit 1
fi
echo ">>> 设备: $DEVICE"

cd "$(dirname "$0")/.."

echo ">>> 构建 debug APK"
./gradlew :app:assembleDebug -q

APK="$(ls -t app/build/outputs/apk/debug/*.apk | head -1)"
echo ">>> 安装 $APK"
"$ADB" -s "$DEVICE" install -r -t "$APK"

echo ">>> 启动 com.zzy.llmreader.debug/.MainActivity"
"$ADB" -s "$DEVICE" shell am start -W -n com.zzy.llmreader.debug/com.zzy.llmreader.MainActivity

echo ">>> Logcat（Ctrl+C 退出）"
sleep 1
PID="$("$ADB" -s "$DEVICE" shell pidof com.zzy.llmreader.debug | tr -d '\r' || true)"
if [[ -n "$PID" ]]; then
    "$ADB" -s "$DEVICE" logcat -v time --pid="$PID"
else
    "$ADB" -s "$DEVICE" logcat -v time \*:W | grep -iE "llmreader|AndroidRuntime"
fi
