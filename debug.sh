#!/usr/bin/env bash
# 编译 debug APK，默认推送到所有 adb 可触达的设备（真机 / 模拟器皆可）。
# 保留模拟器自启能力，作为特殊调用（EMULATOR=1）使用。
#
# 用法：
#   ./debug.sh                 # 编译 + 推送到所有在线设备
#   SKIP_BUILD=1 ./debug.sh    # 跳过编译，直接安装现有 APK 到所有设备
#   SKIP_INSTALL=1 ./debug.sh  # 只编译并复制分发 APK，不安装到设备
#   LAUNCH=1 ./debug.sh        # 安装后顺带拉起 Wand
#   EMULATOR=1 ./debug.sh      # 特殊模式：启动/复用本机模拟器并安装+启动
#   AVD_NAME=pixel_9 EMULATOR=1 ./debug.sh
#
# 依赖（brew 一键装齐）：
#   brew install openjdk@21
#   brew install --cask android-commandlinetools
#   # 仅 EMULATOR 模式需要：
#   sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
#     "emulator" "system-images;android-36;google_apis;arm64-v8a"
#   avdmanager create avd -n wand_debug -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_7

set -euo pipefail

cd "$(dirname "$0")"

# JAVA_HOME：优先用环境已设的；否则按 brew openjdk@21 → @17 兜底。
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  for cand in \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@21 \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@17; do
    if [[ -x "$cand/bin/java" ]]; then JAVA_HOME="$cand"; break; fi
  done
fi
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

AVD_NAME="${AVD_NAME:-wand_debug}"
APP_ID="com.wand.app"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
APK_DIST_DIR="${APK_DIST_DIR:-dist/apk}"
STAMP_FILE="$APK_DIST_DIR/.last-debug-version"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "错误：找不到可用 JDK。先 brew install openjdk@21（或设置 JAVA_HOME）。" >&2
  exit 1
fi
if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "错误：找不到 Android SDK（$ANDROID_HOME）。先 brew install --cask android-commandlinetools。" >&2
  exit 1
fi
if [[ ! -f local.properties ]]; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

repair_build_permissions() {
  local target="$1"
  [[ -e "$target" ]] || return 0
  if find "$target" -maxdepth 3 \( ! -user "$(id -u)" -o ! -group "$(id -g)" \) -print -quit | grep -q .; then
    if ! command -v sudo >/dev/null 2>&1; then
      echo "错误：$target 含有非当前用户拥有的构建文件，但找不到 sudo。" >&2
      echo "请手动执行：chown -R $(id -un):$(id -gn) $target" >&2
      exit 1
    fi
    if [[ ! -t 0 ]] && ! sudo -n true 2>/dev/null; then
      echo "错误：$target 含有非当前用户拥有的构建文件，当前环境又不能输入 sudo 密码。" >&2
      echo "请在终端执行：sudo chown -R $(id -un):$(id -gn) $PWD/$target" >&2
      exit 1
    fi
    echo "==> 修复 $target 权限"
    sudo chown -R "$(id -u):$(id -g)" "$target"
  fi
}

latest_tag_version() {
  local tag
  tag="$(git tag --sort=-v:refname --list 'v*' | head -1 | sed 's/^v//' || true)"
  if [[ -z "$tag" ]]; then
    tag="$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || true)"
  fi
  echo "${tag:-0.0.0}"
}

# 1. 编译
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  repair_build_permissions "app/build"
  VERSION="${1:-$(latest_tag_version)}"
  STAMP="${VERSION}-debug.$(date +%m%d%H%M)"
  echo "==> 编译 debug APK（${STAMP}）"
  ./gradlew assembleDebug -PAPP_VERSION_NAME="$STAMP"
else
  if [[ -f "$STAMP_FILE" ]]; then
    STAMP="$(cat "$STAMP_FILE")"
  else
    STAMP="$(latest_tag_version)-debug.local"
  fi
fi
if [[ ! -f "$APK_PATH" ]]; then
  echo "错误：没有找到 $APK_PATH，先跑一次 ./gradlew assembleDebug。" >&2
  exit 1
fi

mkdir -p "$APK_DIST_DIR"
DIST_APK="$APK_DIST_DIR/wand-v${STAMP}.apk"
cp "$APK_PATH" "$DIST_APK"
printf '%s\n' "$STAMP" > "$STAMP_FILE"
echo "==> 本地分发 APK: $DIST_APK"
echo "    服务端 config.json 可配置 android.apkDir = $(cd "$APK_DIST_DIR" && pwd)"

if [[ "${SKIP_INSTALL:-0}" == "1" ]]; then
  echo "完成：已跳过安装。"
  exit 0
fi

install_to() {
  local serial="$1"
  echo "==> 安装到 $serial"
  if adb -s "$serial" install -r -d "$APK_PATH"; then
    if [[ "${LAUNCH:-0}" == "1" ]]; then
      adb -s "$serial" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    fi
    echo "    ✓ $serial 成功"
  else
    echo "    ✗ $serial 失败" >&2
    return 1
  fi
}

# ── 特殊模式：启动/复用本机模拟器并安装+启动 ──────────────────────────
if [[ "${EMULATOR:-0}" == "1" ]]; then
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
    for _ in $(seq 1 90); do
      if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        break
      fi
      sleep 2
    done
    SERIAL="$(booted_serial)"
  fi
  echo "==> 使用模拟器：$SERIAL"
  LAUNCH=1 install_to "$SERIAL"
  echo "完成。日志：adb -s $SERIAL logcat --pid=\$(adb -s $SERIAL shell pidof -s $APP_ID)"
  exit 0
fi

# ── 默认模式：推送到所有在线设备 ──────────────────────────────────────
# macOS 自带 bash 3.2 没有 mapfile，用 while-read 兜底。
SERIALS=()
while IFS= read -r serial; do
  [[ -n "$serial" ]] && SERIALS+=("$serial")
done < <(adb devices | awk '$2 == "device" { print $1 }')
if [[ "${#SERIALS[@]}" -eq 0 ]]; then
  echo "错误：没有 adb 在线设备。先连真机（adb connect <ip>:5555）或用 EMULATOR=1。" >&2
  exit 1
fi

echo "==> 在线设备 ${#SERIALS[@]} 台：${SERIALS[*]}"
fail=0
for serial in "${SERIALS[@]}"; do
  install_to "$serial" || fail=1
done

if [[ "$fail" -ne 0 ]]; then
  echo "部分设备安装失败。" >&2
  exit 1
fi
echo "全部完成。"
