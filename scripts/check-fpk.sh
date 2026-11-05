#!/usr/bin/env bash
# FNPak fpk 产物校验：可执行名 / 0755 权限 / ELF 最高 GLIBC 版本
# 用法：scripts/check-fpk.sh <fnos 打包目录 stage>
# 全部检查失败即退出非 0，供 CI 直接 fail。
set -euo pipefail

PKG=${1:?用法: scripts/check-fpk.sh <stage-dir>}
MAX_GLIBC=${MAX_GLIBC:-2.36}

fail() { echo "FAIL: $*" >&2; exit 1; }

[ -f "$PKG/manifest" ] || fail "缺少 $PKG/manifest"
APPNAME=$(sed -n 's/^appname[[:space:]]*=[[:space:]]*//p' "$PKG/manifest" | tr -d '\r' | head -1)
[ -n "$APPNAME" ] || fail "manifest 缺少 appname"

LAUNCHER="$PKG/app/bin/${APPNAME}-server"
[ -f "$LAUNCHER" ] || fail "缺少启动器 $LAUNCHER"
[ -x "$LAUNCHER" ] || fail "启动器不可执行：$LAUNCHER"
[ -f "$PKG/cmd/main" ] || fail "缺少 $PKG/cmd/main"
[ -x "$PKG/cmd/main" ] || fail "cmd/main 不可执行"

# 1) 关键可执行文件必须精确 0755
for f in "$LAUNCHER" "$PKG/cmd/main"; do
  mode=$(stat -c '%a' "$f")
  [ "$mode" = "755" ] || fail "$f 权限为 $mode，要求 0755"
done

# 2) 包内所有带执行位的文件都必须是 0755（防止 fnpack/文件系统带来的 0775、0700）
while IFS= read -r f; do
  mode=$(stat -c '%a' "$f")
  [ "$mode" = "755" ] || fail "可执行文件权限不是 0755：$f（$mode）"
done < <(find "$PKG" -type f -perm /111)

# 3) 所有 ELF 的最高 GLIBC 版本 <= MAX_GLIBC（默认 2.36，兼容 fnOS / Debian 12）
higher() {
  printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1
}
max_seen=0.0
while IFS= read -r f; do
  readelf -h "$f" >/dev/null 2>&1 || continue
  v=$(readelf -V "$f" 2>/dev/null | grep -oE 'GLIBC_[0-9]+(\.[0-9]+)+' | sed 's/^GLIBC_//' | sort -V | tail -1 || true)
  [ -n "$v" ] || continue
  if [ "$(higher "$v" "$MAX_GLIBC")" != "$MAX_GLIBC" ]; then
    fail "$f 需要 GLIBC_$v，超过上限 $MAX_GLIBC"
  fi
  if [ "$(higher "$v" "$max_seen")" = "$v" ]; then max_seen=$v; fi
done < <(find "$PKG" -type f)

echo "OK: appname=$APPNAME launcher=$(basename "$LAUNCHER") 0755=pass glibc_max=$max_seen (limit $MAX_GLIBC)"
