# 第三方依赖与许可证

本表按实际构建产物列出主要依赖。完整许可证文本见各依赖包内 `LICENSE` / `META-INF`，以及 fpk 内 `app/lib/runtime/legal/`（JDK）。

## 服务端（Kotlin / JVM）

| 依赖 | 版本 | 许可证 | 链接 |
|---|---|---|---|
| Ktor | 3.5.2 | Apache-2.0 | https://ktor.io/ |
| sqlite-jdbc | 3.54.1.0 | Apache-2.0 | https://github.com/xerial/sqlite-jdbc |
| pikpak-kotlin | 2.5.0 | MIT | https://github.com/NihilDigit/pikpak-kotlin |
| OkHttp | 随 pikpak-kotlin | Apache-2.0 | https://square.github.io/okhttp/ |
| kotlinx-coroutines / serialization | 随 Kotlin | Apache-2.0 | https://github.com/Kotlin |
| SLF4J / Logback | 随 Ktor | MIT / EPL-1.0 | https://www.slf4j.org/ |
| Kotlin | 2.x | Apache-2.0 | https://kotlinlang.org/ |

## 前端（web-ui）

| 依赖 | 版本 | 许可证 | 链接 |
|---|---|---|---|
| Vue | 3.5.13 | MIT | https://github.com/vuejs/core |
| vue-router | 4.5.0 | MIT | https://github.com/vuejs/router |
| Element Plus | 2.9.1 | MIT | https://github.com/element-plus/element-plus |
| Vite | 5.4.x（构建期） | MIT | https://github.com/vitejs/vite |

## 运行时（随 fpk 分发）

| 组件 | 版本/来源 | 许可证 | 说明 |
|---|---|---|---|
| OpenJDK（jlink 裁剪运行时） | 21.0.2 | GPLv2 + Classpath Exception | fpk 内 `app/lib/runtime/legal/` 保留完整许可证与 Assembly Exception；可随应用分发，不得使用其商标暗示背书 |

## 打包工具（不随仓库/产物分发）

| 工具 | 版本 | 说明 |
|---|---|---|
| fnpack | 1.2.1 linux-amd64 | 构建时从飞牛官方直链下载并做 sha256 校验，不纳入仓库或 fpk |

## 合规提示

- Apache-2.0 依赖如带 `NOTICE` 文件，分发时需一并保留；本仓库未修改这些依赖。
- MIT 依赖保留其版权与许可声明即可。
- 本项目自身以 AGPL-3.0-or-later 分发；上述依赖不会改变本项目的许可证义务。
