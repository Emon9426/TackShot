#!/usr/bin/env bash
# 一次性重组脚本：feature/AI 分支——AI 版升为根目录主体，移除无 AI 主版本（代码与构建）。
# 由用户决策的四点执行：保持 AI 专属名 / 仅移代码与构建 / 文档保留 / 合并即切换。
set -e
cd "$(dirname "$0")/.."   # 仓库根

if [ "$(git branch --show-current)" != "feature/AI" ]; then
  echo "错误：必须在 feature/AI 分支执行（当前：$(git branch --show-current)）——先 git checkout -b feature/AI"
  exit 1
fi

echo "[1] 移除无 AI 主版本（代码与构建、旧发行物）"
git rm -r -q src-java
git rm -q build.sh start.bat
git rm -r -q release
git rm -q lib/jna-5.14.0.jar lib/jna-platform-5.14.0.jar
rm -rf dist 2>/dev/null || true   # 主版本可能正在运行占用 dist；忽略删除失败

echo "[2] AI 版升为根目录主体"
mv ai/src-java src-java
mv ai/lib/jna-5.14.0.jar ai/lib/jna-platform-5.14.0.jar \
   ai/lib/copilot-sdk-java-1.0.11.jar ai/lib/jackson-annotations-2.22.jar \
   ai/lib/jackson-core-2.22.1.jar ai/lib/jackson-databind-2.22.1.jar \
   ai/lib/jackson-datatype-jsr310-2.22.1.jar lib/
mv ai/build.sh build.sh
mv ai/start.bat start.bat
mv ai/pom.xml pom.xml
mv ai/README.md README-AI.md
mv ai/tools/DiagCli.java ai/tools/diag-build.sh ai/tools/diag-run.sh tools/

echo "[3] 清理 ai/ 残留（build/dist/release 为忽略的构建产物）"
rm -rf ai

echo "[4] 暂存"
git add -A
git status --short | head -10
echo "...(共 $(git status --short | wc -l) 项变更)"
