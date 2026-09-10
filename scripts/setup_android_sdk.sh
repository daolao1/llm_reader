#!/usr/bin/env bash
# 安装 Android SDK Command-line Tools + platform-tools + platforms;android-34 + build-tools;34.0.0
# 使用方法: bash scripts/setup_android_sdk.sh [安装目录]
set -euo pipefail

SDK_DIR="${1:-$HOME/Android/Sdk}"
CMDLINE_VER="11076708_latest"   # cmdline-tools 13.0
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VER}.zip"

mkdir -p "$SDK_DIR/cmdline-tools"
cd "$SDK_DIR/cmdline-tools"

if [[ ! -d "latest/bin" ]]; then
    echo ">>> 下载 cmdline-tools..."
    tmpzip="$(mktemp --suffix=.zip)"
    curl -fL --retry 3 -o "$tmpzip" "$CMDLINE_URL"
    rm -rf cmdline-tools latest
    unzip -q "$tmpzip"
    rm -f "$tmpzip"
    mv cmdline-tools latest
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

echo ">>> 接受许可..."
yes | sdkmanager --licenses >/dev/null

echo ">>> 安装平台和构建工具..."
sdkmanager \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0"

cat <<EOF

=========================================================
Android SDK 已安装到: $SDK_DIR

请将下面两行加入 ~/.bashrc 或 ~/.zshrc:

    export ANDROID_HOME="$SDK_DIR"
    export ANDROID_SDK_ROOT="$SDK_DIR"
    export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"

并在工程根目录创建 local.properties:

    sdk.dir=$SDK_DIR
=========================================================
EOF
