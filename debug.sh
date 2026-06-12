#!/usr/bin/env bash
# 编译 debug APK，启动（或复用）本机 Android 模拟器，安装并拉起 Wand。
# 对称 ios/debug.sh：让日常调试不依赖真机。
#
# 用法：
#   ./debug.sh                 # 编译 + 装模拟器 + 启动
#   SKIP_BUILD=1 ./debug.sh    # 跳过编译，直接安装现有 APK
#   AVD_NAME=pixel_9 ./debug.sh
#
# 依赖（brew 一键装齐）：
#   brew install openjdk@21
#   brew install --cask android-commandlinetools
#   sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
#     "emulator" "system-images;android-36;google_apis;arm64-v8a"
#   avdmanager create avd -n wand_debug -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_7

set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

AVD_NAME="${AVD_NAME:-wand_debug}"
APP_ID="com.wand.app"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -d "$JAVA_HOME" ]]; then
  echo "错误：找不到 JDK（$JAVA_HOME）。先 brew install openjdk@21。" >&2
  exit 1
fi
if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "错误：找不到 Android SDK（$ANDROID_HOME）。先 brew install --cask android-commandlinetools。" >&2
  exit 1
fi
if [[ ! -f local.properties ]]; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

# 1. 编译
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || echo 0.0.0)"
  echo "==> 编译 debug APK（${VERSION}-debug.$(date +%m%d%H%M)）"
  ./gradlew assembleDebug -PAPP_VERSION_NAME="${VERSION}-debug.$(date +%m%d%H%M)"
fi
if [[ ! -f "$APK_PATH" ]]; then
  echo "错误：没有找到 $APK_PATH，先跑一次 ./gradlew assembleDebug。" >&2
  exit 1
fi

# 2. 启动 / 复用模拟器
if ! avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
  echo "错误：AVD「$AVD_NAME」不存在，先用 avdmanager create avd 创建（见文件头注释）。" >&2
  exit 1
fi

booted_serial() {
  adb devices | awk '/^emulator-/ && $2 == "device" { print $1; exit }'
}

SERIAL="$(booted_serial || true)"
if [[ -z "$SERIAL" ]]; then
  echo "==> 启动模拟器 $AVD_NAME"
  nohup emulator -avd "$AVD_NAME" -netdelay none -netspeed full \
    >/tmp/wand-emulator.log 2>&1 &
  echo "==> 等待模拟器就绪…"
  adb wait-for-device
  # 等系统完成开机（boot_completed=1），最多 180s。
  for _ in $(seq 1 90); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      break
    fi
    sleep 2
  done
  SERIAL="$(booted_serial)"
fi
echo "==> 使用设备：$SERIAL"

# 3. 安装 + 启动
echo "==> 安装 APK"
adb -s "$SERIAL" install -r "$APK_PATH"
echo "==> 启动 Wand"
adb -s "$SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
echo "完成。日志：adb -s $SERIAL logcat --pid=\$(adb -s $SERIAL shell pidof -s $APP_ID)"
