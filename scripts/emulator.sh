#!/usr/bin/env bash
# 创建并无窗口启动 Android 模拟器（需要 /dev/kvm）
# 用法：scripts/emulator.sh [-port 5556]；多台并行时用环境变量 AVD=ocr2 区分
set -euo pipefail
SDK="${ANDROID_HOME:-/opt/android-sdk}"
IMAGE="system-images;android-34;google_apis;x86_64"
AVD="${AVD:-ocr}"
if ! "$SDK/cmdline-tools/latest/bin/avdmanager" list avd -c | grep -qx "$AVD"; then
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_7
fi
exec "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect -no-snapshot "$@"
