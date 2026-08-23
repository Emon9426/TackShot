#!/usr/bin/env bash
# 钉图 TackShot 构建脚本（MinGW-w64 GCC，产物 dist/TackShot.exe）
set -e
cd "$(dirname "$0")"

GCC="${GCC:-}"
if [ -z "$GCC" ]; then
  if [ -x "tools/w64devkit/bin/g++.exe" ]; then
    GCC="tools/w64devkit/bin/g++.exe"
    PATH="$(pwd)/tools/w64devkit/bin:$PATH"
    export PATH
  elif command -v g++ >/dev/null 2>&1; then GCC="$(command -v g++)"
  else echo "错误：未找到 g++（请先解压 w64devkit 到 tools/ 或安装 MinGW-w64）"; exit 1; fi
fi

mkdir -p dist
"$GCC" -std=c++20 -municode -mwindows -O2 -static \
  -DUNICODE -D_UNICODE \
  -Wall -Wno-unused-parameter \
  -o dist/TackShot.exe \
  src/app.cpp src/capture.cpp src/pin.cpp src/editor.cpp src/config.cpp src/util.cpp \
  -lgdiplus -lcomctl32 -lcomdlg32 -lshell32 -lole32 -luser32 -lgdi32 -ladvapi32

echo "构建完成: dist/TackShot.exe"
ls -la dist/TackShot.exe
