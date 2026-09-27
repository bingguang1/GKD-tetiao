# 更新内容

本文件记录本 fork（GKD 特调版）的更新；上游 GKD 的更新请见
<https://github.com/gkd-kit/gkd/releases>。

## v107 / 1.12.2

**开箱即用版本**（基线上游 GKD 1.12.1，1.12.2 是本 fork 的构建号）：

- **内置订阅，首次启动自动加载**：出厂配置预置了「梦念逍遥のGKD订阅」的订阅链接，
  App 首次启动会自动从作者自己的发布渠道拉取规则，**下载安装即用，无需手动添加订阅**
  - 仓库内不打包该订阅的规则正文，只预置链接 → 不构成第三方内容二次分发，
    且用户始终拿到作者的最新规则
  - 首次启动需要联网；无网时按每日间隔自动重试
- **新增一键 ADB 配置工具** `adb-tools/adb-setup.bat`：自动安装 APK、授予权限与 appops、
  **自动开启无障碍服务**、加入电池优化白名单（命令与 App 内置授权页一致）
- 修复两个会导致"内置订阅失效"的 bug（均经 MuMu 模拟器真机验收发现）：
  - **订阅文件解析器不一致**：备份导入路径用了严格反序列化 `json.decodeFromString`，
    而网络更新/本地重载路径用的是 `RawSubscription.parse`（内含把 `"matches": "选择器"`
    归一化成数组的逻辑，见 `RawSubscription.kt:842`）。结果是内置的
    `subscription/101.json`（本 fork 的微信精准规则）**解析失败并被逐文件 try/catch 静默吞掉**，
    界面照样提示"导入成功"。现已统一改用 `parse`
  - **首启拉取订阅存在竞态**：`checkSubsUpdate` 读取的是 Room 派生 StateFlow
    `subsEntriesFlow` 的 `.value`，而导入刚写完数据库时该 flow 可能还是导入前的快照，
    于是遍历不到任何非本地订阅、直接整轮跳过（日志表现为"开始检测更新"到"结束检测更新"
    只隔 1 毫秒），用户看到订阅列表空白，且要等到每日轮询才会补上。
    现改为等待新导入的订阅项真正进入 flow（上限 5 秒）再触发检测
- 应用内版本号更新为 `1.12.2-fok0014`（versionCode 107）

## v106 / 1.12.1

首个开源化整理版本：

- 关闭应用内「检测更新」（上游更新源发布的是官方 GKD，与本 fork 同包名但签名不同）
- 「开源代码」「问题反馈」「首页」等入口改指向本仓库
- 分享面板的「Google Play」项改为「GitHub Releases」
- 清除内置配置中的第三方订阅全文与开发者个人使用数据（APK 减小约 125 KB）
- 仓库卫生：移除构建日志与签名密钥，补齐 `.gitignore` / `.gitattributes`
- CI：移除对上游 Secrets 的依赖与 Play 渠道步骤，修复必然失败的构建

## 更新方式

本 fork **不提供应用内自动更新**（原因见上），请关注本仓库 Releases：

`https://github.com/bingguang1/gkd-tejiao/releases/latest`
