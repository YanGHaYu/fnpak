# CHANGELOG

本项目从上游 Piko 的 fnOS 服务端分支演化而来。版本号为本 fork 自有编号；**0.7.0 起产品名为 FNPak**（`appname` 从 `piko` 改为 `fnpak`）。

## 0.7.0 — 更名 FNPak（破坏性）

- 产品名改为 **FNPak**：`appname=fnpak`、`display_name=FNPak`、桌面入口 `fnpak.main`
- 可执行/构件名改为 `fnpak-server`，安装目录从 `/var/apps/piko` 变为 `/var/apps/fnpak`
- 服务端：日志前缀/启动日志改为 FNPak，新增 `APP_NAME`，健康检查返回 `app`；Cookie 改为 `fnpak_session`；媒体响应头改为 `X-FNPak-Media-Mode`
- 前端：页面标题、页头品牌、设置页版本标签改为 FNPak
- 新增 `fnos/` 打包骨架、`scripts/build-fpk.sh`、`scripts/check-fpk.sh` 与 GitHub Actions（build-server/build-web/release）
- 补齐 NOTICE、THIRD-PARTY、SECURITY、CODE_OF_CONDUCT、CHANGELOG、Issue/PR 模板
- 图标更换为自绘中性设计（64×64 / 256×256）
- 迁移：见 README「从 Piko 迁移」

## 0.6.2

- 退出登录从页头移到设置页账号区块
- 回收站改为首页第四个平行 Tab（目录 / 本机下载 / 离线任务 / 回收站），`/trash` 深链仍可直达
- 修复批量下载展开后子项看不全：SSE 进度事件补齐 `fileId/parentTaskId/groupId`，前端按 id 合并而非整体替换；子项始终以 `GET /api/downloads` 全量为准；子项显示「共 N 项，显示 M 项」

## 0.6.1

- 行内「⋯」改为表格独立最后一列，移动端固定卡片右上角
- 批量菜单去掉重命名（多选不提供重命名）
- 移动端批量操作条分层：常见操作常驻，移动到/复制到收进「更多」
- 选中数量与大小文案独占一行，按钮另起一行；移动端按钮仍 44px

## 0.6.0

- 新增回收站页面（`/trash`）：恢复、彻底删除（永久删除前二次确认并写出目标名）
- 全站行内操作收敛为单个「⋯」图标浮层；行内操作只作用于当前行
- 删除类操作二次确认并写出目标名称

## 0.5.0

- 云端文件/文件夹重命名、批量移动、批量复制
- 移动/复制目录树懒加载，禁止选中自身或子目录；逐条返回成功/失败原因

## 0.4.3

- ≤768px 响应式：表格降级为卡片，触控目标 ≥44px，底部操作条贴底换行

## 0.4.2

- 同目录文件与文件夹混合多选，合并为同一批次；文件夹递归展开与进度聚合

## 0.4.1

- 首次启动改为「设置访问口令」向导；口令 0600 原子写盘，日志只记指纹前缀

## 0.4.0

- 多选下载与整文件夹递归下载；离线任务多选删除；首页三 Tab

## 0.3.0

- 分段并发下载（默认 8 段，可配 1–32）+ 断点续传
- 前端重构为 Vue 3 + Element Plus（自包含离线）；设置拆到 `/settings`

## 0.2.2

- 支持 fnOS「访问权限」运行期授权目录作为下载位置；授权来源与可写性在设置页展示
