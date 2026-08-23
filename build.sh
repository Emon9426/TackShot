#!/usr/bin/env bash
# 钉图 TackShot V2.0（Java 版）构建：javac --release 11 → jar → 组装 release/TackShot
# 依赖：JDK 11+；lib/ 下 jna jar（已在仓库中）
set -e
cd "$(dirname "$0")"

JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

rm -rf build dist/TackShot.jar
mkdir -p build/classes dist

cat > build/javac.args <<'EOF'
--release 11
-encoding UTF-8
-cp "lib/jna-5.14.0.jar;lib/jna-platform-5.14.0.jar"
-sourcepath src-java
-d build/classes
src-java/tackshot/Main.java
EOF

echo "[1/5] 编译 (release 11)..."
"$JAVAC" @build/javac.args

echo "[2/5] 打包 TackShot.jar..."
cat > build/manifest.mf <<'EOF'
Main-Class: tackshot.Main
Class-Path: lib/jna-5.14.0.jar lib/jna-platform-5.14.0.jar
EOF
"$JAR" cfm dist/TackShot.jar build/manifest.mf -C build/classes .

echo "[3/5] 复制运行依赖..."
mkdir -p dist/lib
cp lib/*.jar dist/lib/
unix2dos -q start.bat 2>/dev/null || true
cp start.bat dist/start.bat

echo "[4/5] 组装 release/TackShot ..."
rm -rf release/TackShot release/TackShot-java.zip release/TackShot-win64
mkdir -p release/TackShot/lib release/TackShot/img
cp dist/TackShot.jar release/TackShot/
cp lib/*.jar release/TackShot/lib/
cp start.bat README.md LICENSE THIRD-PARTY-NOTICES.txt release/TackShot/
cp img/*.svg release/TackShot/img/ 2>/dev/null || true

echo "[5/5] 生成 SHA256SUMS 与 zip ..."
(cd release/TackShot && sha256sum TackShot.jar lib/*.jar start.bat README.md LICENSE > SHA256SUMS.txt)
powershell -NoProfile -Command "Compress-Archive -Path 'release/TackShot' -DestinationPath 'release/TackShot-java.zip' -Force" >/dev/null 2>&1 || true

echo "完成：dist/TackShot.jar（本地运行）与 release/TackShot/（发行包，双击 start.bat 启动）"
