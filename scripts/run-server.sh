#!/usr/bin/env bash
# 在 /tmp/x 编译并启动本地 OpenCloudreve（端口 5212），用于移动端联调
# 模拟器内访问宿主机：http://10.0.2.2:5212
set -euo pipefail
SERVER_REPO="${SERVER_REPO:-/root/Projects/Ling/OpenCloudreve}"
WORK="${WORK:-/tmp/x}"
mkdir -p "$WORK"
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  (cd "$SERVER_REPO" && go build -o "$WORK/cloudreve" .)
fi
cd "$WORK"
if [ ! -f conf.ini ]; then
  printf '[System]\nMode = master\nListen = :5212\n' > conf.ini
fi
exec ./cloudreve -c conf.ini
