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
# 一键：installDist -> jlink -> jpackage -> fnpack（appname/version 全部读 fnos/manifest）
JDK="$HOME/opt/jdk-21" FNPACK="$HOME/opt/fnpack" ./build-nodev.sh

# 等价入口，CI 用的就是它；已单独跑过 installDist 时加 SKIP_INSTALL_DIST=1 可跳过重复构建
JDK="$HOME/opt/jdk-21" FNPACK="$HOME/opt/fnpack" ./scripts/build-fpk.sh

# 校验 0755 / GLIBC
./scripts/check-fpk.sh build/fpk/stage
```

### 默认构建 = 服务端 / fpk（只需 JDK 21）

服务端与 fpk 链路只需要 JDK 21。完整工程里的 `:desktopApp` 在**配置阶段**就会解析 JDK 25 工具链
（Windows 原生能力依赖 JDK 22+ 的 FFM，见 `desktopApp/build.gradle.kts`）；机器上没有 JDK 25 时，
Gradle 会经 `foojay-resolver-convention` 自动下载，而该插件 0.9.0 在 Gradle 9.6 上会抛
`Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field ... IBM_SEMERU`，
看起来与 fpk 无关，实际是 `:desktopApp` 的配置副作用。

因此 `settings.gradle.kts` 的**默认行为**只 include `:shared` 与 `:server`：

```bash
# 不带任何 -P 属性即可，只需要 JDK 21，不会评估桌面模块
./gradlew :server:installDist --configure-on-demand --no-daemon
```

- 默认：只 include `:shared` 与 `:server`，不评估 `:app` / `:ui` / `:desktopApp` / `:cli` / `:shots`，
  根任务 `run` 也不注册。`./gradlew projects` 只会列出这两个模块。
- `-Pfnpak.full=true`：include 全部模块（含桌面/CLI/截图），**需要 JDK 25**，根 `run` 同时可用。
  这是桌面开发与桌面打包的预期前提，不是故障。
- 兼容旧属性 `-Pfnpak.fpkOnly`：显式传 `false` 等价于 `-Pfnpak.full=true`；两者冲突时 `full=true` 优先。
- `-Porg.gradle.java.installations.auto-download=false`：工具链缺失时立即报错，不联网解析。
  `scripts/build-fpk.sh` 固定带上它，CI 也按同样参数构建，不再写
  `~/.gradle/gradle.properties` 的 `org.gradle.java.installations.paths`。

注意：桌面模块源码仍在原位，只是默认不参与配置；需要桌面开发时显式加 `-Pfnpak.full=true`。
根任务 `run` 也只在 `full=true` 时注册，默认构建下 `./gradlew tasks` 里看不到它属正常现象。

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `Permission denied: ./gradlew` | Windows 检出丢失执行位或 CRLF | `chmod +x gradlew`；确认 `.gitattributes` 已含 `gradlew text eol=lf` |
| `fnpack` 产物里脚本不可执行 | 在 Windows 侧打包 | 改在 Linux/WSL 打包；脚本会 `chmod 0755` |
| `JvmVendorSpec ... IBM_SEMERU` | 传了 `-Pfnpak.full=true` 且机器没有 JDK 25，`:desktopApp` 配置期走 foojay 0.9 的异常路径 | 只构建 server/fpk 时不要带 `full=true`（默认即是）；需要桌面模块就装 JDK 25 |
| `No matching toolchains found ... languageVersion=21` | `auto-download=false` 且机器没有 JDK 21 | 安装 JDK 21 并设 `JAVA_HOME`/`JDK`；不要靠自动下载 |
| `configure-on-demand` 仍配置 `:desktopApp` | 显式传了 `-Pfnpak.full=true` 或 `-Pfnpak.fpkOnly=false` | 去掉该属性即回到默认 fpk-only |
| 前端页面 404 / 旧资源 | `server/src/main/resources/web` 未同步 | `cd web-ui && npm run build` 后同步 `dist/index.html` 与 `dist/assets`；CI `build-web.yml` 会校验 |
| `.jpackage.xml` 版本与 manifest 不一致 | 复用了旧的 app-image | 删除构建工作目录后重跑，确保 jpackage 每次重新生成 |

## 回滚思路

- 构建失败不会改动仓库内已提交的 `server/src/main/resources/web`；前端回滚只需 `git checkout -- server/src/main/resources/web`
- fpk 产物在 `dist/`（已被 `.gitignore` 忽略）；回滚安装包直接换回上一版 fpk
- `fnos/manifest` 的版本号是唯一版本源；回退版本时同时回退 `ServerConfig.VERSION` 与前端 `package.json`
