# GKD 特调版

<p align="center">
  <b>基于 <a href="https://github.com/gkd-kit/gkd">gkd-kit/gkd</a> 的<u>非官方</u>修改版（fork）</b><br/>
  自定义屏幕点击 Android 应用 · 遵循 GPL-3.0 开源
</p>

---

## ⚠️ 这是什么 / 不是什么

**这是 [GKD](https://github.com/gkd-kit/gkd)（作者 [@lisonge](https://github.com/lisonge)）的非官方修改版。**

| | |
|---|---|
| ✅ 是 | 一个个人自用的 GKD 定制分支，在微信等场景上做了规则与点击逻辑的调优 |
| ❌ 不是 | GKD 官方版本，与上游作者**没有任何隶属关系**，未获任何形式的官方背书 |
| 📌 版权 | 本仓库绝大部分代码版权归 **GKD 上游项目及其贡献者**所有，遵循 **GPL-3.0** |
| 📌 求助 | 本 fork 特有的问题 → 请提到 [本仓库 Issues](https://github.com/bingguang1/GKD-tetiao/issues)<br/>GKD 应用本身的问题 → 请提到 [上游仓库](https://github.com/gkd-kit/gkd/issues) |

详细的修改声明（GPL-3.0 §5(a) 要求）见 `NOTICE.md`。

> **包名说明**：本 fork 的包名与上游同为 `li.songe.gkd`（为保证老用户能覆盖升级），
> 因此**无法与官方 GKD 同时安装**，也**不能上架 Google Play**。
> 安装本 fork 会覆盖已安装的官方版（若签名不同则安装失败，需先卸载官方版）。

---

## 功能一览（本 fork 的定制能力）

> 全部**免 root**。开关都在「设置」页里，默认值以开箱可用为准。

| 功能 | 做什么 | 默认 |
|---|---|---|
| **防摇一摇广告 / 开屏自动关闭** | 打开应用后的「开屏时长」内，自动点掉**真正可点**的「跳过 / 关闭 / 知道了」按钮 —— 在广告被摇一摇晃走之前先关掉它。只做节点点击，**绝不打坐标、不做返回键兜底** | 开 |
| **摇一摇跳转防护** | **在你勾选的应用**里，开屏窗口内被带到别的应用 → 判定为摇一摇广告跳转 → 按返回 + 必要时把原应用拉回来。开屏/广告页另有 15 秒语义兜底；「开屏时长」可配（1.5~15 秒） | 开（名单默认为空 = 不拦任何应用） |
| **假跳过防护** | 点"跳过"后校验落点：跳到**别的应用** = 假跳过误点 → 立即返回 + 自动降级该应用（宁可不跳，也不点进广告） | 开 |
| **关闭快应用** | 流氓广告借**快应用**（厂商预装的免安装小程序运行环境）把你在里面自动下载 APK。识别"谁响应 `hap://` 链接"= 快应用引擎 → **秒退**退回原应用；还可一键停用引擎、掐掉引擎的「安装应用」权限 | 开 |
| **坐标点击守卫** | 拒绝在"空/反向矩形"的节点上、以及"用户看不到的节点范围内"打盲坐标 —— 真机实证这是朋友圈广告"按逻辑关闭却点进广告"的根因 | 开 |
| **无障碍自动守护** | 部分 ROM（vivo/小米等）会清掉无障碍启用列表。本 fork 用 ContentObserver + 自适应闹钟（未运行 2 分钟一发）+ 亮屏/解锁触发 + 开机重试自动恢复，且是**非破坏性恢复**（不反复摘除服务） | 开 |
| **关联应用守护** | 你勾选的"关键 App"被打开时，若无障碍不在位就**立即**恢复（不必等闹钟，也不用打开 GKD） | 开 |
| **一键开关无障碍** | 通知栏按钮 / 快捷磁贴 / 桌面小组件「GKD快捷开关」三种入口，语义与设置页一致（**手动关闭则守护不拉回**） | 通知/磁贴按需开 |
| **系统界面闸门**（v118 新增，底层） | 所有内置守卫都不会在**系统界面**上动手：状态栏/通知/控制面板/上滑面板/桌面/负一屏一律忽略，只对**你自己应用**的窗口生效 | 始终生效 |

### 为什么会有「系统界面闸门」这一层

因为踩过一个很典型的坑（v115 修复）：很多 ROM 的控制面板里，开关的节点文字**恰好就是「关闭」**
（`class=android.widget.Switch`、`text=关闭`、功能名在 `content-desc` 里，例如 飞行模式 / WLAN /
手电筒 / 甚至 GKD 自己的磁贴）。早期"找关闭按钮"的判据是 `clickable && text.contains("关闭")`，
于是**上拉打开控制面板时 GKD 会去点这些开关**（实测一天 40+ 次，点到 GKD 磁贴还会把无障碍关掉）。
现在：系统包名清单 + "只扫自己应用的窗口" + "开关类控件一律不点" + "关闭只认关闭类短语" 四道闸一起挡。

---

## 相对上游的改动

> 版本基线：`1.12.2`（versionCode 118）。以下为**用户可感知**的改动。

### 0. 内置防护（v107~v118 的主要工作）

上面「功能一览」里的 9 项能力都是本 fork 新增的，上游没有。实现位置：
`service/ShakeGuard.kt`（防摇一摇）、`service/JumpGuard.kt`（跳转防护）、`service/FakeSkipGuard.kt`（假跳过）、
`service/QuickApp*.kt`（关闭快应用）、`service/A11yAutoGuard.kt` + `service/AssocAppGuard.kt`（无障碍守护）、
`service/SystemSurfaces.kt`（系统界面闸门）。
`app/build.gradle.kts` 里的 `gkd-kit/gkd` 上游代码保持原样，便于跟随上游升级。

### 1. 微信朋友圈分段广告规则（本 fork 的主要工作）

内置了一组针对微信朋友圈广告的精准规则（`subscription/101.json`），要点：

- 坐标兜底**不再点击「用户不可见的节点 / 空矩形」**——真机实证这正是"点到广告上"的原因
- `[直接关闭]` 提到第二段，解决"第一段触发后卡住"
- 不在组级共享冷却，让 `①→②→③` 的分段链尽快走完

> ⚠️ 部署提示：若你同时启用了第三方订阅里同名的分组，**必须关掉其中一个**，否则两组各点一次。

### 2. 关闭应用内检测更新

上游更新源发布的是**官方 GKD**，与本 fork 同包名但签名不同 —— 一旦用户点"检测更新"，
会被引导安装官方版（签名冲突失败，或把 fork 覆盖掉）。因此本 fork 关闭了该功能。

**更新请关注本仓库 Releases。**

### 3. 应用内链接改指向本仓库

"开源代码""问题反馈"等入口原本指向上游仓库，会让用户把 fork 的 bug 提到上游去；
现已改为指向本仓库（`util/Constants.kt`、`App.kt`、`ui/AboutPage.kt`）。

### 4. 仓库卫生

移除了构建日志、签名密钥等不应入库的文件，补齐了 `.gitignore` / `.gitattributes`，
并修掉了上游 CI 工作流里对本 fork 无用或必然失败的部分（详见 `NOTICE.md`）。

---

## 开箱即用：内置订阅（首次启动自动加载）

本 fork 的出厂配置里**预置了 [梦念逍遥のGKD订阅](https://github.com/MengNianxiaoyao/gkd-subscription)**，
**首次启动会自动联网拉取规则**，不需要你手动添加订阅。

> **实现说明（重要）**：仓库里**不打包**该订阅的规则正文（642 KB），只预置订阅链接，
> 由 App 从**作者自己的发布渠道**（`registry.npmmirror.com/gkd-subscription`）拉取。
> 这样既做到"下载即用"，又不构成对第三方内容的二次分发；
> 而且用户拿到的**永远是作者的最新规则**——内置快照在首次联网时本来就会被在线版本整体覆盖。
> 该订阅的版权归其作者所有，本仓库只提供链接。

- 首次启动**需要联网**；若那时没网，App 会按每日间隔自动重试，也可在订阅页下拉手动刷新
- 想再加别的订阅：订阅 → 右上角 `+` → 粘贴订阅链接；
  第三方订阅列表见 <https://github.com/topics/gkd-subscription>

---

## 安装

本仓库是**源码仓库**。请自行构建，或从 Releases 下载已构建的 APK。

### 从源码构建

```bash
# 环境要求: JDK 21 + Android SDK (compileSdk 37 / buildTools 37.0.0)
# 1) 在根目录创建 local.properties 指向你的 Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2) 构建 release APK
#    未配置签名时自动回退到 debug 签名, 产物可直接安装
./gradlew app:assembleGkdRelease

# 产物: app/build/outputs/apk/gkd/release/app-gkd-release.apk
```

### 使用自己的签名

签名信息通过 Gradle 属性传入，**不要写进仓库**：

```bash
./gradlew app:assembleGkdRelease \
  -PGKD_STORE_FILE=/abs/path/your.jks \
  -PGKD_STORE_PASSWORD=*** \
  -PGKD_KEY_ALIAS=*** \
  -PGKD_KEY_PASSWORD=***
```

> 签名密钥一旦丢失，你将**无法**给已安装的用户推送升级，请务必备份。

---

## 一键 ADB 配置（推荐）

装完 App 后需要授予若干权限，一个个点很麻烦。用数据线连上电脑，运行
`adb-tools/adb-setup.bat` 即可一次配好：

- 授予 `WRITE_SECURE_SETTINGS`、`GET_APP_OPS_STATS`、通知 权限
- 解除 6 项系统操作限制（appops）：`POST_NOTIFICATION`、`SYSTEM_ALERT_WINDOW`、
  `ACCESS_ACCESSIBILITY`、`ACCESS_RESTRICTED_SETTINGS`、`FOREGROUND_SERVICE_SPECIAL_USE`、
  `CREATE_ACCESSIBILITY_OVERLAY`
- **自动开启无障碍服务**（免去在设置里翻找，且不会关掉你已启用的其它无障碍服务）
- 加入电池优化白名单、触发一次状态同步

脚本会自动查找 adb（脚本目录 → PATH → 常见 SDK 路径），找不到时从 Google 官方地址下载
platform-tools。**命令清单与 App 内置授权页完全一致**（源码依据：`AuthA11yPage.kt` 的 `gkdStartCommandText`）。

> 用法：把 `gkd-tejiao-<版本>.apk` 与 `adb-tools` 里的两个文件放同一目录，双击 `adb-setup.bat`；
> 手机需开启「开发者选项 → USB 调试」并在弹窗里允许。
> 部分 ROM（小米/华为/OPPO/vivo）会拦截 adb 修改无障碍，脚本会提示改用手动开启。

---

## 核心能力来自上游

选择器语法、订阅机制、快照审查等核心能力均由上游提供，文档请见：

- 选择器：<https://gkd.li/guide/selector>
- 订阅规则：<https://gkd.li/guide/subscription>
- 快照审查工具：<https://github.com/gkd-kit/inspect>

一个选择器示例（点击"广告"节点）：

```
@[vid="menu"] < [vid="menu_container"] - [vid="dot_text_layout"] > [text^="广告"]
```

---

## 本 fork 仍在使用的上游公共服务

以下能力依赖上游提供的公共服务，本 fork **未自建替代**，请知悉流量去向：

| 用途 | 地址 | 说明 |
|---|---|---|
| 快照导入 / 导出短链 | `i.gkd.li` / `f.gkd.li` | 截图与规则快照的分享链接由上游服务托管 |
| 快照上传 | `github.com/gkd-kit/inspect` | 经上游 inspect 的附件通道 |
| 应用内文档 / HTTP 服务页面 | `registry.npmmirror.com/@gkd-kit/*` | 直接从上游 npm 包加载 |
| 使用协议 / 隐私政策页面 | `gkd.li?r=11`、`gkd.li?r=12` | 上游文档站（内容同样适用于本 fork 的代码） |
| "捐赠支持"入口 | `github.com/lisonge/sponsor` | **直接捐赠给上游作者**——本 fork 代码主体由其开发，这是有意保留的 |

---

## 免责声明

- 本项目遵循 [GPL-3.0](LICENSE) 开源，**仅供学习交流，禁止用于商业或非法用途**
- 本 fork 是个人非官方修改版，**使用本 fork 造成的一切后果由使用者自负**，与上游作者无关
- 无障碍自动化操作存在误触可能，请自行评估风险后使用

---

## 许可证

[GPL-3.0](LICENSE) · 原始版权归 [gkd-kit/gkd](https://github.com/gkd-kit/gkd) 及其贡献者所有。
