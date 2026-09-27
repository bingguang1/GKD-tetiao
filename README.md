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
| 📌 求助 | 本 fork 特有的问题 → 请提到 [本仓库 Issues](https://github.com/bingguang1/gkd-tejiao/issues)<br/>GKD 应用本身的问题 → 请提到 [上游仓库](https://github.com/gkd-kit/gkd/issues) |

详细的修改声明（GPL-3.0 §5(a) 要求）见 `NOTICE.md`。

> **包名说明**：本 fork 的包名与上游同为 `li.songe.gkd`（为保证老用户能覆盖升级），
> 因此**无法与官方 GKD 同时安装**，也**不能上架 Google Play**。
> 安装本 fork 会覆盖已安装的官方版（若签名不同则安装失败，需先卸载官方版）。

---

## 相对上游的改动

> 版本基线：`1.12.1`（versionCode 106）。以下为**用户可感知**的改动。

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

## 首次运行不再预置第三方订阅

本 fork 早期版本的内置配置里打包了第三方作者的完整订阅
（[梦念逍遥のGKD订阅](https://github.com/MengNianxiaoyao/gkd-subscription)，285 个应用 / 874 个规则组）。
该内容版权属于其作者，**不宜随本仓库二次分发**，现已移除。

**订阅需要你自己添加**：

1. 打开 GKD → 订阅 → 右上角 `+` → 输入订阅链接
2. 第三方订阅列表：<https://github.com/topics/gkd-subscription>
3. 也可用 [subscription-template](https://github.com/gkd-kit/subscription-template) 自建

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
