#!/usr/bin/env bash
# FNPak fpk 构建（Linux，免 Docker）：installDist -> jlink -> jpackage -> fnpack
# 所有产品名/版本都从 fnos/manifest 读取，改名不需要改本脚本。
#
# 依赖：JDK 21（JDK/JAVA_HOME）、fnpack 1.2.1 linux-amd64（FNPACK 路径或 PATH）
# 官方直链（已在 CI 中做 sha256 校验）：
#   https://static2.fnnas.com/fnpack/fnpack-1.2.1-linux-amd64
#   sha256=72d2a4095da676b64510b023731a227b369d80f8079bc45ff8a2f802ec0480c1
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FPK_SRC=${FPK_SRC:-$ROOT/fnos}
OUT_DIR=${OUT_DIR:-$ROOT/dist}
WORK=${WORK:-$ROOT/build/fpk}
JDK=${JDK:-${JAVA_HOME:-}}
FNPACK=${FNPACK:-fnpack}

fail() { echo "FAIL: $*" >&2; exit 1; }

read_field() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$FPK_SRC/manifest" | tr -d '\r' | head -1
}

APPNAME=$(read_field appname)
VERSION=$(read_field version)
DISPLAY=$(read_field display_name)
[ -n "$APPNAME" ] || fail "manifest 缺少 appname"
[ -n "$VERSION" ] || fail "manifest 缺少 version"
[ -n "$JDK" ] && [ -x "$JDK/bin/jlink" ] || fail "未设置 JDK（需要 JDK 21：JDK 或 JAVA_HOME）"
[ -x "$FNPACK" ] || command -v "$FNPACK" >/dev/null 2>&1 || fail "找不到 fnpack（FNPACK 指向 linux-amd64 可执行文件）"

DIST_DIR="$ROOT/server/build/install/${APPNAME}-server"
[ -d "$DIST_DIR/lib" ] || fail "缺少 $DIST_DIR，请先跑 ./gradlew :server:installDist"
[ -f "$DIST_DIR/lib/server.jar" ] || fail "installDist 里找不到 server.jar"

rm -rf "$WORK"
mkdir -p "$WORK/runtime" "$WORK/image" "$WORK/stage"

echo "== jlink =="
"$JDK/bin/jlink" \
  --add-modules java.base,java.logging,java.management,java.security.sasl,java.naming,java.net.http,java.transaction.xa,java.xml,java.sql,jdk.crypto.ec,jdk.unsupported \
  --output "$WORK/runtime" \
  --strip-debug --no-header-files --no-man-pages --compress=2

echo "== jpackage app-image: ${APPNAME}-server =="
"$JDK/bin/jpackage" --type app-image --name "${APPNAME}-server" \
  --input "$DIST_DIR/lib" \
  --main-jar server.jar --main-class dev.piko.server.MainKt \
  --runtime-image "$WORK/runtime" --dest "$WORK/image" \
  --app-version "$VERSION" --vendor "$APPNAME" --description "${DISPLAY} fnOS server · 非官方" \
  --java-options "-Djava.awt.headless=true"

echo "== assemble fpk =="
STAGE="$WORK/stage"
cp -a "$FPK_SRC/manifest" "$FPK_SRC/config" "$FPK_SRC/cmd" "$STAGE/"
if [ -d "$FPK_SRC/wizard" ]; then cp -a "$FPK_SRC/wizard" "$STAGE/"; fi
for f in "$FPK_SRC"/ICON*.PNG; do
  if [ -f "$f" ]; then cp -a "$f" "$STAGE/"; fi
done
cp -a "$WORK/image/${APPNAME}-server" "$STAGE/app"
cp -a "$FPK_SRC/app/ui" "$STAGE/app/ui"

# 权限归一化：数据文件 0644，脚本/启动器/运行时 bin 0755
find "$STAGE" -type f -exec chmod 0644 {} +
find "$STAGE/cmd" -type f -exec chmod 0755 {} +
find "$STAGE/app/bin" "$STAGE/app/lib/runtime/bin" -type f -exec chmod 0755 {} +
chmod 0755 "$STAGE/app/lib/runtime/lib/jspawnhelper" 2>/dev/null || true
bash "$ROOT/scripts/check-fpk.sh" "$STAGE"

echo "== fnpack build =="
(cd "$STAGE" && "$FNPACK" build)

mkdir -p "$OUT_DIR"
cp "$STAGE/${APPNAME}.fpk" "$OUT_DIR/${APPNAME}.fpk"
(cd "$OUT_DIR" && sha256sum "${APPNAME}.fpk" | tee sha256sum.txt)
echo "done: $OUT_DIR/${APPNAME}.fpk"
