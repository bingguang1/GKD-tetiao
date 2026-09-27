# 修改声明 / NOTICE

本文件用于履行 **GNU GPL-3.0 第 5(a) 条**对修改版的要求：
以显著方式声明本作品**已被修改**、说明修改内容与日期，并保留原始版权声明。

---

## 1. 上游来源与版权

| 项目 | 内容 |
|---|---|
| 上游项目 | [gkd-kit/gkd](https://github.com/gkd-kit/gkd) |
| 上游作者 | [@lisonge](https://github.com/lisonge) 及 GKD 贡献者 |
| 版权归属 | 原始代码版权归上游作者及贡献者所有 |
| 许可证 | **GNU General Public License v3.0**（见 [`LICENSE`](LICENSE)） |
| 本仓库性质 | 非官方修改版（fork），**与上游作者无隶属关系，未获任何背书** |
| 本仓库维护者 | GitHub [@bingguang1](https://github.com/bingguang1) |

本仓库**保留**上游的 `LICENSE` 全文，未删除或修改任何原始版权声明。
本仓库同样以 **GPL-3.0** 分发（无附加限制）。

---

## 2. 修改声明（GPL-3.0 §5(a)）

**本作品基于上游 GKD 修改而来。** 以下列出本 fork 相对上游的实质性修改。

> 名称：`GKD特调版` · 版本基线：`1.12.1`（versionCode 106）
> 修改者：GitHub [@bingguang1](https://github.com/bingguang1)
> 修改日期：见本仓库 git 提交历史（`git log --format='%ad %s' --date=short`）

### 2.1 功能与逻辑改动

改动清单基于**路径级权威比对**得出：以上游 tag [`v1.12.1`](https://github.com/gkd-kit/gkd/releases/tag/v1.12.1)
（与本 fork 的 `versionName` 完全对应）的文件树为基线，与本地文件树做集合差 ——
**新增 16 个 `.kt` 文件、删除 0 个**，另有 281 个同名文件存在内容改动
（其中大部分为上游原有文件，本 fork 的实质性改动以代码内 `fork(vNN)` 注释标注）。

#### A. 本 fork 新增的文件（上游 v1.12.1 不存在）

| 功能 | 文件 |
|---|---|
| 桌面小组件（`fork(v97)`） | `widget/WGkdWidget.kt`、`widget/WGkdWidgetProvider.kt`、`widget/WGkdWidgetReceiver.kt`<br/>资源：`res/drawable/widget_gkd_bg.xml`、`res/layout/widget_gkd_toggle.xml`、`res/xml/wgkd_widget_info.xml` |
| 关联应用守护（`fork(v99)`） | `service/AssocAppGuard.kt`、`service/A11yAutoGuard.kt`、`service/AlarmReceiver.kt`、`service/BootReceiver.kt`、`notif/NotifActionReceiver.kt`、`ui/GuardAssocAppListPage.kt`、`ui/GuardAssocAppListVm.kt` |
| 跳转 / 摇一摇防护（`fork(v106)`） | `service/JumpGuard.kt`、`service/FakeSkipGuard.kt`、`service/ShakeGuard.kt`、`service/SensorOrientationGuard.kt`、`ui/JumpGuardAppListPage.kt`、`ui/JumpGuardAppListVm.kt` |
| 首启预置配置 | `app/src/main/assets/firstrun/gkd-backup.zip`（含本 fork 自研的微信广告规则 `subscription/101.json`） |

#### B. 相对上游 v1.12.1 被修改的既有文件（选列）

| 位置 | 改动 |
|---|---|
| `app/src/main/res/values/strings.xml` | 应用名改为 `GKD特调版`；通知/小组件文案 |
| `app/src/main/AndroidManifest.xml` | 注册新增的 Receiver / Service / 小组件 Provider |
| `app/build.gradle.kts` | `GitInfo` 改为硬编码常量（`commitId = "fok0014"`），使脱离 git 环境亦可构建 |
| `App.kt` / `MainActivity.kt` / `MainViewModel.kt` / `ui/**` | 适配上述新增功能的入口与状态（`fork(v97/v99/v106)` 标注处） |
| `store/StoreExt.kt` | 新增"关联应用守护"（v99）与"摇一摇跳转防护"（v106）的偏好项 |

### 2.2 开源化改动（本次）

| # | 位置 | 改动 | 原因 |
|---|---|---|---|
| 9 | `app/src/main/kotlin/li/songe/gkd/App.kt` | `updateEnabled` 由 `isGkdChannel` 改为 `false` | 上游更新源发布官方 GKD，与本 fork 同包名但签名不同 → 会引导用户安装官方版或安装失败 |
| 10 | `app/src/main/kotlin/li/songe/gkd/App.kt` | `commitUrl` 由上游仓库改为本仓库 | 原值指向不存在的上游 commit，点击必然 404 |
| 11 | `app/src/main/kotlin/li/songe/gkd/util/Constants.kt` | `REPOSITORY_URL` / `ISSUES_URL` / `HOME_PAGE_URL` 改为本仓库；新增 `RELEASES_URL`；移除 `PLAY_STORE_URL` | 原值使用户把 fork 的问题提交到上游、并跳转官方 Play 商店页 |
| 12 | `app/src/main/kotlin/li/songe/gkd/ui/AboutPage.kt` | 分享面板 `Google Play` 项改为 `GitHub Releases`，改用 `RELEASES_URL` | 本 fork 无 Play 分发 |
| 13 | `app/src/main/assets/firstrun/gkd-backup.zip` | 移除第三方订阅 `subscription/1.json`（梦念逍遥のGKD订阅，285 应用 / 874 组）及其在 `db.json` 中的引用；清空个人化的 App 名单与计数 | 第三方内容版权属其作者，不宜随本仓库二次分发；且原内容含开发者个人使用数据 |
| 14 | `.gitignore` / `.gitattributes` | 补齐日志、产物、签名物料忽略规则；统一换行符 | 防止密钥与构建残留入库 |
| 15 | `.github/workflows/*` | 移除对上游 Secrets 的依赖、删除 Play 渠道相关步骤、替换已归档的 `create-release@v1` / `upload-release-asset@v1`、收敛触发条件 | 上游 Secrets 在本仓库不存在 → 每次 push 必然失败 |
| 16 | `README.md` / `NOTICE.md` / `.github/*` | 重写为 fork 身份，去掉上游官方下载入口与赞助入口；新增本修改声明 | GPL-3.0 §5(a) 合规；避免用户误认为官方版本 |

### 2.3 本 fork 未改动的部分

选择器引擎（`selector/` 模块）、规则解析与执行引擎、订阅机制、快照审查、数据库结构等
核心能力**均来自上游**，本 fork 未作实质性修改。

---

## 3. 第三方内容

| 内容 | 作者 | 许可 | 状态 |
|---|---|---|---|
| GKD（上游） | [@lisonge](https://github.com/lisonge) 及贡献者 | GPL-3.0 | 本仓库的基础，保留 `LICENSE` |
| 梦念逍遥のGKD订阅 | [@MengNianxiaoyao](https://github.com/MengNianxiaoyao) | 未在上游声明 | **已从仓库移除**（原先被内置在首启备份中） |

本仓库**不包含**任何第三方订阅数据。用户自行添加的订阅由用户与订阅作者之间自行约定。

---

## 4. 联系与边界

- 本 fork 特有的问题、构建失败、本仓库的改动 → 本仓库 Issues
- GKD 应用本身的功能问题、规则语法、选择器用法 → [上游仓库](https://github.com/gkd-kit/gkd/issues)
- 请不要向上游作者反馈本 fork 引入的问题
