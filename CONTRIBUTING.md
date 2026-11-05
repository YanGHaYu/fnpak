# 参与贡献

感谢你愿意帮助 FNPak。本仓库是上游 [NihilDigit/piko](https://github.com/NihilDigit/piko) 的 fork，面向飞牛 fnOS 的服务端与打包。

## 流程

1. 先搜 Issue，避免重复；较大的功能或架构调整请先开 Issue 说明场景与方案
2. Fork 本仓库，从 `main` 建分支（`feat/...`、`fix/...`）
3. 提交前本地跑：
   ```bash
   ./gradlew :server:installDist --configure-on-demand --no-daemon
   cd web-ui && npm ci && npm run build
   ./scripts/check-fpk.sh build/fpk/stage   # 若已打包
   ```
4. PR 描述里写清：改动内容、验证方式、影响范围；前端改动请附截图
5. 不要提交 `access-token`、`accounts.json`、`settings.json`、`download-tasks.json`、日志、`*.fpk`、构建缓存

## 许可

- 本仓库整体以 **AGPL-3.0-or-later** 分发，提交即表示你同意以同一许可证授权你的贡献
- **本仓库不要求签署 CLA**。根目录 `CLA.md` 仅说明上游 NihilDigit/piko 的贡献协议；只有当你把补丁回贡给上游时才需要遵守上游的 CLA 流程
- 新增文件请加 SPDX 头：
  ```kotlin
  // SPDX-License-Identifier: AGPL-3.0-or-later
  // Copyright (C) 2026 <你的名字>
  ```
- 不要移除上游版权声明；修改上游文件时在 `NOTICE` 记录修改模块与日期

## 代码风格

- Kotlin：沿用现有包名 `dev.piko.*`（不因产品更名而重排包结构，避免破坏上游同步）
- Vue：`<script setup lang="ts">`，样式沿用现有 CSS 变量与 44px 触控规则
- 提交信息：`feat(scope): ...` / `fix(scope): ...` / `docs: ...`

## 行为准则

参与本项目即表示同意 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
