# CHANGELOG

本项目从上游 Piko 的 fnOS 服务端分支演化而来。版本号为本 fork 自有编号；**0.7.0 起产品名为 FNPak**（`appname` 从 `piko` 改为 `fnpak`）。

## 0.10.0 — 目录页：时间 / 列表-卡片切换 / 缩略图 / 文件详情

目录页界面优化。**下载与续传逻辑、`ConnectionBudget` 语义未改**；「本机下载 / 离线任务 / 回收站」三页的排序与数据逻辑未动。

**时间展示**

- 列表主行默认显示「添加时间」（`createdAtMs`，与「时间」排序键同源）：桌面 `添加 YYYY-MM-DD HH:mm`，移动端压缩为 `添加 MM-DD HH:mm`（跨年补年份），时间不参与名称横滑
- 修改时间（`modifiedTime`）仅在与添加时间相差 **> 60s** 时补一行小字 `改 …`，避免绝大多数文件两行重复；悬停 `title` 展示含秒的完整时间
- SDK 侧核实：`FileStat` 有 `createdTime` / `modifiedTime`；`FileDetail` 没有时间字段，因此详情弹层复用列表行数据而非再打详情接口

**列表 / 卡片视图切换**

- 目录工具栏新增「列表 / 卡片」两个按钮（桌面 32px、移动 44px），选择写入 `localStorage: fnpak-drive-view`
- 默认值：**桌面（≥768px）列表、窄屏卡片**——宽屏列表的信息密度与列对齐更好，窄屏单行放不下名称 + 大小 + 类型 + 时间
- 卡片：2 / 3 / 4 / 5 / 6 列（窄屏 / sm / lg / xl / 2xl），16:9 缩略图、名称两行截断（`line-clamp-2 break-all`）、大小/类型/时间元信息；文件夹带「目录」徽标
- 与既有交互共存：整卡点击层（文件夹进入 / 视频播放 / 预览 / 解压 / 种子 / 确认下载）、排序六种组合、多选与长按、行尾 `⋯` 菜单均保留；窄屏未进入选择模式时勾选衬底 `invisible` 占位，保证 `⋯` 固定在右上角
- `ProgressiveList` 的「加载更多」哨兵加 `col-span-full`，卡片网格下跨列显示

**缩略图 / 视频封面（服务端代理 + 磁盘缓存）**

- **调研结论**：PikPak 列表接口本身返回 `thumbnail_link`，SDK `listFilesPaged` 固定传 `thumbnail_size=SIZE_SMALL`；因此**不需要本地抽帧、不引入 ffmpeg 等依赖**，也不新增第三方缩略图端点
- 浏览器只访问同源 `GET /api/drive/files/{id}/thumbnail`，签名直链不出服务端
- 磁盘缓存：`$fnpak.home/thumbnails/${sha256(fileId)}.thumb`；上限 **128 MiB 且 4096 项**，命中刷新 mtime 实现 LRU，超出按 mtime 升序淘汰；启动时扫描校正计数；单图上限 3 MiB，只接受 jpeg/png/gif/bmp/webp/avif，非图片直接失败
- 浏览器侧：`ETag` + `Cache-Control: private, max-age=31536000, immutable`（缩略图按 fileId 不可变），命中 `If-None-Match` 回 304；失败回 404 + `no-store`，前端渲染类型图标占位而不是坏图
- 懒加载：`IntersectionObserver`（`rootMargin: 240px`）+ `loading="lazy"`，进入视口才请求
- 并发：缩略图独立 `Semaphore(4)`，**不占用下载的 `ConnectionBudget`**，下载与缩略图互不饿死
- 直链过期（403 / 非 2xx）：按 `parentId` 重列父目录换新链后重试一次
- **重启兜底**：内存直链表为空时，按 fileId 查一次详情拿 `parentId`，再重列父目录取回 `thumbnail_link`；兜底（详情 + 重列）合计限 **2 并发**，父目录列表 **30s 复用**（整屏缩略图只多打一次列表），失败进 **60s 负缓存**；两者都拿不到才 404 走占位
- 可观测：`GET /api/drive/thumbnail/stats` 返回 hits / misses / upstreamOk / upstreamFailures / evictions / writeFailures / cachedEntries / cachedBytes / linkEntries / coldLookups / recovered / negativeEntries

**文件详情**

- 目录行 `⋯` 菜单新增「详情」：弹层展示名称、类型 / MIME、大小（文件夹标「需扫描」）、添加时间、修改时间、时长、GCID / 哈希、fileId、父目录 ID、所在目录
- 数据复用列表行，不额外请求；文件夹大小与文件数量需递归扫描，本版不做
- 修复：`DriveDetailsDialog` 用 `v-model` 只会传 `modelValue`，而组件声明的是 `open` prop，导致点击「详情」弹层不显示；改为 `v-model:open`

**构建与验证**

- 前端重建并同步 `server/src/main/resources/web/`，dist 与 server 资源按文件哈希一致
- 缩略图冒烟（本地 `HttpServer` 假 CDN）覆盖五条路径：冷启动兜底、磁盘命中、403 重列刷新、容量淘汰、重启后无直链兜底，全部通过
- 版本 `0.10.0`（manifest / `ServerConfig` / `/api/health` / `package.json` 与构建产出一致）
- **未验证**：真实账号下视频是否稳定返回 `thumbnail_link`；缩略图在真实 PikPak CDN 的过期策略与响应头

## 0.9.2 — 双直链加速（默认关闭）

新增「双直链加速」：同一文件在下载开始后再取一条签名直链，两条直链各自并行跑分段，**默认关闭**，需在设置页「下载并发」区块手动开启。

- 实现：`SegmentDownloader` 支持两条 lane（两个 `UrlHolder`，每条直链独立 `Semaphore(8)`），主 lane 先跑 1.5s 再取第二条直链；开启时段数按设定值翻倍（默认 8→16），**不额外突破全局连接预算**（`ConnectionBudget` 语义未变）
- 生效条件：第二直链与主直链的签名（`path` + 排序后的 `query`）必须不同；同签名、取直链失败、文件已下完时自动放弃第二 lane，交由主 lane 收尾；第二 lane 分段失败/403 降级为主 lane 续传，必要时再走单连接兜底
- 小文件门槛：文件 < 16 MiB 不启用第二条直链（避免为多打一次详情接口而收益甚微）
- 设置项：`dualSignedUrls`（默认 `false`）与 `downloadDiagnostics`（默认 `false`）写入 `settings.json`；`GET /api/settings` 返回两字段，`POST /api/settings/download-tuning` 可改（字段省略时保持原值）。Web UI 只暴露「双直链加速」开关并附前置条件与未验证说明；**诊断开关不对普通用户开放**，仅供 settings.json / API 层排查
- 不改下载产物格式、不改断点续传格式；关停开关对已开始的任务不回溯，新任务生效
- 实测（假 CDN：32 MiB、每连接限速 400 KiB/s、同一 URL 第 9 条起 503、可指定 403）：单 lane 16 089 ms → 双 lane 9 520 ms（约 **+70%**）；同签名 16 825 ms；403 降级 15 958 ms；503 场景 0 字节失败、单 URL 峰值连接 ≤8、全局峰值 16/32、五场景 sha256 全部一致
- **未验证 / 已知限制**：上述为假 CDN 限速环境的实验室数据，**真实 PikPak 账号 A/B 未验证**；若 PikPak 按账号或单 IP 限速，增加连接可能完全不提速甚至更慢；是否真能拿到「签名不同」的两条直链取决于服务端行为，同签名时会静默回退单 lane
- **本版未做（Phase 2/3/4）**：滑窗式分段重切、按实测速率自适应 worker 数、升级 PikPak SDK 的并发加速能力
- 版本 `0.9.2`（manifest / `ServerConfig` / `/api/health` / `package.json` 与构建产出一致）

## 0.9.1 — 目录行整行点击 / 不可预览确认 / 主题跟随系统 / 长按多选修复

纯前端交互改进，API 与 SSE 契约完全不变。

- 目录行整行可点：`<tr>`/行卡任意空白与名称文字走同一分发（`rowAction.js` 按 folder → video → 可预览类型 → 单卷压缩包 → `.torrent` → 其他 的顺序判定）。文件夹进目录；视频直接播放；图片/音频/PDF/文本/Markdown 直接预览；zip/rar/7z 单卷直接打开解压弹层；`.torrent` 直接打开种子清单
- 不可预览文件（二进制、无扩展名、分卷 `.part1.rar`、`tar` 等）点行**先确认再下载**：确认框展示文件名、大小与中性说明「该文件无法在浏览器内预览，是否下载到本机？」，确认后才走既有下载链路（目录可写预检、失败原因映射、在途锁）；取消/Esc/点遮罩均无动作。行内菜单里显式点「下载」仍直接开始，不二次确认
- 隔离与可达性：勾选框、行尾 `⋯`、菜单/弹层内部不触发行动作；移动端名称区横向拖动沿用 `ScrollName` 手势判定；多选模式点行只切换勾选；键盘聚焦行后 Enter/Space 触发同一动作；连点 600ms 去重，下载复用在途锁
- 主题三态：新增 `system | light | dark`，默认 `system`；`system` 下监听 `matchMedia('(prefers-color-scheme: dark)')` 的 `change`，运行期切换立即生效；`index.html` 挂载前内联脚本即写入根元素 `.dark` 与 `meta name="theme-color"`，深色首屏不再白闪；`fnpak-theme` 显式设置保留，旧键 `piko-theme` 继续回退；设置页新增「外观」三档选择
- 深色对比度：实心按钮/勾选框/角标改用更深色阶（`*-strong` token），确认框、菜单、输入框、进度条、失败行、骨架屏在深色下无白底白字或低对比度
- 修复本机下载父任务行与离线任务行的长按多选：模板把 `ref` 直接传给 `startPress`，Vue 模板自动解包为 `null`，每次 `touchstart` 抛 `Cannot read properties of null`，长按永远进不了选择模式；现按目录行同一写法包一层（`startGroupPress/cancelGroupPress`、`startOfflinePress/cancelOfflinePress`），并在长按后 800ms 内吞掉浏览器补发的 `click`，避免误触展开/暂停/重试/取消/删除；回收站行的 `startPress` 是组件内局部函数，不属于同一问题
- 已知边界：**下载子项行暂不支持长按多选**（子项行没有 touch handler 与子项选择态，属新功能，本版不实现）；主题默认跟随系统，**页头按钮仍是显式浅/深切换**，三态选择在设置页
- 版本 `0.9.1`（manifest / `ServerConfig` / `/api/health` / `package.json` 与构建产出一致）

## 0.9.0 — 目录排序 / 在线预览 / 在线解压 / 种子

- 目录 Tab 排序：时间 / 文件名 / 文件类型 × 正序 / 倒序，默认「时间倒序 + 文件夹恒定在前」；名称用 `Intl.Collator(zh-Hans-CN, numeric)` 自然排序，时间缺失恒排最后；偏好写入 localStorage；排序作用于已取回的数据源，不产生额外请求；`FileItem` 新增 `createdAtMs`（`createdTime` 解析为 UTC 毫秒，失败为 `null`）
- 在线预览：`PreviewDialog` 按 `mimeType` + 扩展名分发图片 / 音频 / 视频（复用现有播放器）/ PDF（iframe）/ 纯文本与代码 / Markdown；文本与 Markdown 强制 `?mode=proxy` + `Range: bytes=0-1048575` 同源读取，UTF-8 解码失败回退 GBK，二进制嗅探命中即拒绝预览并提示下载，超 1MB 截断并明确提示「仅显示前 1MB」；Markdown 使用 `marked` + `DOMPurify` 净化，禁止内联 HTML 注入；浏览器不支持的类型回退到下载
- 在线解压：新增 `POST /api/archive/list`、`POST /api/archive/extract`、`GET /api/archive/tasks/{id}`，`ArchiveRepository` 接入 `ServiceGraph`；服务端串行提交避免「系统繁忙」，前端 500ms 轮询、60s 超时；首次 `list` 返回 `unzip_password_required` 或 `unzip_password_incorrect`，前端弹框内联提示可重输，密码只放 POST body、不写日志、不进 URL query，成功后的密码复用到 `extract`；仅 `zip`/`rar`/`7z` 单卷显示入口，分卷（`.part*.rar`、`.7z.00N`、`.z0N`、`.r0N`）与 `tar` 隐藏；错误文案 `E_INVALID_PASSWORD` / `E_INVALID_FORMAT` / `NEED_MORE_QUOTA` / `space_not_enough`，原始异常只进日志；完成后返回产物 `fileId` 并刷新当前目录
- 种子打开 / 离线：新增 `POST /api/links/torrent`，服务端用 `MediaService.meta(fileId).directUrl` 拉取字节，不落盘、不入库；自研 bencode 解析（不引第三方库），支持 `name` / `name.utf-8`、单文件 `length`、`files[]`、路径拼装与目录折叠、`announce` / `announce-list`，infohash 沿用 info 原始字节 SHA-1；防护 10MB / 嵌套深度 32 / 文件数 1 万 / 总大小溢出，BT v2（`file tree`）明确提示暂不支持；无官方 torrent 上传接口，magnet 的 `tr=` 由 `announce` / `announce-list` 拼接并 URL 编码；前端「打开」展示文件清单与总大小并可「加入离线」（复用 `/api/links/save`），另有「离线下载」直接提交，两条路径均可用
- 修复 `ProgressiveList` 两处问题：补 `AppButton` 导入；哨兵出现后再懒建 `IntersectionObserver`，使「加载更多」恢复可用
- 已知边界：目录排序只覆盖已取回的一页（≤100 项），第 101 项之后不参与排序；解压仅支持 zip/rar/7z 单卷且整包解压；种子暂不支持 BT v2
- 版本 `0.9.0`（manifest / `ServerConfig` / `/api/health` / `package.json` 与构建产出一致）

## 0.8.2 — 排队中永久卡死修复（调度自愈）

- 现象（线上实机）：3 个任务 2 个完成、1 个永久停在「排队中」（PENDING），暂停再继续、单条重试都不生效；重启应用后立即恢复——说明卡住状态只存在于进程内
- 根因：`PikoDownloadCoordinator.launchTracked` 先把任务登记写入 `jobs[taskId]`，再调用 `job.start()`；暂停/取消恰好落在两步之间时，LAZY 协程从未真正启动，`finally` 收尾不执行，`jobs` 留下一条永不结束的死登记。该登记同时造成两件事：占住 `BATCH_PARALLEL - jobs.size` 的并发位；且 `pumpQueue` 用 `taskId !in jobs.value` 过滤派发，导致同名任务永远不被调度。重试/暂停继续只把状态改回 PENDING，不会唤醒调度器，于是永久排队；重启清空 `jobs` 才恢复
- 并存路径：`ConnectionBudget.acquire()` 在等待中被取消时不摘除等待者，取消态 `release()` 又直接丢名额，`active` 可被永久钉在上限
- 修复：
  - `SegmentDownloader` 的连接预算改为 `Permit` 句柄（幂等归还、记录持有者协程）：`acquire` 取消时清理等待者，`release` 在 `NonCancellable` 内归还；新增 `reclaimOrphans()` 只回收「持有者协程已结束」的名额，等待超过 `RECLAIM_AFTER_MS = 30s` 触发一次回收
  - `PikoDownloadCoordinator` 的任务登记收尾改 `invokeOnCompletion`（协程未 start 也执行清理）；对 PENDING 任务重试时主动 `pumpQueue()`
  - 新增 10s 周期看门狗 `reconcileQueue()`（`QUEUE_WATCHDOG_INTERVAL_MS`）：清掉已完成任务的残留登记、把 DOWNLOADING 但协程已结束的任务回 PENDING 续传、有空位则补调度——即使名额/登记被异常路径破坏，也能在下个 tick 自愈，不再需要重启
- 兜底不误伤正常下载：回收只看「持有者协程是否已结束」（活跃持有者 1.5s 采样不被回收），健康分段获取远低于 30s 窗口；看门狗只做一致性校正，不归零真实占用
- 验收：`StallBench`（test 源集，不进安装包）覆盖暂停/继续、名额泄漏、登记残留、取消四场景全部 PASS（泄漏自愈 2397 ms、残留登记 8242 ms 自愈、取消后名额采样全 0）；`RetryBench` 全通过（重试、`completedRanges` 不被清、暂停/继续、取消、批量重试）
- 说明：截图实测「每任务分段数 8 / 全局连接预算 32」，3×8=24<32，默认配置不会因等名额而卡；触发条件是上述登记竞态与预算泄漏路径
- 版本 `0.8.2`（manifest / `ServerConfig` / `/api/health` / `package.json` 与构建产出一致）

## 0.8.1 — 移动端长名称与行内按钮修复

- 行内操作按钮固定在行尾：菜单触发器 `shrink-0`，名称区占据剩余空间并允许收缩（`min-width: 0`），按钮与整行等高对齐、永远贴行右边缘
- 长文件名/文件夹名不再把操作按钮挤出行范围或挤出屏幕：名称改为在**名称区内部**单行横向滚动（`overflow-x: auto; white-space: nowrap`，移动端隐藏滚动条、保留滚动能力），整行不横向滚动
- 名称区手势判定：横向拖动超过阈值视为滚动、不触发进入目录/展开任务；轻点仍可进入/打开；按钮点击命中正常
- 覆盖四类列表：目录、本机下载（父任务行与展开子项行）、离线任务、回收站；下载组行有第二行元信息、回收站与离线任务的行结构不同，均逐一套用同一行骨架
- 桌面端（≥768px）保留省略号策略（悬停 `title` 看全名），不做滚动
- 根因：窄屏 4 列网格在未进入选择模式时 checkbox 为 `display:none`，自动放置使可见的 icon/name/menu 三列错位，菜单列落入 `minmax(0,1fr)` 的名称轨道，被长名称一路挤出；修复为窄屏显式指定列位（`max-md:col-start-2/3/4`），桌面保持自动布局
- 不回归既有修复：窄屏批量条仍为两行、不遮最后一行；下载组元信息无速率时不再出现占位分隔符；触控目标 ≥44px；三档（1440×900 / 820×1180 / 390×844）横向溢出均为 0
- 版本 `0.8.1`（manifest / `ServerConfig` / `/api/health` / `package.json` 与构建产出一致）；纯前端修复，API 与 SSE 契约完全不变

## 0.8.0 — UI 重构（双端布局）

- 移除 Element Plus（及图标包），改为 **Tailwind CSS v4（Vite 插件）+ reka-ui** 无样式底层 + 自建轻量组件：Button / IconButton / Dialog / Sheet（移动抽屉）/ Popover / Menu / Checkbox / Toast / Progress / Skeleton / Tabs
- 设计 token 化（颜色/间距/字号/圆角/阴影全部 CSS 变量），明暗主题沿用 `fnpak-theme` 键（读不到回退 `piko-theme`，老设置不丢）；断点 `sm 640 / md 768 / lg 1024 / xl 1280`
- 统一 AppShell：桌面（≥1024）顶部品牌 + Tab + 设置入口，内容区限宽 1280 居中；移动（<768）紧凑顶栏 + 底部四 Tab（目录 / 本机下载 / 离线任务 / 回收站）+ 安全区 padding
- 目录、本机下载、离线任务、回收站统一为「行式卡片列表」：主行图标/名称/大小/状态/进度，失败原因与速率在第二行或展开区；完成/失败用色彩与图标区分
- 多选：行首 checkbox，移动端长按 500ms 进入选择模式；批量条移动端固定底部安全区、桌面悬浮内容区底部，列表预留底部空间，不遮最后一行
- 窄屏批量条压缩为两行：信息行只保留数量与「文件夹 / 文件」计数（体积与「待扫描」说明仅桌面显示），按钮行标签精简（回收站「恢复」「彻底删除」不再重复计数），390px 下按钮不换行、完整可点
- 修复下载组移动端元信息前的多余分隔符：无速率时不再渲染占位「—」
- 行内 `⋯` 浮层：桌面 hover、移动点击，只作用于当前行；已完成行不出现下载控制按钮
- 大列表：目录/任务/子项改用增量渲染（首屏 40 项、滚动哨兵或「加载更多」追加），320+ 项与展开子项不再一次性挂载；展开仍保留 `childCount > 本地子项数` 补拉
- SSE 逻辑不变：继续按 id merge，子项不会因增量事件丢失归属
- 播放器：移动端全屏弹层，PC 弹层尺寸合理；弹层均支持 esc / 遮罩关闭与键盘操作
- 可访问性：触控目标 ≥44px、`:focus-visible` 可见、对比度达标、弹层有标题与关闭按钮
- 体积：JS 562,083 B → 288,488 B（gzip 190,892 → 96,797 B）；CSS 148,027 B → 27,018 B（gzip 21,126 → 6,085 B）
- **默认构建路径改为 fpk-only**：`./gradlew :server:installDist` 不带任何 `-P` 属性即可，只需 JDK 21；`:app` / `:ui` / `:desktopApp` / `:cli` / `:shots` 源码保留原位、默认不参与配置。桌面开发/打包显式加 `-Pfnpak.full=true`（需 JDK 25，属预期行为）；兼容旧属性 `-Pfnpak.fpkOnly`（显式 `false` 等价于 full，冲突时 full 优先）
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
