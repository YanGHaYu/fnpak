# 构建环境说明（Linux / WSL，免 Docker）

> 本文只记录可复用的构建前置、命令与排错思路，**不包含任何个人机器路径、用户名或临时目录**。
> 仓库自身的模块划分与版本约定见根目录 README。

## 推荐环境

- Ubuntu 22.04/24.04（GitHub Actions 用 `ubuntu-24.04`）或 WSL2 Ubuntu
- JDK 21（Temurin 或任意 OpenJDK 21 均可）
- Node.js 22（只构建前端时需要）
- `tar`、`rsync`、`readelf`、`file`、`sha256sum`
- Linux 版 `fnpack` 1.2.1（构建 fpk 时需要；Windows 版会丢失执行位，不要用）

## 一次性准备（示例）

```bash
# JDK 与 fnpack 放到用户目录下的工具目录，避免污染系统
mkdir -p "$HOME/opt"
# 例：解压 JDK 21 与 fnpack 到 $HOME/opt/jdk-21 / $HOME/opt/fnpack
chmod +x "$HOME/opt/fnpack"
"$HOME/opt/jdk-21/bin/java" -version
"$HOME/opt/fnpack" --help >/dev/null
```

## 构建步骤

```bash
./gradlew :server:installDist --configure-on-demand --no-daemon

# 仅打 app-image + fpk（appname/version 全部读 fnos/manifest）
JDK="$HOME/opt/jdk-21" FNPACK="$HOME/opt/fnpack" ./scripts/build-fpk.sh

# 校验 0755 / GLIBC
./scripts/check-fpk.sh build/fpk/stage
```

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `Permission denied: ./gradlew` | Windows 检出丢失执行位或 CRLF | `chmod +x gradlew`；确认 `.gitattributes` 已含 `gradlew text eol=lf` |
| `fnpack` 产物里脚本不可执行 | 在 Windows 侧打包 | 改在 Linux/WSL 打包；脚本会 `chmod 0755` |
| `configure-on-demand` 仍配置 `:desktopApp` | Gradle 版本/任务图变化 | 确认只跑 `:server:installDist`，并检查根 `build.gradle.kts` 的 afterEvaluate |
| 前端页面 404 / 旧资源 | `server/src/main/resources/web` 未同步 | `cd web-ui && npm run build` 后同步 `dist/index.html` 与 `dist/assets`；CI `build-web.yml` 会校验 |
| `.jpackage.xml` 版本与 manifest 不一致 | 复用了旧的 app-image | 删除构建工作目录后重跑，确保 jpackage 每次重新生成 |

## 回滚思路

- 构建失败不会改动仓库内已提交的 `server/src/main/resources/web`；前端回滚只需 `git checkout -- server/src/main/resources/web`
- fpk 产物在 `dist/`（已被 `.gitignore` 忽略）；回滚安装包直接换回上一版 fpk
- `fnos/manifest` 的版本号是唯一版本源；回退版本时同时回退 `ServerConfig.VERSION` 与前端 `package.json`
