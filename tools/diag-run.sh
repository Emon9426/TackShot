#!/usr/bin/env bash
# 运行 SDK 启动诊断：假令牌 + 隔离 home（验证 CLI 下载/启动链路与错误形态）
cd "$(dirname "$0")/.."
JDK="/c/Program Files/Java/jdk-18.0.2.1"
CP="build/diag;lib/copilot-sdk-java-1.0.11.jar;lib/jackson-annotations-2.22.jar;lib/jackson-core-2.22.1.jar;lib/jackson-databind-2.22.1.jar;lib/jackson-datatype-jsr310-2.22.1.jar"
rm -rf build/diag-home
mkdir -p build/diag-home
MSYS2_ARG_CONV_EXCL='*' "$JDK/bin/java" -cp "$CP" DiagCli "github_pat_FAKE_DIAGNOSTIC_TOKEN_0000000000000000000000" "build/diag-home"
