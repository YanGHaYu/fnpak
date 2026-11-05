# CHANGELOG

本项目从上游 Piko 的 fnOS 服务端分支演化而来。版本号为本 fork 自有编号；**0.7.0 起产品名为 FNPak**（`appname` 从 `piko` 改为 `fnpak`）。

## 0.8.0 — UI 重构（双端布局）

- 移除 Element Plus（及图标包），改为 **Tailwind CSS v4（Vite 插件）+ reka-ui** 无样式底层 + 自建轻量组件：Button / IconButton / Dialog / Sheet（移动抽屉）/ Popover / Menu / Checkbox / Toast / Progress / Skeleton / Tabs
- 设计 token 化（颜色/间距/字号/圆角/阴影全部 CSS 变量），明暗主题沿用 `fnpak-theme` 键（读不到回退 `piko-theme`，老设置不丢）；断点 `sm 640 / md 768 / lg 1024 / xl 1280`
- 统一 AppShell：桌面（≥1024）顶部品牌 + Tab + 设置入口，内容区限宽 1280 居中；移动（<768）紧凑顶栏 + 底部四 Tab（目录 / 本机下载 / 离线任务 / 回收站）+ 安全区 padding
- 目录、本机下载、离线任务、回收站统一为「行式卡片列表」：主行图标/名称/大小/状态/进度，失败原因与速率在第二行或展开区；完成/失败用色彩与图标区分
- 多选：行首 checkbox，移动端长按 500ms 进入选择模式；批量条移动端固定底部安全区、桌面悬浮内容区底部，列表预留底部空间，不遮最后一行
- 行内 `⋯` 浮层：桌面 hover、移动点击，只作用于当前行；已完成行不出现下载控制按钮
- 大列表：目录/任务/子项改用增量渲染（首屏 40 项、滚动哨兵或「加载更多」追加），320+ 项与展开子项不再一次性挂载；展开仍保留 `childCount > 本地子项数` 补拉
- SSE 逻辑不变：继续按 id merge，子项不会因增量事件丢失归属
- 播放器：移动端全屏弹层，PC 弹层尺寸合理；弹层均支持 esc / 遮罩关闭与键盘操作
- 可访问性：触控目标 ≥44px、`:focus-visible` 可见、对比度达标、弹层有标题与关闭按钮
- 体积：JS 562.1 kB → 282.7 kB（gzip 190.9 → 96.8 kB）；CSS 148.0 kB → 26.9 kB（gzip 21.1 → 6.1 kB）
- 版本 `0.8.0`（manifest / `ServerConfig` / `/api/health` / `package.json` 与构建产出一致）；API 契约完全不变

## 0.7.1 — 只重试失败项（断点续传）

- 新增 `POST /api/downloads/{id}/retry` 与 `POST /api/downloads/groups/{id}/retry`：只挑失败/中断的子项重试，已完成、下载中、排队中的一律不动
- 重试保留 `completedRanges` 与已下字节，只补未完成区间；开始时重新获取直链（不复用过期签名）
- 防重复：运行或排队中的文件重试返回 409 与可读提示（如「该文件正在下载中，请等它结束或先暂停」）
- 失败原因可读化：HTTP 状态、直链失效、云端 404、磁盘空间不足、下载目录不可写，原始异常只进日志；SSE `download.progress` 新增 `error` 字段
- UI：子项行内 `⋯` 菜单新增「重试」（仅失败状态显示）；批量操作条新增「重试失败项 (N)」；失败原因直接显示在子项行
- 版本 `0.7.1`（manifest / `ServerConfig` / `/api/health` 与构建产出的 `.jpackage.xml`、`fnpak-server.cfg` 一致）

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
