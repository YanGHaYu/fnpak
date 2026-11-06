# FNPak

[![build-server](https://github.com/YanGHaYu/fnpak/actions/workflows/build-server.yml/badge.svg?branch=main)](https://github.com/YanGHaYu/fnpak/actions/workflows/build-server.yml)
[![build-web](https://github.com/YanGHaYu/fnpak/actions/workflows/build-web.yml/badge.svg?branch=main)](https://github.com/YanGHaYu/fnpak/actions/workflows/build-web.yml)

**PikPak 第三方 NAS 客户端 · 非官方**

> **非官方声明**：FNPak 是非官方社区项目，与 NihilDigit/Piko、PikPak、飞牛 fnOS 官方均无关联，也未获得其背书或授权。本项目不包含 PikPak 或飞牛的商标素材。
>
> 本项目基于 [NihilDigit/piko](https://github.com/NihilDigit/piko)（AGPL-3.0-or-later）开发，内部 Kotlin 包名 `dev.piko.*` 沿用上游以降低同步冲突。

FNPak 是运行在飞牛 fnOS 上的 Web 服务端：在 NAS 上登录你的 PikPak 账号，用浏览器管理云端文件与下载任务，不需要桌面客户端。

## 功能

- **目录浏览**：文件/文件夹层级浏览，卡片/表格两套布局，桌面与移动端自适应
- **多选与文件夹下载**：同目录文件与文件夹混合多选，合并为同一批次；文件夹服务端递归展开
- **多线程分段下载**：默认 8 段（可配 1–32），本地断点续传，全局连接预算
- **离线任务**：磁力/分享链接入库，查看进度与子项，支持多选删除
- **回收站**：列出、恢复、彻底删除（永久删除前二次确认并写出目标名称）
- **文件操作**：重命名、批量移动、批量复制（移动/复制逐条返回失败原因）
- **目录排序**：时间 / 文件名 / 文件类型 × 正序 / 倒序，默认「时间倒序 + 文件夹恒定在前」，偏好本地持久化（天然排序）
- **目录行整行点击**：点行内任意位置即触发动作——文件夹进目录、视频直接播放、图片/音频/PDF/文本直接预览、单卷压缩包直接解压、`.torrent` 直接打开种子清单；不可预览文件先弹确认（文件名 + 大小）再下载；行内菜单快捷路径仍保留，多选模式下点行只切换勾选
- **在线预览**：图片、音频、视频（复用播放器）、PDF、纯文本与代码、Markdown；文本走同源代理并只读前 1MB，UTF-8 失败回退 GBK，二进制命中即拒绝预览；Markdown 经 DOMPurify 净化渲染
- **在线解压**：zip/rar/7z 单卷解压到当前目录，加密包弹窗输入密码、错误可重输（解压落点与额度/空间限制**待真实账号验证**）
- **种子打开 / 离线**：`.torrent` 展示文件清单与总大小，可「加入离线」或「离线下载」直接提交；BT v2 暂不支持（直链拉取与离线成功率**待真实账号验证**）
- **主题**：设置页可选「跟随系统 / 浅色 / 深色」，默认跟随系统并随设备深浅色实时切换；页头太阳/月亮按钮为显式浅/深切换（点过之后不再跟随系统，可回设置页改回）
- **设置**：访问口令、下载目录、授权目录、主题、退出登录
- **移动端**：≤768px 卡片布局，44px 触控目标，批量操作条分层，无横向滚动
- **自包含**：前端资源打包进服务端，离线可用，无 CDN 依赖

## 安装（fnOS）

1. 在 [Releases](https://github.com/YanGHaYu/fnpak-fpk/releases) 下载 `fnpak.fpk`（或自行构建）
2. fnOS「应用中心 → 手动安装」上传 fpk
3. 首次打开会进入「设置访问口令」向导，设置后进入
4. 在「应用设置 → 访问权限」给 FNPak 授权至少一个共享目录；也可在 FNPak 设置页选择下载目录
5. 浏览器访问 `http://<NAS>:18080`（fpk 内 `cmd/main` 以 `--host 0.0.0.0` 启动；如需限制监听地址请改该脚本）

> 升级/迁移老用户（原 `piko` 包）请看下文「从 Piko 迁移」。

## 构建

### 本机（Linux，免 Docker）

前置：

- JDK 21（`JDK` 或 `JAVA_HOME` 指向）
- Linux 版 `fnpack` 1.2.1：<https://static2.fnnas.com/fnpack/fnpack-1.2.1-linux-amd64>
  （sha256 `72d2a4095da676b64510b023731a227b369d80f8079bc45ff8a2f802ec0480c1`）
- 系统工具：`tar`、`rsync`、`readelf`、`file`、`sha256sum`

```bash
# 1) 构建服务端
./gradlew :server:installDist --configure-on-demand --no-daemon

# 2) 打 fpk（appname/版本全部来自 fnos/manifest）
FNPACK=/path/to/fnpack ./scripts/build-fpk.sh
# 产物：dist/fnpak.fpk + dist/sha256sum.txt

# 3) 校验权限与 GLIBC
./scripts/check-fpk.sh build/fpk/stage
```

> 必须在 Linux（或 WSL Ubuntu）上执行 fnpack，Windows 侧打包会丢失 0755 执行位。
>
> **默认构建只包含 `:shared` 与 `:server`，只需 JDK 21**，不带任何 `-P` 属性即可；
> 桌面开发/桌面打包需要完整模块（`:desktopApp` 等），此时显式加 `-Pfnpak.full=true` 并安装 JDK 25，
> 这是预期行为而非故障。兼容旧属性 `-Pfnpak.fpkOnly`（显式 `false` 等价于 `full=true`，冲突时 `full=true` 优先）。

### GitHub Actions

- `build-server.yml`：PR/push 自动构建 `fnpak.fpk`，校验 0755 与 GLIBC ≤ 2.36，上传 artifact
- `build-web.yml`：`web-ui/` 变更时构建前端并校验 `server/src/main/resources/web` 与产物一致
- `release.yml`：推送 `v*` tag 时构建并发布 Release（含源码 tag 链接，履行 AGPL §6/§13）

三个工作流均**不需要 secrets**；Release 使用 `GITHUB_TOKEN`。

## 已知限制

- **未做真实账号端到端验证的部分**：PikPak 登录/验证码、离线任务真实入库、分享链接解析等依赖第三方服务的行为，仓库内测试使用 mock/fixture；不同账号与地区可能表现不同
- **转码档单流**：视频转码播放同一时间只支持一个流，多开会被拒绝
- **目录排序只覆盖已取回的一页**：排序作用于当前已加载的目录数据（≤100 项），第 101 项之后不参与排序；跨页全量排序留待后续版本
- **在线解压限制**：只支持 zip/rar/7z **单卷**且只能**整包**解压到当前目录；分卷（`.part*.rar`、`.7z.00N`、`.z0N`、`.r0N`）与 `tar` 不显示入口；加密包密码流程、额度不足与网盘空间不足的真实行为**待真实账号验证**
- **种子限制**：暂不支持 BT v2（`file tree`）种子；真实 PikPak 直链拉取与磁力离线成功率**待真实账号验证**
- **下载子项行不支持长按多选**：本机下载的父任务行、离线任务行、回收站行可长按进入选择模式；展开后的下载**子项行**没有子项级选择态，长按不会进入多选（后续版本补齐）
- **回收站首页 100 条**：回收站列表首屏最多展示 100 条，更多需要配合搜索/分页能力（后续版本完善）
- **反代下来源 IP**：0.6.x 记录的是直连地址；经反向代理时日志 IP 可能失真，不要把日志 IP 当作审计依据
- **AGPL 义务**：通过网络使用本服务时，你运营的修改版也必须向使用者提供 Corresponding Source（见下）

## 源码与许可证（AGPL §13）

本项目以 **AGPL-3.0-or-later** 发布，完整文本见 [LICENSE](LICENSE)。

- 源码仓库：<https://github.com/YanGHaYu/fnpak>
- 每个 Release 的正文都包含与二进制完全对应的源码 tag 链接

如果你修改并在网络上运行本程序，必须向所有远程使用者提供你修改版的 Corresponding Source。

## 从 Piko 迁移（appname `piko` → `fnpak`）

升级后安装目录从 `/var/apps/piko` 变为 `/var/apps/fnpak`，旧数据仍在 `/var/apps/piko`。

**方案 A：迁移数据（保留口令与任务）**

```bash
# 1) 先在应用中心停止旧 Piko
# 2) 用 root/SSH 复制运行时数据（按需）
mkdir -p /var/apps/fnpak/var
cp -a /var/apps/piko/var/access-token        /var/apps/fnpak/var/ 2>/dev/null || true
cp -a /var/apps/piko/var/settings.json       /var/apps/fnpak/var/ 2>/dev/null || true
cp -a /var/apps/piko/var/download-tasks.json /var/apps/fnpak/var/ 2>/dev/null || true
cp -a /var/apps/piko/var/accounts*           /var/apps/fnpak/var/ 2>/dev/null || true
cp -a /var/apps/piko/var/downloads           /var/apps/fnpak/var/ 2>/dev/null || true
# 3) 安装 fnpak.fpk，首次启动前再确认权限与属主
chown -R fnos:fnos /var/apps/fnpak/var 2>/dev/null || true
chmod 0600 /var/apps/fnpak/var/access-token 2>/dev/null || true
```

**方案 B：不迁移**：安装后重新设置访问口令，并在设置页重新选择下载目录即可；云端文件不受影响，已下载文件仍在原目录。

注意：

- `settings.json` 里若写有旧的下载目录，仍可直接使用；也可在新设置页改到新目录
- 登录会话 Cookie 名从 `piko_session` 改为 `fnpak_session`，迁移后需要重新登录（口令不变）
- fnOS 的目录授权按 appname 隔离，新包需要在「应用设置 → 访问权限」重新授权共享目录
- 旧包若被卸载，部分系统可能清理 `/var/apps/piko`，请先复制再卸载

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

## 致谢

- [NihilDigit/piko](https://github.com/NihilDigit/piko)：上游项目与 PikPak SDK
- Ktor、Kotlin、Element Plus、Vue、sqlite-jdbc 等开源依赖（见 [THIRD-PARTY.md](THIRD-PARTY.md)）
- 飞牛 fnOS 提供的打包规范与运行环境
