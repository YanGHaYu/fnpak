## 改动内容

<!-- 一句话说明这个 PR 解决什么 -->

## 验证

- [ ] `./gradlew :server:installDist --configure-on-demand --no-daemon` 通过
- [ ] `cd web-ui && npm ci && npm run build` 通过（前端改动）
- [ ] `./scripts/check-fpk.sh build/fpk/stage` 通过（打包改动）
- [ ] 已在真机/真浏览器验证（写明版本、fnOS 版本、屏幕尺寸）

## 影响范围

- [ ] 服务端 API / 数据目录 / 权限模型
- [ ] 前端布局（移动端 44px、无横向滚动）
- [ ] fpk 打包 / 版本号
- [ ] 文档

## 截图 / 日志

<!-- 移动端请附 375×667 截图；日志请先去敏（token/口令/Cookie/IP/用户名/路径） -->

## 其他

- [ ] 我已阅读并同意 [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md)
- [ ] 我没有提交 token、`access-token`、`accounts.json`、`settings.json`、`download-tasks.json`、日志或构建缓存
