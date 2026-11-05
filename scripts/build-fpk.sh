#!/usr/bin/env bash
# FNPak fpk 构建（Linux，免 Docker）：installDist -> jlink -> jpackage -> fnpack
# 所有产品名/版本都从 fnos/manifest 读取，改名不需要改本脚本。
#
# 依赖：JDK 21（JDK/JAVA_HOME）、fnpack 1.2.1 linux-amd64（FNPACK 路径或 PATH）
# 官方直链（已在 CI 中做 sha256 校验）：
#   https://static2.fnnas.com/fnpack/fnpack-1.2.1-linux-amd64
#   sha256=72d2a4095da676b64510b023731a227b369d80f8079bc45ff8a2f802ec0480c1
#
# 本脚本默认先跑 :server:installDist，再打包；CI 若已单独跑过 installDist，
# 可用 SKIP_INSTALL_DIST=1 跳过。installDist 固定带：
#   -Porg.gradle.java.installations.auto-download=false  工具链缺失立即报错，不走 foojay 联网解析
# 模块裁剪已改为 Gradle 默认行为（默认只 include :shared/:server，不评估要 JDK 25 的 :desktopApp），
# 脚本不再显式传 -Pfnpak.fpkOnly=true；需要完整模块时手动加 -Pfnpak.full=true。
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FPK_SRC=${FPK_SRC:-$ROOT/fnos}
OUT_DIR=${OUT_DIR:-$ROOT/dist}
WORK=${WORK:-}
# 工作目录默认在 build/fpk；但 WSL 的 /mnt/e 等 DrvFs 不保存 chmod 位，0755 会退化成 777，
# 这种文件系统上改用 Linux 原生临时目录（产物仍写回 OUT_DIR）。CI 在 ext4 上仍是 build/fpk。
if [ -z "$WORK" ]; then
  WORK="$ROOT/build/fpk"
  mkdir -p "$ROOT/build"
  probe="$ROOT/build/.fpk-mode-probe"
  : > "$probe"
  chmod 755 "$probe" 2>/dev/null || true
  mode=$(stat -c '%a' "$probe" 2>/dev/null || echo '')
  rm -f "$probe"
  if [ "$mode" != "755" ]; then
    WORK="${TMPDIR:-/tmp}/fnpak-fpk"
    echo "NOTE: $ROOT 所在文件系统不保存权限位（probe=$mode），stage 改用 $WORK"
  fi
fi
JDK=${JDK:-${JAVA_HOME:-}}
FNPACK=${FNPACK:-fnpack}
GRADLE_ARGS=${GRADLE_ARGS:---configure-on-demand --no-daemon}

fail() { echo "FAIL: $*" >&2; exit 1; }

# gradlew 需要 JAVA_HOME（或 PATH 里的 java）：只给了 JDK 时同步给 Gradle，两者都没给时从 PATH 反推
if [ -z "${JAVA_HOME:-}" ] && [ -n "$JDK" ]; then
  export JAVA_HOME="$JDK"
fi
if [ -z "$JDK" ]; then
  JAVA_BIN=$(command -v java || true)
  if [ -n "$JAVA_BIN" ]; then
    JDK=$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")
    export JAVA_HOME="$JDK"
  fi
fi

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
if [ "${SKIP_INSTALL_DIST:-0}" != "1" ]; then
  echo "== installDist (JDK 21) =="
  # settings.gradle.kts 默认不 include :desktopApp；关掉自动下载后缺 JDK 21 会直接报错，
  # 不会去 foojay 联网解析（Gradle 9.6 + foojay 0.9 在这条路径上会抛 IBM_SEMERU）
  (cd "$ROOT" && ./gradlew :server:installDist $GRADLE_ARGS \
    -Porg.gradle.java.installations.auto-download=false) \
    || fail "installDist 失败：确认 JAVA_HOME 或 java 指向 JDK 21（:shared/:server 用 jvmToolchain(21)），且未依赖工具链自动下载"
fi
[ -d "$DIST_DIR/lib" ] || fail "缺少 $DIST_DIR，请先跑 ./gradlew :server:installDist"
[ -f "$DIST_DIR/lib/server.jar" ] || fail "installDist 里找不到 server.jar"

rm -rf "$WORK"
mkdir -p "$WORK/image" "$WORK/stage"

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
