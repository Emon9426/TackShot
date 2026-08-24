#!/usr/bin/env bash
# 钉图 TackShot · AI 版构建（feature/AI 分支主体：源码 src-java、产物 dist/release）
# 说明：javac --release 17（官方 Copilot SDK 要求 Java 17+）、classpath 含 lib 全部 jar
# 依赖：JDK 17+（默认取 JAVA_HOME，可用 JAVAC 环境变量覆盖）
# 依赖刷新（升级 SDK 版本时）：改 pom.xml 后执行
#   mvn -f pom.xml dependency:copy-dependencies -DoutputDirectory=lib
set -e
cd "$(dirname "$0")"   # 仓库根（脚本即位于根目录）

if [ -n "$JAVAC" ]; then
    :
elif [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
else
    JAVAC=javac
fi
BIN_DIR="$(dirname "$JAVAC")"
JAR="$BIN_DIR/jar"
"$JAVAC" -version

JARS="jna-5.14.0.jar jna-platform-5.14.0.jar copilot-sdk-java-1.0.11.jar jackson-annotations-2.22.jar jackson-core-2.22.1.jar jackson-databind-2.22.1.jar jackson-datatype-jsr310-2.22.1.jar"
CP=$(printf 'lib/%s;' $JARS)
CPC=$(printf 'lib/%s ' $JARS)   # manifest Class-Path 用相对路径

echo "[1/4] 编译 (release 17)..."
rm -rf build/classes dist/TackShotAI.jar
mkdir -p build/classes dist
"$JAVAC" --release 17 -encoding UTF-8 -proc:none -cp "$CP" -sourcepath src-java -d build/classes src-java/tackshot/Main.java

echo "[2/4] 打包 TackShotAI.jar..."
printf 'Main-Class: tackshot.Main\nClass-Path: %s\n' "$CPC" > build/manifest.mf
"$JAR" cfm dist/TackShotAI.jar build/manifest.mf -C build/classes .
mkdir -p dist/lib
cp lib/*.jar dist/lib/

echo "[3/4] 组装 release/TackShotAI ..."
rm -rf release/TackShotAI
mkdir -p release/TackShotAI/lib
cp dist/TackShotAI.jar release/TackShotAI/
cp lib/*.jar release/TackShotAI/lib/
cp start.bat README-AI.md README.md LICENSE THIRD-PARTY-NOTICES.txt release/TackShotAI/ 2>/dev/null || true

echo "[4/4] 生成 SHA256SUMS 与 zip ..."
(cd release/TackShotAI && sha256sum TackShotAI.jar lib/*.jar start.bat README-AI.md LICENSE > SHA256SUMS.txt)
powershell -NoProfile -Command "Compress-Archive -Path 'release/TackShotAI' -DestinationPath 'release/TackShotAI-win64.zip' -Force" >/dev/null 2>&1 || true

echo "完成：dist/TackShotAI.jar（本地运行）与 release/TackShotAI/（发行包，双击 start.bat 启动）"
