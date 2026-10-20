#!/usr/bin/env bash
# 创建并无窗口启动 Android 模拟器（需要 /dev/kvm）
set -euo pipefail
SDK="${ANDROID_HOME:-/opt/android-sdk}"
IMAGE="system-images;android-34;google_apis;x86_64"
AVD=ocr
if ! "$SDK/cmdline-tools/latest/bin/avdmanager" list avd -c | grep -qx "$AVD"; then
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_7
fi
exec "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -gpu swiftshader_indirect -no-snapshot "$@"
