# 更新内容

本文件记录本 fork（GKD 特调版）的更新；上游 GKD 的更新请见
<https://github.com/gkd-kit/gkd/releases>。

## v122 / 1.12.2

应用内版本号 `1.12.2-fok0029`（versionCode 122）

本轮是三件事：**新增「运行日志」页（含清空按钮）** + **清理死代码** + **修一个"名存实亡"的功能**。

- **新增：应用内「运行日志」页**（设置 → 高级设置 → 日志 → 运行日志；控制页也有一张卡片）
  - **为什么**：GKD 的运行日志一直**只写文件**（`files/log/gkd-YYYYMMDD.log`）—— 守卫的每一条决策
    （`ShakeGuard handled` / `JumpGuard jump … send BACK` / `QuickApp block …` / `ClickGuard reject`）、
    订阅异常、崩溃堆栈全在里面，但**在 App 里看不到**：以前只能"连数据线 → `adb pull` → `grep`"。
    用户自己完全无法自查，排障必须先找电脑。
  - **现在的样子**：进入即显示最近的日志（按 `\n\n` 分块，**最新在最上面**）；顶部可按日期切换最近 7 天的文件；
    顶栏有**关键字筛选**（输入 `ShakeGuard` / `JumpGuard` / `send BACK` 直接筛）；
    **守卫决策高亮成主题色、异常(异常/失败/FATAL)标红**；`复制` 一键复制当前筛选结果、`导出` 走原有的分享日志。
  - **读取方式**：文件超过 2 MB 时**只读尾部 2 MB**（排障要的永远是最近一段，真机一天能有上万条），
    并在页面上明确标出"只显示了最近 2 MB"。
  - **新增「清空日志」按钮**（顶栏垃圾桶图标 → 二次确认）：删除 `log/` 下**全部**日志文件。
    日志是持续写入的，所以清空后 App 会重新建当天的文件继续记录 —— 清的是历史，不是把记录功能关掉。
  - 新增文件：`ui/LogFilePage.kt`、`ui/LogFileVm.kt`，路由 `LogFileRoute`（`MainActivity` 注册）。
- **死代码清理**（全部经"全仓引用扫描 + Kotlin 编译告警零新增"核对后删除，Kotlin 编译器本身对
  `private` 未使用项会告警，本次 7 项都是**编译器不报**的 `public/internal` 死角）：
  | 删除项 | 位置 | 证据 |
  |---|---|---|
  | `Modifier.runIf()`（整个文件） | `ui/component/ModifierExt.kt` | 全仓 0 处调用 |
  | `SettingsStore.subsAppShowUninstall` | `store/SettingsStore.kt` | 字段从未被读取（`ignoreUnknownKeys=true`，删掉对旧 store.json 无影响） |
  | `ShowGroupState.addAppRule` | `ui/component/RuleGroupState.kt` | 从未被读/写 |
  | `ResolvedRule.hasNext` | `data/ResolvedRule.kt` | 从未被读取 |
  | `QuickAppController.available()` | `service/QuickAppController.kt` | 从未被调用（UI 用 `shellOk` 判断） |
  | `DeviceOrientationGuard.summary()` | `service/DeviceOrientationGuard.kt` | 从未被调用（页面自己拼结论文案） |
  | `SafeAppOpsService.setModeForPackage/checkMode` | `shizuku/AppOpsService.kt` | v104 传感器路线的遗留，随 `SensorOrientationGuard` 删除后已无人调用 |
- **修：「关联应用守护」的名单以前形同虚设**：`AssocAppGuard` 命中关联 App 时**只打了一行日志**，
  真正干活的还是紧随其后的**无条件** `autoEnsure()`（还带 5 秒节流）⇒ 这个开关和名单**对行为没有任何影响**，
  和设置页写的"打开关联的 App 时若无障碍被清除**立即**恢复"完全对不上。
  现在命中名单时**直接** `ensureEnabled()`（不受节流、不等下一轮 4.5 秒），并保留原日志。
- **修：App 内仓库链接的拼写**：`Constants.kt`（关于/问题反馈/内页入口）与 `App.kt`（`commitUrl`）
  用的是 `bingguang1/gkd-tejiao`，仓库**规范名是 `GKD-tetiao`**（写错虽然靠 301 重定向能打开，
  但每次都要多一跳）。统一成规范名。

## v121 / 1.12.2

应用内版本号 `1.12.2-fok0028`（versionCode 121）
- **修「用微信时开相机 / 开小程序会闪回」等一批"正常操作被守卫打断"的回归**（用户报：这些在 **fok0014 没有**）：
  - **取证**（手机 `files/log/gkd-20261002.log`）：`ShakeGuard handled pkg=com.tencent.mm via click=关闭`
    **一天 21 次**（19:39~20:27 一批、23:10~23:12 又 10 次 —— 后者是我们 22:38 装完 fok0026 之后），
    全部 `shakeHint=false`、`window=1500ms`。用户看到的就是"相机/小程序界面闪一下又退回原来的应用"。
  - **根因（与 fok0014 对比得到）**：v109 为了修"4 天 0 次生效"，把当年**强制要求"摇一摇提示词"**这个前提去掉了，
    又加了"**同应用内换页也重置开屏窗口**" ⇒ 微信小程序右上角的"关闭"、相机界面里的"关闭"都成了候选
    （打开小程序/相机正好是一次换页，窗口立刻重新上膛，于是 4 秒能连点 5 次）。
  - **修法（既保留关广告能力，又不碰正常界面）—— 点击前必须有广告证据**：
    ① 命中"跳过/skip" → 本身就是广告特征，直接点；
    ② 命中**自带"广告"字样**的短语（关闭广告/关闭浮层/关闭弹窗…）→ 算证据；
    ③ **裸"关闭/知道了" → 必须本窗口看到摇一摇提示词，或当前页面像开屏/广告页**（`isSplashLikePage`，
      与 JumpGuard 共用同一份判据），否则只记 `no-ad-evidence` 日志、绝不动手。
- 同时修掉两处"用户意图目标被当跳转目标"的误伤（都是日志里抓到的）：
  - **输入法（键盘）**：`千问 -> com.baidu.input_vivo`（点开键盘）被 `JumpGuard` 按了返回键 ⇒ 现在输入法包
    （动态读启用的输入法）按"瞬时窗口"处理：忽略、且**保留源应用**，任何守卫都不在它上面动手。
  - **相机/相册/文件选择器**：新增 `SystemSurfaces.isUserIntentTarget()`（按标准 Intent 查询谁会响应
    拍照/选图/选文件，懒加载一次），`JumpGuard` 不再把它们当跳转目标、`FakeSkipGuard` 不再把落点算成误点。
- 验收（MuMu，2026-10-02 23:37，靶子 = 新加的 `PlainCloseActivity`：页面只有一个普通可点「关闭」按钮）：
  | 用例 | 靶子日志 | GKD 日志 |
  |---|---|---|
  | 只有裸「关闭」、无广告特征 | 无 `CLOSE-CLICKED` | `ShakeGuard skip … reason=no-ad-evidence close=关闭 shakeHint=false splashLike=false` ✓ |
  | 同页面 + 「摇一摇有惊喜」 | `CLOSE-CLICKED count=1` | `ShakeGuard handled … via click=关闭 shakeHint=true` ✓ |
  | 真跳过靶 `SplashActivity` | —— | `ShakeGuard handled … via click=跳过广告` ✓ |
  | 控制面板开关陷阱靶 | 无 `SWITCH-TOGGLED` | `skip … reason=no-close-button` ✓ |
  另：`FATAL` = 0；同一份日志里还留着旧行为的对照行（`15:46:32 handled … via click=关闭 shakeHint=false` ⇒ 那时同样没证据却点了）。

## v120 / 1.12.2

应用内版本号 `1.12.2-fok0027`（versionCode 120）
- **两处"实测打脸"的文案改正**（都是被真机/真平板暴露出来的，改的是说法、不是逻辑）：
  - **「设备动作与方向」页**：自检说"本机无可编程入口"时，补一句 **"找不到就是这台 ROM 没提供这一项"** ——
    目前确认提供的有 **vivo（OriginOS 5 起）** 与 **小米（HyperOS 3）**；实测 **联想 ZUXOS 把权限/安全中心 APK 全量搜了一遍，
    「设备动作与方向 / 获取设备方向 / 仅开屏」0 命中 ⇒ 该 ROM 压根没有这个设置**。不补这句，用户会在设置里白找半天。
  - **「快应用引擎」页**：原文案说「停用」"推荐"，但实测 **vivo OriginOS 会报 `Cannot disable … no root permission`**、
    **联想 ZUXOS / AOSP 能成功停用**。现在改成"**优先用「禁止安装应用」（多数 ROM 免 root 就能成功）；「停用」在部分 ROM
    只允许 root 停用系统应用**"，并指向电脑上的一键脚本 `release\一键关闭快应用.bat`。
- 配套**新增一键脚本**（不在 APK 里，纯 adb 工具）：`GKD特调版\release\一键关闭快应用.bat` + `quickapp-off.ps1`
  —— 自动识别引擎（谁响应 `hap://` + 包名特征）、逐条执行（停用 / 掐安装权限 / 结束进程）、**如实报错**、
  带 `-WhatIf` 预览与 `-Restore` 还原、多设备要 `-Serial`。实测：联想平板 `pm disable-user` 成功（`hap://` 直接无法解析），
  vivo 会拒绝（脚本如实报错并保留"掐安装权限 + 结束进程"）。
- 验收：联想平板 TB710FU（Android 16 / ZUXOS）上装 v120、页面文案按实测显示、`FATAL` = 0；脚本六个用例（多设备保护 /
  -WhatIf / 关闭 / -Restore / 再关闭 / 别名识别）全绿。

## v119 / 1.12.2

应用内版本号 `1.12.2-fok0026`（versionCode 119）
- **「防摇一摇」改为针对应用传感器的「获取设备动作与方向」权限 —— 走 ROM 自带的「仅开屏禁止」**（用户需求）：
  - **根因**：摇一摇广告能跳转，是应用在**开屏那几秒**读到了加速度计/陀螺仪；系统里这一项在小米叫「获取设备动作与方向」、
    在 **vivo 叫「访问设备动作与方向」**（真机实测，在「传感器」分组里）。2024 年底起 **vivo OriginOS 5**（真机弹窗原文
    `允许 / **仅开屏时禁止** / 禁止`）/ **小米 HyperOS 3**（「仅开屏时拒绝」）直接给了"只在开屏拒绝"的语义：
    开屏广告晃不动手机，而应用内的正常摇一摇（摇一摇换歌等）**照常可用**。
  - **GKD 能做与不能做**：这个开关在 ROM 自己的权限框架里（vivo 上不是标准 AppOps op，见交接文档 §10.5），
    第三方应用**读不到也写不了** ⇒ 本版**不写"自动设置"**，只做**引导 + 自检 + 清单**，全程**不做任何写入**。
  - 新增 `service/DeviceOrientationGuard.kt`：
    ① **只读自检** —— 多路反射枚举本机全部 op 名（`sOpToString` / `sOpToSwitch` / Android 14+ 的 `sAppOpInfos` /
    `opToPublicName(code)` 逐 code 反查）+ 候选权限探测，把"找到什么 / 来源是什么 / 共枚举到几个"显示在页面上
    （MuMu 实测 `opToPublicName` 枚举到 **155 个 op** —— 原来"枚举为空"是因为只试了旧字段）；
    ② **一键跳系统权限页** —— 小米/澎湃、vivo/iQOO、OPPO/一加、华为/荣耀的权限页 ComponentName **逐个静默尝试**
    （失败的候选不弹 toast），全部失败兜底到通用"应用详情页"，并 toast 说明走了哪个入口；
    ③ 按厂商给出菜单路径提示（vivo/小米/OPPO/华为 + 通用兜底）。
  - 新增 `ui/DeviceOrientationAppListPage.kt` / `DeviceOrientationAppListVm.kt` + 路由 `DeviceOrientationAppListRoute`；
    设置页「防摇一摇广告」下方新增入口「**设备动作与方向 (N)**」；清单持久化 `store/device_orientation_app_list`
    （系统状态读不到，所以是**用户自报**的勾选记录，用途是统计/提醒别漏）。
  - `ShakeGuard` **行为不变**，只把定位写清为"兜底"：系统没这个选项、或用户没设过的应用，仍靠它把开屏广告点掉。
  - **按 ROM 用原文**：新增 `itemName()` / `targetOptionName()`（vivo = 「访问设备动作与方向」/「仅开屏时禁止」；
    小米 = 「获取设备动作与方向」/「仅开屏时拒绝」），页面说明、跳转 toast、设置项副标题、菜单路径提示**都用本机原文**
    —— 名字对不上，用户就找不到那一项。
  - **真机实测暴露的两处修正**（已在同一版本内修掉）：
    ① 入口原来挂在「防摇一摇广告」开关的 `AnimatedVisibility` 里，而用户手机上 `shakeGuard=false`（把**兜底**关了）
    ⇒ **根因防护入口整个消失**、根本找不到；现在**常显**（两者是不同机制）；
    ② 文案/路径按 ROM 给原文（见上）。
  - 验收（MuMu / Android 15，`tmp\fok0026-verify\`）：设置页计数、自检结论 + 证据、逐应用「去设置」→
    本机没有厂商权限页时**静默兜底**到 app-details（前台 `com.android.settings/.spa.SpaActivity`）、
    勾选后计数与**强杀重启后仍在**、`FATAL/Exception` = 0。
  - **真机验收（vivo V2238A / Android 16 / OriginOS 16.0，2026-10-02 晚，全绿）**：
    ① 覆盖安装直接 `Success`（`versionCode=119`，配置零丢失）；
    ② 设置页 `设备动作与方向 (0)` **在 `shakeGuard=false` 下也在**，副标题 = "把「**访问设备动作与方向**」设为「**仅开屏时禁止**」"；
    ③ 自检 = `已枚举本机 164 个 op, 未发现「设备动作与方向」类入口`（来源 `opToPublicName(164)`，候选权限命中 = 无）
    —— 与 adb 侧交叉验证一致（`dumpsys appops` 里含 sensor/orient 关键词的只有 `BODY_SENSORS`，`pm list permissions` 里没有那个权限）；
    ④ **deep-link 打通**：点「去设置」→ 前台 `com.vivo.permissionmanager/.activity.SoftPermissionDetailActivity`，
    该页「传感器」分组里就是「访问设备动作与方向」（当前「允许」），点进去弹 `允许 / **仅开屏时禁止** / 禁止`；
    ⑤ 勾选 → 行尾 `去设置` 变 `去改`、计数跟着变；再点一次恢复，**清单文件留 0 字节、手机上零残留**；
    ⑥ logcat `FATAL` = 0、GKD 无异常；无障碍仍绑定（`Bound services: GKD特调版`）。
  - 用法（用户侧）：设置 → 摇一摇 → 「设备动作与方向」→ 逐个应用点「去设置」→ 在「传感器」里把
    「访问设备动作与方向」选成「仅开屏时禁止」→ 回来勾上（仅为记录）。也可走**批量**路径：
    设置 → 权限管理 → 「权限」标签 → 传感器 → 访问设备动作与方向。

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
