# Piko Web UI

fnOS 服务端内嵌的 Web 界面：Vue 3 + vue-router + Element Plus，构建产物直接打进 `server.jar`。

## 离线自包含

- 运行期不请求任何 CDN，也不从 npm registry 拉东西：`npm run build` 后所有 JS/CSS/字体都被打进 `dist/assets/`。
- Element Plus 按需引入（`unplugin-vue-components` + `unplugin-auto-import`），没引 `@element-plus/icons-vue` 之外的图标集。
- 字体用系统字体栈（`-apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif`），不下载 web font。
- 深浅色跟随 `html[data-theme]` + Element Plus 的 `dark/css-vars.css`，不依赖外部主题包。

## 重新构建

```sh
cd web-ui
npm install          # 仅构建时需要联网拉依赖，node_modules 不入库
npm run build        # 产物在 web-ui/dist

# 把产物同步进服务端资源目录（构建产物本身要入库）
rm -rf ../server/src/main/resources/web/assets
cp -a dist/index.html ../server/src/main/resources/web/index.html
cp -a dist/assets ../server/src/main/resources/web/assets
```

要求 Node 18+（Vite 5 的下限）。构建完成后重新打服务端即可：

```sh
./gradlew :server:installDist --configure-on-demand
```

## 路由与后端约定

| 路径 | 内容 |
| --- | --- |
| `/` | 三个 Tab：目录浏览（多选/文件夹下载/移入回收站）、本机下载、云端离线任务；链接解析、播放器 |
| `/settings` | 访问口令、下载位置、下载并发、版本与日志 |

- 首次运行（服务端没有 `access-token` 文件）时首页被替换为独立的「首次设置访问口令」页（`SetupView.vue`），不弹窗；`GET /api/setup/state` 免口令，返回 `needsSetup` / `reason` / `setupWindowOpen`。老用户已有 token 文件或外部注入 `PIKO_SERVER_TOKEN` 时不出现该页。
- 设置窗口默认 15 分钟，可用 `--setup-window-minutes` 调整（0 = 不限）；忘记口令时删除 token 文件后重启即重新进入向导。

- 单页应用用 `createWebHistory()`；服务端 `StaticRoutes.kt` 对**所有非 `/api` 路径**回 `index.html`（带扩展名的缺失文件仍返回 404 JSON），所以刷新 `/settings` 不会 404。
- 接口错误统一是 `{"error":{"code","message"}}`；`api.js` 把 `401 access_denied` 交给全局处理器，界面回到口令卡（`GateView`）。
- 进度走 `EventSource("/api/events")`，监听 `download.progress` / `task.progress`；事件字段保持后端原名，`segments` 为可选的附加信息。
- 媒体播放走 `<video>` + `/api/media/{id}/stream`（Range 由服务端代理），前端不做任何拼流。
