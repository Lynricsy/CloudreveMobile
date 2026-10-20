#!/usr/bin/env bash
# 构建共享核心 XCFramework 并放到 iosApp/Frameworks（XcodeGen 工程引用此路径）
# 用法：scripts/build-ios-framework.sh [debug|release]
set -euo pipefail
cd "$(dirname "$0")/.."
CONFIG="${1:-debug}"
case "$CONFIG" in
  debug) TASK=assembleOpenCloudreveCoreDebugXCFramework ;;
  release) TASK=assembleOpenCloudreveCoreReleaseXCFramework ;;
  *) echo "未知配置：$CONFIG" >&2; exit 1 ;;
esac
./gradlew ":shared:$TASK"
rm -rf iosApp/Frameworks/OpenCloudreveCore.xcframework
mkdir -p iosApp/Frameworks
cp -R "shared/build/XCFrameworks/$CONFIG/OpenCloudreveCore.xcframework" iosApp/Frameworks/
echo "已复制到 iosApp/Frameworks/OpenCloudreveCore.xcframework"
