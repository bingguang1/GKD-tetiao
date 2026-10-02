# 更新内容

本文件记录本 fork（GKD 特调版）的更新；上游 GKD 的更新请见
<https://github.com/gkd-kit/gkd/releases>。

## v117 / 1.12.2

应用内版本号 `1.12.2-fok0025`（versionCode 118）

- **修「关闭快应用」秒退失效**（fok0023）—— 这是 v115 引入的回归：
  快应用引擎是系统包、**桌面上没有图标**，而 v115 新加的"系统界面闸门"把"没有启动入口"当成了系统浮层 ⇒
  引擎被误判并直接 `return`，**秒退永不触发**。现在**引擎判定排在浮层判定之前**。
  真机 A/B（联想平板 / 无桌面入口的引擎靶）：修复前引擎留在前台（0 次拦截），
  修复后 `QuickApp block ... send BACK` + `back ok now=com.android.settings`。
- **修「系统界面判据把真实应用误判成系统浮层」**（fok0025）—— 同样由真机暴露：
  原来用 `getLaunchIntentForPackage(pkg) != null`（有没有桌面入口）当通用判据，但**联想平板(ZUI)** 上
  `com.android.settings` 会被判成"没有入口"（而 `cmd package resolve-activity -a MAIN -c LAUNCHER` 能解析出
  `com.android.settings/.Settings`，说明该 API 在该 ROM 上不可靠）⇒ 设置页被当成系统浮层忽略，
  "从设置页被广告拉进快应用"**静默失效**（日志里 `prev=com.zui.launcher`，而设置明明在前台）。
  实测 `com.android.camera / gallery3d / documentsui` 也都没有桌面入口。
  现在改用**"有没有任何 Activity"**（与 GKD 自己的 `AppInfo.checkHasActivity` 同一套判据）：
  只有完全没有 Activity 的纯服务/插件包才算系统浮层，真实应用一律不受影响。
- **新增「看到引擎但没拦」的诊断日志**（fok0024）：引擎被识别时直接记录
  `QuickApp seen engine=… prev=… guardOn=… prevIsSystemSurface=… engines=…`，
  让这个功能不再是黑盒（本次就是靠它一眼定位到 `prev=com.zui.launcher`）。
- **新增控制面板开关陷阱靶** `PanelSwitchTrapActivity`（测试 App）：3 个与真机同形的
  `Switch(text=关闭)` + 可选真按钮，用于复现/回归「在系统操作面板上误触」。

## v115 / 1.12.2

应用内版本号 `1.12.2-fok0022`（versionCode 115）

- **修复「在系统操作面板上误触」**（用户报："上拉到控制面板时会触发什么东西，关掉 GKD 就不会触发"）
  - **取证**（vivo / Android 16，`gkd-20261002.log` + 控制面板界面树）：控制面板里有 **7 个可点开关**，
    节点形态是 `class=android.widget.Switch`、**`text=关闭`**、**功能名在 `content-desc` 里**
    （飞行模式 / WLAN / 振动模式 / 静音模式 / 省电模式 / 手电筒 / **GKD特调版（本 App 的磁贴）**），
    而「防摇一摇广告」找关闭按钮的判据当时是 `clickable && text.contains("关闭")`
    ⇒ 一天误点 **40+ 次**（`ShakeGuard handled pkg=com.android.systemui via click=关闭`）。
    **点到 GKD 自己的磁贴时直接把无障碍关掉了**（`A11yAutoGuard manualOff=true` + "无障碍已关闭"，
    而手动关闭语义是"守护不拉回" ⇒ 手机长时间处于无保护状态）。
    同一形态还误伤了应用内的功能开关：`tv.danmaku.bili via click=关闭弹幕`、`com.android.camera via click=超微距,关闭`。
  - **三条通道**：① 面板的窗口事件没有过滤系统界面；② 事件来自应用 A、而 `rootInActiveWindow` 是面板的树时仍照扫；
    ③ "关闭"是**包含匹配** —— 而开关的状态文字恰好就叫"关闭"。
    另外「摇一摇跳转防护」把用户**上滑**产生的 `com.vivo.upslide`（上滑面板）与 `com.vivo.hiboard`（负一屏）
    当成"跳到了别的应用"，一天按了 **7 次返回键 + 拉起原应用**。
  - **修复**：新增共用的 `service/SystemSurfaces.kt`，把系统窗口分成**瞬时浮层**（状态栏/通知/转场/无桌面入口的系统包
    —— 忽略但**不清空**源应用，保住 fok0021 的修复）与**用户离开应用的面板**（桌面/上滑面板/负一屏 —— 结束开屏计时）
    两类；`ShakeGuard` 只扫**自己应用**的窗口、**开关类控件一律不点**、"关闭"只认关闭类短语；
    `JumpGuard` / `FakeSkipGuard` / `QuickAppGuard` 不再把系统面板当跳转目标或落点。
    判据除了厂商包名清单，还有一条与 ROM 无关的：**没有桌面启动入口的包 = 系统组件**（新 ROM 自动生效）。
    顺带删除 v104 遗留的惰性模块 `SensorOrientationGuard`（该 ROM 上没有「获取设备方向」appop，只在每次切换应用时刷一行日志）。
  - **验证**（MuMu，新靶 `PanelSwitchTrapActivity`：3 个与真机同形的 `Switch(text=关闭)` + 一个真正可点的「跳过广告」）：
    旧版 fok0020 —— `ShakeGuard handled ... via click=关闭` + 靶侧 `SWITCH-TOGGLED total=1`（**复现**）；
    新版 fok0022 同一页面 —— `ShakeGuard skip ... reason=no-close-button`、靶侧 **开关 0 次**；
    页面里再放真按钮时 —— `ShakeGuard handled ... via click=跳过广告`（**真按钮照点，无回归**）。

## v114 / 1.12.2

应用内版本号 `1.12.2-fok0021`（versionCode 114）

- **修复「摇一摇跳转防护」在部分设备上完全不拦截的 bug**（换新设备装机时真机复现并定位）
  - **根因**：`com.android.systemui` 的**瞬时窗口**（状态栏 / 通知 / 转场）同样会发出
    `TYPE_WINDOW_STATE_CHANGED`，而 `JumpGuard` 只过滤了 GKD 自身、没有过滤它 ——
    于是它把"源应用"改写成 systemui，紧接着那次**真正的**跨应用跳转就被当成
    `systemui → B` 来评估，而 `evaluate()` 对 systemui 是直接 return 的，
    **连一行日志都不会留下**，表现就是"这个功能像是没生效"。
    实测该瞬时窗口在同一分钟内可出现 4 次 ⇒ 真机上是**偶发失效**，很难归因。
  - **修复**：`JumpGuard` 跳过 systemui 事件，让"源应用"始终是**最后一个真实应用**。
  - **验证**（联想 TB710FU / Android 16，名单内应用「便签 → 设置」，gap≈1.35 秒）：
    修复前 **0/3 拦截、零日志**；修复后 **3/3 拦截**
    （`JumpGuard jump … reason=guarded-app, send BACK` → `back ok now=…`，前台确实退回原应用）。
    负向对照（防护名单为空）照旧**不拦截**，并正常给出 `not-guarded` 候选提示。

## v108 ~ v113 / 1.12.2

应用内版本号 `1.12.2-fok0015` ~ `1.12.2-fok0020`（versionCode 108 ~ 113）

- **新增「关闭快应用」三层防护**（fok0015）：流氓广告会借**快应用**（厂商预装的"免安装小程序"
  运行环境，**原生渲染、不是 WebView**）把人从开屏广告拉进快应用广告页并在里面自动下载 APK；
  这类页面**没有可点的"跳过"**，所以"找按钮点跳过"的老思路完全无效。改为三层：
  - **① 识别**：**谁响应 `hap://app/...` 谁就是快应用引擎**（与包名/ROM 无关，最可靠），
    另加包名特征、已知厂商包名与用户手动补充；结果缓存，无障碍热路径只做集合判断
  - **② 秒退**：前台从 A 变成"快应用引擎" → toast + `BACK` 退回 A（退不回去就用启动意图拉回）；
    从桌面/系统界面进入的不拦（那是用户自己开快应用中心）
  - **③ 根治**：停用引擎包、掐掉引擎的"安装应用"权限、结束进程，可一键恢复。
    特权动作走 GKD 已有的 **Shizuku 用户服务**，**不新增任何隐藏 API**
  - 设置页新增「关闭快应用」开关 + 「快应用引擎 (N)」管理页（含深度扫描、复制 adb 命令）
  - ⚠️ **ROM 差异**：MuMu / 联想 ZUI 上「停用引擎」可用；**vivo 非 root 停不掉系统应用**
    （`Cannot disable ... no root permission`），此时只能用"秒退 + 禁止引擎安装应用"
- **修复「摇一摇跳转防护失效」+ 「开屏时长」可配**（fok0016 / fok0017）：两个根因都来自真机日志
  - **① 开屏时长写死 1.8 秒**：真机上广告跳转发生在开屏页出现后的 **4.2s / 5.0s / 6.8s**，一次都追不上
  - **② 让位判据太粗**：旧逻辑是"本次前台期间 GKD 点过任何东西就让位"，而开屏时 GKD 几乎必然点过
    （跳过/关弹窗），于是**整个应用会话都失效**。现在只在 `FakeSkipGuard` 正有一次
    "跳过类点击落点校验在飞"时才让位
  - 计时起点改为**当前页面**出现的时刻（原来按"应用成为前台"会把同应用内换页时间一起累加），
    并加 60 秒上界，保证只在开屏阶段这么算
  - 设置页新增「开屏时长」（1.5 / 2 / 3 / 5 / 8 / 10 / 15 秒，**默认 8 秒**）；
    新增 `not-guarded` 候选提示日志，让"该把哪个应用加进名单"不再是黑盒
- **再修漏拦 + 重做「防摇一摇广告」**（fok0018 / fok0019）
  - 漏拦：用户把开屏时长设成 3 秒，而广告 5~6 秒才跳 → 新增**语义兜底**：源页面类名含
    `splash / advert / adactivity / .ads. / welcome / guideactivity` 时，
    有效窗口取 `max(用户设定, 15 秒)`
  - 取"源页面名"改走 `topActivityFlow`：实测开屏页那一次事件的 `className` 是**空的**
  - **重做 `ShakeGuard`（防摇一摇广告 / 开屏自动关闭）**：旧版 4 天日志里 `handled` **0 次**
    （窗口只有 2 秒，而可点按钮 **3.2 秒**才出现；且强制要求先看到"摇一摇"提示词，
    而那行字是图片/动画、**根本不在无障碍树里**）。现在窗口共用「开屏时长」、
    **不再强制**提示词、规则刚点过 1.5 秒内不补刀；**只做节点点击，绝不打坐标、不做返回键兜底**
- **收尾两处小缺陷**（fok0020）：`not-guarded` 在"刚处置过"时不再打印（否则我们自己的
  relaunch 会被记成一条广告源线索，误导用户）；`ShakeGuard` 拿不到 `rootInActiveWindow`
  时不再占用 300ms 扫描配额

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
