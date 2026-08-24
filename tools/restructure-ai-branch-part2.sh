#!/usr/bin/env bash
# 重组续传（feature/AI）：步骤 1 与 src-java 迁移已完成，此处补齐 lib/构建脚本/工具/清理
set -e
cd "$(dirname "$0")/.."
[ "$(git branch --show-current)" = "feature/AI" ] || { echo "须在 feature/AI 执行"; exit 1; }

mkdir -p lib
mv ai/lib/*.jar lib/
mv ai/build.sh build.sh
mv ai/start.bat start.bat
mv ai/pom.xml pom.xml
mv ai/README.md README-AI.md
mv ai/tools/DiagCli.java ai/tools/diag-build.sh ai/tools/diag-run.sh tools/
rm -rf ai
git add -A
echo "=== 变更统计（A=新增 D=删除 M=修改 R=重命名）==="
git status --short | awk '{print $1}' | sort | uniq -c
