# 更新内容

本文件记录本 fork（GKD 特调版）的更新；上游 GKD 的更新请见
<https://github.com/gkd-kit/gkd/releases>。

## v106 / 1.12.1

本版本为开源化整理版本，功能与本地构建的 v106 一致：

- 关闭应用内「检测更新」（上游更新源发布的是官方 GKD，与本 fork 同包名但签名不同）
- 「开源代码」「问题反馈」「首页」等入口改指向本仓库
- 分享面板的「Google Play」项改为「GitHub Releases」
- 移除了内置的第三方订阅（梦念逍遥のGKD订阅），订阅需自行添加
- 仓库卫生：移除构建日志与签名密钥，补齐 `.gitignore` / `.gitattributes`
- CI：移除对上游 Secrets 的依赖与 Play 渠道步骤，修复必然失败的构建

## 更新方式

本 fork **不提供应用内自动更新**（原因见上），请关注本仓库 Releases：

`https://github.com/bingguang1/gkd-tejiao/releases/latest`
