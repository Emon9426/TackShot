#!/usr/bin/env bash
# 诊断工具构建与运行（不进发行包）
set -e
cd "$(dirname "$0")/.."   # ai/
JDK="/c/Program Files/Java/jdk-18.0.2.1"
CP="lib/copilot-sdk-java-1.0.11.jar;lib/jackson-annotations-2.22.jar;lib/jackson-core-2.22.1.jar;lib/jackson-databind-2.22.1.jar;lib/jackson-datatype-jsr310-2.22.1.jar"
mkdir -p build/diag
"$JDK/bin/javac" --release 17 -encoding UTF-8 -proc:none -cp "$CP" -d build/diag tools/DiagCli.java
echo COMPILED
