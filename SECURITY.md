# 安全策略

## 私下报告

请**不要**在公开 Issue 里贴漏洞细节、账号凭据或完整日志。私下渠道：

- 邮箱：`yanghy0312@foxmail.com`
- 或使用 GitHub 的 Private vulnerability reporting（仓库 Settings → Security）

## 请提供

- 受影响版本（`/api/health` 返回的 `version`）与 fnOS 版本
- 复现步骤与影响（例如：未授权访问、路径穿越、命令执行、凭据泄露）
- 必要的日志片段（请先去掉 token、口令、Cookie、内网 IP、用户名与真实路径）

## 响应时限

- 3 个工作日内确认收到
- 7 个工作日内给出初步评估
- 修复发布后会在 CHANGELOG 与 Release 中注明（在未修复前不会公开细节）

## 禁止事项

- 不要对他人实例进行扫描、爆破或未授权测试
- 不要在公开渠道发布可直接利用的 PoC
- 不要提交真实账号、PikPak token/captcha token、`access-token`、`accounts.json` 或包含个人路径的日志
