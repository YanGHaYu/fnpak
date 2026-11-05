#!/usr/bin/env bash
# 一键构建 fnOS fpk（Linux/WSL，免 Docker）：installDist -> jlink -> jpackage -> fnpack
# 等价于 ./scripts/build-fpk.sh；保留此入口名以兼容既有用法。
#
# 依赖：JDK 21（JDK 或 JAVA_HOME）、fnpack 1.2.1 linux-amd64（FNPACK 或 PATH）。
# 不需要 JDK 25：Gradle 默认只 include :shared 与 :server（fpk-only），桌面模块需 -Pfnpak.full=true。
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
exec "$ROOT/scripts/build-fpk.sh" "$@"
