# GKD Fork 项目交接文档（换新对话前先读这份）

> ⚠️ **本文件为公开副本（已脱敏）**：原始的本地交接文档中包含签名密钥库口令、真机序列号与签名证书指纹，这些内容**已在本文中替换为占位符**（`<STORE_PASSWORD>` 等）。
> 本地构建请使用自己的密钥库与口令，不要照抄占位符。

> 目标：给新手（新对话的 AI）在**不丢失上下文**的前提下，直接接手"GKD特调版 fork（防摇一摇 + 内置配置 + 无障碍自动守护 + 通知栏一键开关无障碍 + 桌面小组件 + 关联应用守护）在 MuMu/真机上的构建、验证与交付"。
> 最后更新：**2026-10-02**（本轮三件事：① **v107=fok0015 新增「关闭快应用」三层防护**（识别 hap:// 引擎 / 秒退 / 停用引擎+掐安装权限），MuMu 全绿 + 真机实测通过，并查清 **vivo 非 root 停不掉系统应用(快应用引擎)**，见 **§11**；② **v108=fok0016/fok0017 修「摇一摇跳转防护失效」+「开屏时长」可配** —— 真机日志证明旧 1.8 秒窗口追不上 4~7 秒的跳转、旧让位判据让整个会话失效，见 **§12**；③ **v109=fok0018/fok0019 再修漏拦（完美校园→百度网盘 5~6 秒跳转）+ 重做「防摇一摇广告」**（旧版 2 秒窗口 + 强制摇一摇提示词 ⇒ 4 天 0 次生效），并加了"开屏/广告页"语义兜底，见 **§13**。历史版：v106=fok0014「按应用设定」、v105=fok0013 JumpGuard、v104~v97 见 §10 与 §9）

---

## 0. 一句话总览
基于 **gkd v1.12.1 官方 tag** 的 fork（软件名 **GKD特调版**），在 Windows 上完成：
1. 内置**防摇一摇广告**（免 root，设置开关，默认开；v97 起保守化：仅在"摇一摇提示+可点关闭钮"同现时点击，**无全局返回键兜底**）
2. 内置**备份 zip**，首次启动自动导入配置（订阅/规则/设置一次到位）
3. **无障碍自动守护**（v99 强化：见 §3.3）
4. **通知栏一键开关无障碍**（常驻通知按钮）
5. **桌面小组件「GKD快捷开关」**
6. **关联应用守护（v99 新增，自定义关联）**：用户勾选一批"关键 App"，这些 App 被打开时若无障碍被系统清除则**立即恢复**（见 §3.8）
7. **假跳过防护（v100 新增，v101 真机加固）**：点击"跳过/开屏广告"后校验落点，被带到广告落地页时**立即返回**并**自动降级**该 App（见 §3.10）；配套**规则层加固**（见 §3.11）
8. **坐标点击守卫（v102 新增）**：拒绝在"空/反向矩形"的节点上、以及"用户看不到的节点范围内"打盲坐标 —— 真机实证这是微信朋友圈广告"按逻辑关闭却点进广告"的根因（见 §3.12）；配套**微信精准规则** `wx-ad-gkd.json5`
并交付**单文件自签 APK（versionCode 103，GKD特调版-1.12.1-fok0011.apk）**：v100/v101 已在 MuMu 完整验证，v101 补上真机（vivo）暴露的"BACK 回退不生效"问题（§3.10）；**v102 的坐标守卫已在 MuMu 端到端复现+修复验证通过（含 v100 三靶回归全绿）；v103 把微信精准规则烘进内置备份，并已装到真机 + 完成手机侧精准导入与逐组精算验收**（§3.12）。
- v99 本次改动动因：用户反馈 (a) vivo 上无障碍仍会被系统清除且恢复慢/不恢复；(b) 希望增加"关联应用启动（自定义关联）"；(c) 启用无障碍后个别应用出现横屏。
  - 针对 (a)：自适应闹钟 + 亮屏/解锁触发 + 开机失败 +60s 早期重试 + **非破坏性恢复**（真机实测：清空启用列表后 **~3s 自动恢复**；进程被杀场景由闹钟唤醒兜底）。
  - 针对 (b)：新增「关联应用守护」开关 + 「守护关联应用」App 选择页（store 持久化），打开关联 App 时若无障碍不在位立即恢复。
  - 针对 (c)：真机 6 轮完美校园开屏 + 首页实验**未能复现横屏**（自动旋转关闭状态）；代码上已把"恢复时摘除再写回服务"的频次大幅降低（45s 冷却 + 首轮只等不摘），消除"守护反复重启服务导致前台应用界面重建/进入全屏横屏"的最可能诱因；若再遇到请按 §3.9 取证。
- v100 本次改动动因：用户提问"对于'假跳过'广告有什么解决办法"。**假跳过**＝画面上那个"跳过"是**不可点的装饰文字/图片**，下面盖着广告的可点层；用坐标点它 = 点广告 → 拉起浏览器/应用市场/落地页，甚至把小程序踢回桌面。
  - 关键难点（已用真机日志证实）：**真跳过和假跳过在节点属性上无法区分** —— 学习通 `btn_jump`(text=跳过3s) 与微信小程序"跳过"都是 `clickable=false`，但前者点坐标有效、后者点坐标中招（证据见 §3.10）。所以"加 `[clickable=true]`"一刀切会误伤真跳过。
  - 解法分两层：**代码层**用"点击后落点"判定并回退（§3.10，已实现并验证）；**规则层**把泛化的"不可点跳过文字→点坐标"这条规则去掉（§3.11）。
- ⚠️ 权限变化：v99 新增 `PACKAGE_USAGE_STATS`（使用情况访问权限）。**vivo 在 `adb install -r` 覆盖安装时会弹"新权限"确认框，需要在手机上点允许/继续**，否则安装报 `INSTALL_FAILED_ABORTED: User rejected permissions`（同权限后续更新不会再弹）。授权命令见 §4.2。

---

## 1. 目录地图（绝对路径）
```
E:\AI_workspace\gkd\
├── gkd-main.zip / src\gkd-main\        # 官方 main 分支源码（超前开发版，勿用于构建）
├── mnxy-gkd.json5                      # 梦念逍遥订阅 本地副本(v77, 594KB)
├── official-gkd.json5                  # 官方订阅副本（已归档停更 v185）
├── gkd-backup-1788605605617.zip        # 从模拟器导出的配置备份（110KB）
├── custom-gkd.json5                    # 演示用自定义订阅（fakeadstest 规则 + v100 假跳过「朴素规则」靶）
├── localskip-gkd.json5                 # ★ v100 规则层加固：本地精准订阅（学习通/测试靶, 假跳过不点）
├── shots\                              # 全部过程截图/UI dump（*.png / *.xml）
├── debug-notes.md                      # 早期调试纪要
├── phone-pull-20260908\                # v99 真机取证目录：当日 log/db、UI dump、解析脚本
│
├── fork\
│   ├── gkd-fork.jks                    # 签名密钥库 密码 <STORE_PASSWORD> / alias gkd
│   ├── src\gkd-1.12.1\                 # ★ fork 源码（构建根）
│   │   ├── gradle\wrapper\gradle-wrapper.properties   # 腾讯镜像 gradle-9.5.1
│   │   ├── local.properties            # sdk.dir=build-tools\android-sdk
│   │   ├── app\build.gradle.kts        # gitInfo=fok0011、versionCode=103、签名走 -P 参数
│   │   └── app\src\main\
│   │       ├── AndroidManifest.xml     # +PACKAGE_USAGE_STATS(v99)；boot/alarm/notif/widget 接收器
│   │       ├── assets\firstrun\gkd-backup.zip   # ★ 内置配置(v103 起内含 subscription/101.json 微信精准规则; ⚠️ 只首启导入一次, 见 §3.12)
│   │       └── kotlin\li\songe\gkd\
│   │           ├── App.kt              # v99: 亮屏/解锁动态接收器 + AssocAppGuard.start()
│   │           ├── store\SettingsStore.kt        # +enableGuardAssoc(v99)
│   │           ├── store\StoreExt.kt             # +guardAssocAppListFlow(v99)
│   │           ├── service\A11yAutoGuard.kt      # ★ v99: 自适应闹钟+非破坏恢复(45s摘除冷却)
│   │           ├── service\FakeSkipGuard.kt      # ★ v100 新增: 假跳过防护(点后落点校验+BACK回退+自学习降级)
│   │           ├── service\AssocAppGuard.kt      # ★ v99 新增: 关联应用前台检测(UsageStats)守护
│   │           ├── service\BootReceiver.kt / AlarmReceiver.kt   # v99: 开机失败+60s重试 / 触发后重排闹钟
│   │           ├── ui\GuardAssocAppListPage.kt / GuardAssocAppListVm.kt  # ★ v99 新增: 关联 App 选择页
│   │           └── (其余 fork 文件见 v98 版本文档: ShakeGuard/A11yService/GkdTileService/StatusService/Widget)
│   └── emu-*.txt / emu-store.json
│
├── docs\                               # ★ v102 起的过程文档
│   ├── wechat-ad-research.md           # 上游订阅/issue 调研结论(为什么"没有现成规则可导入")
│   └── v102-wechat-ad-plan.md          # 施工单 + 文末《施工结果》(权威版)
├── wx-ad-gkd.json5                     # ★ v102 微信精准规则(本地订阅, 朋友圈广告组; 部署见 §3.12)
├── release\                            # ★ 交付物
│   ├── GKD特调版-1.12.1-fok0014.apk    # ★ v106 最新 APK(跳转防护改为「按应用设定」, 默认空, 见 §10.13)
│   ├── GKD特调版-1.12.1-fok0013.apk    # v105(+ 摇一摇跳转防护 JumpGuard, 全局生效, 见 §10.9)
│   ├── GKD特调版-1.12.1-fok0012.apk    # v104(坐标守卫 + 传感器守卫(真机上惰性, 见 §10.5))
│   ├── GKD一键ADB配置.exe              # ★ v104 新增: 一键 ADB 单文件 exe(内嵌 adb, 双击即用, 见 §10.4)
│   ├── GKD特调版-1.12.1-fok0011.apk    # v103(坐标守卫 + 内置备份里已含微信精准规则 subscription/101.json)
│   ├── GKD特调版-1.12.1-fok0010.apk    # v102(坐标守卫, 内置备份未含规则)
│   ├── GKD特调版-1.12.1-fok0009.apk    # v101(假跳过: BACK 后用新读判定 + 拉起原App)
│   ├── GKD特调版-1.12.1-fok0008.apk    # v100(历史版, 可删)
│   ├── GKD特调版-1.12.1-fok0006.apk    # v98(历史版, 可删)
│   ├── adb-oneclick-setup.ps1 + 一键ADB配置开机自启.bat
│   └── README-安装说明.txt
└── build-tools\android-sdk\            # 构建 SDK（platform android-37.0，build-tools 37）
```
其他环境目录：`E:\AI_workspace\build-tools\jdk21\jdk-21.0.12.1+1`（构建 JDK）、
`E:\AI_workspace\build-tools\gradle-home`（GRADLE_USER_HOME，依赖缓存已热）、
`E:\AI_workspace\跳过广告助手\testapp\`（★ fakeadstest 测试 App 源码；**注意目录已从 `AdSkipper` 改名成中文名**。
  含 `app`(宿主/各广告靶) 与 **`engine`(v107 新增的「假快应用引擎」靶, 包名 `com.example.fakequickapp`, 响应 `hap://`)** 两个模块；
  中文路径靠 `gradle.properties` 的 `android.overridePathCheck=true` 过；debug 签名指向
  `E:\AI_workspace\build-tools\testapp-debug.keystore`(工作区内, 见 §2.3)）、
`E:\AI_workspace\gkd-build\`（★ **v104 起的实际构建根**：纯 ASCII 路径，中文路径会踩 §9 坑1）、
`E:\AI_workspace\adb-oneclick\Program.cs`（★ 一键 ADB exe 的源码，编译命令见 §10.4）、
`E:\AI_workspace\.dotnet\`（.NET SDK 8.0.425，仅用来拿 Roslyn 编译器；产物是 .NET Framework exe）。

---

## 2. 构建环境与命令（重要！）

### 2.1 版本约束
| 项 | 值 | 原因 |
|---|---|---|
| JDK | **21**（不是17） | gkd remap/loc gradle 插件要求 JVM≥21 |
| Gradle | 9.5.1（腾讯镜像，已缓存） | AGP 9.2.1 要求 |
| AGP / Kotlin | 9.2.1 / 2.3.21 | libs.versions.toml |
| SDK | platforms;android-37.0 + build-tools;37.0.0 | compileSdk=37，平台目录名 `android-37.0` |
| 签名 | 自签 gkd-fork.jks，密码 <STORE_PASSWORD> | 与官方不同，**不能覆盖官方版**（须先卸载官方） |

### 2.2 一键构建命令（在 fork 源码根执行）
```powershell
$env:JAVA_HOME='E:\AI_workspace\build-tools\jdk21\jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME='E:\AI_workspace\build-tools\gradle-home'
$env:ANDROID_HOME='E:\AI_workspace\build-tools\android-sdk'
& '.\gradlew.bat' :app:assembleGkdRelease `
  -PGKD_STORE_FILE='E:\AI_workspace\gkd\fork\gkd-fork.jks' `
  -PGKD_STORE_PASSWORD=<STORE_PASSWORD> -PGKD_KEY_ALIAS=gkd -PGKD_KEY_PASSWORD=<STORE_PASSWORD> --no-daemon
# 产物: app\build\outputs\apk\gkd\release\app-gkd-release.apk → 拷贝到 release\GKD特调版-1.12.1-fok0011.apk
```
- 全量约 10~11 分钟，增量约 3~4 分钟。
- 已做源码前置改动（勿还原）：`app/build.gradle.kts` gitInfo 硬编码（commitId=**fok0011**）、腾讯镜像、versionCode=**103**。

### 2.3 测试 App（fakeadstest）构建
> v107 起 `testapp` 多了 `engine` 模块（假快应用引擎靶），命令与两个新坑如下：
```powershell
$env:JAVA_HOME='E:\AI_workspace\build-tools\jdk\jdk-17.0.20+8'
$env:GRADLE_USER_HOME='E:\AI_workspace\build-tools\gradle-home'   # ★ 必须设! 见下方注意
& 'E:\AI_workspace\android-toolchain\gradle-8.9\bin\gradle.bat' :app:assembleDebug :engine:assembleDebug --no-daemon
# workdir: E:\AI_workspace\跳过广告助手\testapp ; 产物 app\build\outputs\apk\debug\app-debug.apk
#                                                 与 engine\build\outputs\apk\debug\engine-debug.apk
# 增量约 30 秒
```
- ⚠️ **`GRADLE_USER_HOME` 必须指到工作区内**：本会话文件策略为 workspace-write 时，gradle 8.9 无法往 `C:\Users\<用户名>\.gradle` 解压 `native-platform.dll`，会直接报 `Could not initialize native services / Failed to load native library`（构建启动前就失败）。文件策略为 danger-full-access 时可以不设。
- ⚠️ **v107 新增两个构建坑（都已修好，别还原）**：
  1. **工程目录是中文**（`跳过广告助手\testapp`）→ AGP 直接拒绝（`Your project path contains non-ASCII characters`）。
     GKD 工程因为**有 aidl** 必须换 ASCII 目录（§9 坑1），但测试 App 没有 aidl，**加 `android.overridePathCheck=true` 就够**（已写进 `testapp\gradle.properties`）。
  2. **debug 签名**：AGP 要在 `C:\Users\<user>\.android\` 建 `debug.keystore.lock`，沙箱下会 `AccessDeniedException`。
     已把本机原 debug keystore 复制到工作区内并显式指定（两个模块的 `build.gradle` 都加了 `signingConfigs.debug`，
     `storeFile = E:/AI_workspace/build-tools/testapp-debug.keystore`）——**用原密钥是为了还能覆盖安装**。
- **七个靶 Activity（都 `exported=true`，可直接 `am start -S -n ...` 冷启动）**（v107 新增第 7 个）：
  | 靶 | 结构 | 用途 |
  |---|---|---|
  | `SplashActivity` | 3 秒倒计时后 `btnSkip`(可点) + 「摇一摇」提示 | 真跳过/防摇一摇（§3.1） |
  | `FakeSkipActivity` | `fakeSkip`(`clickable=false` 的"跳过"文字) 压在整屏可点 `adLayer` 上，点它 → **系统设置**(跨应用落地页) | v100 假跳过主链路（§3.10） |
  | `FakeSkipActivity --ez toHome true` | 同上，但广告层改为**回桌面** | 验证 `left-system` 分支（不抢返回键） |
  | `RealSkipActivity` | `realSkip`(同样 `clickable=false`) 压在可点 `skipLayer` 上，点它 → **同包 MainActivity** | **防误伤**：属性与假跳过完全一样，但不该被回退 |
  | **`MomentsAdActivity`（v102 新增）** | 朋友圈广告卡片：可点 `adBody` 广告层 + **id=`kbe` 的标签行**(`clickable=false`,`childCount=2`,860×65px，与真机同名同形) + 可点 `adCloseX`(✕) + `closeMenu`；**卡片底部留 24dp**，让空矩形时的落点落在卡片内 | v102 复现"按逻辑关闭却点进广告"（§3.12）。参数：`--ez hidden`(标签行 INVISIBLE) / `--ez degenerate`(高度压 0=空矩形) / `--ez toLanding` / `--ez crossApp` |
  | **`MomentsAdLandingActivity`（v102 新增）** | **同包**落地页 + 真 `WebView` | 复用为 v100 跨应用分支的对照；也为将来 T4"同包落地页回退"备用 |
  | **`engine` 模块 `QuickAppAdActivity`（v107 新增，独立包名 `com.example.fakequickapp`）** | 模拟"快应用引擎"广告页：整屏广告 + 假"立即下载并安装"按钮 + 「快应用引擎(测试靶)」标题；**Manifest 里声明 `hap://app` 的 intent-filter**（这才是被 GKD 识别成引擎的关键） | v107 验证「关闭快应用」：① 识别(`响应hap链接`) ② 秒退 ③ 停用引擎。宿主靶入口按钮「演示流氓快应用跳转(hap:// 拉起快应用引擎)」在 `fakeadstest` 的 MainActivity 上（注意该 Activity 是 `exported=false`，**adb 只能起 `.SplashActivity`**）。触摸日志 tag `FakeQuickAppTest`，**0 条 = 只按了返回键、没误点广告页** |
- `FakeSkipActivity`/`RealSkipActivity`/`MomentsAdActivity`/`MomentsAdLandingActivity` 的 `dispatchTouchEvent` 会把触摸来源打进 logcat（tag `FakeSkipTest`）：**注入触摸特征 `deviceId=-1 source=0x1002`**，可用来区分"谁点的"（GKD 的 `clickCenter`/`dispatchGesture` vs 宿主鼠标/真人）。**触摸 0 条 = 确实没点**，这是"守卫生效"最硬的证据。
- 一键装/卸调试台脚本：`E:\AI_workspace\gkd\tmp\fakeskip-rig.ps1`（v100 三靶；`-Disarm` 卸）与 `E:\AI_workspace\gkd\tmp\moments-rig.ps1`（v102 朋友圈靶，`-Probe coord|coordSafe|chain|burst|fake`，`-GuardOff` 关新守卫做 A/B）。取证跑测用 `moments-run.ps1 -Case ...`。脚本与所有调试用 JSON 都在 `gkd\tmp\`。
  - ⚠️ 这两个 `.ps1` 必须存成 **UTF-8 带 BOM**，且调用时**不要**用 `| Select-Object -First N` / `| Select-String` 直接接在脚本后面（会提前中断管道杀掉脚本，导致它没走到"开无障碍"那步 —— 本次踩过，误判了一轮）。要过滤就先 `| Out-String` 收全再在本地过滤。

---

## 3. Fork 功能说明（代码位置/行为）

### 3.1 防摇一摇广告 / 开屏自动关闭（`service/ShakeGuard.kt`）★ v109 重做
- **做什么**：应用刚打开（或刚换到开屏页）的**「开屏时长」内**（与跳转防护共用设置，默认 8 秒），只要窗口里出现**真正可点+可用**的 "跳过 / 关闭 / 知道了" 按钮就点掉它 —— 在摇一摇把页面晃走之前先把广告关掉。
- **v109 之前为什么等于没有**：旧版要求"①2 秒内 ②同时看到摇一摇提示词 ③有可点关闭按钮"。真机实测（完美校园开屏页）：可点按钮 **3.2 秒**才出现（2 秒窗口必错过），而"摇一摇"提示词**根本不在无障碍树里**（是图片/动画）⇒ 4 天日志 `handled` **0 次**。
- **现在的判据**：窗口内、`clickable=true && enabled=true`、文本/desc ≤20 字且含关键词（优先级 跳过 > 关闭 > 知道了）→ 点它；**看到"摇一摇"提示词只作为高置信证据记给 `JumpGuard`**（日志 `shakeHint=`）。规则引擎刚点过 1.5 秒内不补刀。
- **安全边界（别删）**：只做**节点点击**，**绝不打坐标、绝不做返回键兜底** —— 那种"不可点的跳过文字"（假跳过）本模块一律不碰（v97 的教训）。
- 详细改动与验收见 **§13.3–§13.4**。

### 3.2 内置配置首启导入（`App.kt#initFirstRunConfig` + `util/BackupUtils.kt`）
- 不变，见 v98 文档。首次启动导入 assets/firstrun/gkd-backup.zip 一次。

### 3.3 无障碍自动守护（`service/A11yAutoGuard.kt` + `BootReceiver.kt` + `AlarmReceiver.kt`）★ v99 重点改造
- **守护目标**：vivo/小米等 ROM 会清掉无障碍启用列表（重启后、一键清理后、"用着用着"被后台管家摘除），GKD 需自动恢复。
- **统一入口**：`A11yAutoGuard.ensureEnabled(ignoreManualOff, ignoreAutoRestore)`/`addToEnabledList()`/`autoEnsure()`/`ensureFromReceiver()`，所有写回经 `writeMutex` 串行。
- **触发通道（v99 全链路）**：
  1. ContentObserver：监听 `ENABLED_ACCESSIBILITY_SERVICES`（列表被摘）与 `ACCESSIBILITY_ENABLED`（整体开关被关），3s 后自检（进程存活时最快 ~3s 恢复，真机实测）；
  2. **v99 自适应周期闹钟**：由"固定 10 分钟重复"改为**一次性闹钟，每次触发后按运行状态重排**——未运行 2 分钟一发、稳定运行 10 分钟一发；用 `setExactAndAllowWhileIdle`（`*walarm*`，Doze 也可唤醒）。进程被杀（非强行停止）时这是唯一兜底：闹钟唤醒 → AlarmReceiver → ensure + 重排，链不断；
  3. **v99 亮屏/解锁触发**：`App.initScreenOnTriggers()` 动态注册 `SCREEN_ON`/`USER_PRESENT`（这两类广播只能动态注册）。进程被闹钟/开机拉起来后，用户下一次亮屏/解锁即立刻恢复，不必等闹钟；
  4. BootReceiver：`BOOT_COMPLETED/LOCKED_BOOT_COMPLETED/USER_UNLOCKED/MY_PACKAGE_REPLACED` → 先重挂闹钟 → +10s 首检；**v99：首检失败再排 +60s 早期重试**（开机初期系统未就绪导致首写被吞的常见场景）；
  5. **v99 关联应用守护**：`AssocAppGuard` 每 ~4.5s（熄屏 10s）感知前台 App，打开关联 App 时若无障碍不在位立即恢复（见 §3.8）。
- **v99 非破坏性恢复（重要）**：`ensureEnabledLocked` 改为——服务名已在启用列表但未绑定成功时，**第一轮只补写整体开关并等待 2.5s**（多数是系统重启/刚解除停用尚未绑定），**不再立刻摘除再写**；持续失败才做"摘除→重写"，且**两次摘除间隔 ≥45s**。目的：避免服务反复断开/拉起（这会打断前台应用、可能引发部分应用重建/进入全屏等怪异状态，见 §3.9），也减少"无障碍已启动/已关闭"的提示骚扰。
- 手动关闭语义不变：通知栏按钮/控制页/快捷磁贴关闭时置 `manualA11yOff=true`，守护尊重不拉回；成功启动自动清标记。
- 生效前提：`WRITE_SECURE_SETTINGS` 已授予（`adb shell pm grant li.songe.gkd android.permission.WRITE_SECURE_SETTINGS`）。
- **v99 真机实测（vivo V2238A, 2026-09-08）**：
  - 全新状态（列表空+开关关）打开 App → **~14s 内自动恢复**（watcher/启动自检路径，日志 `ensure attempt=0 write-back → onA11yConnected`）；
  - 进程存活时把列表删空 + 总开关关掉 → 服务 onDestroy（"无障碍已关闭"）→ **~3s 后自动写回并重连**（日志时间戳 18:49:02 关闭 → 18:49:05 恢复）；
  - `dumpsys alarm` 可见 `*walarm*:li.songe.gkd.action.AUTO_A11Y_CHECK`（自适应一次性闹钟已挂）；
  - 19:00 前后 vivo 再次自动摘除一次，v99 数秒内恢复（用户未察觉/未操作）。
  - ⚠️ 已确认边界（vivo 系统限制，代码无法逾越）：用户"强行停止/一键清理"会把包置为 `stopped`（`dumpsys package` 见 `stopped=true`），期间**任何广播/闹钟都不投递**，只能等下次手动打开 GKD（或引导用户允许自启动 + 不清理 GKD，见 §4.2/§7）。重启后不打开 App 的恢复依赖 BootReceiver + 解锁触发，**下次真机重启务必再实测一次**（§7.1）。

### 3.4 通知栏一键开关无障碍（`notif/` + `service/StatusService.kt`）
- 不变，见 v98 文档。开启「控制 → 常驻通知」后常驻通知上出现「开启/关闭无障碍」按钮，与磁贴同一套开关语义（手动关闭置标记）。

### 3.5 软件名与一键更新规则
- 软件名 GKD特调版；订阅（梦念逍遥 v77+，npmmirror 源）下拉/每天自动更新。不变，见 v98 文档。

### 3.6 Manifest / 版本变化
- v99 manifest 新增：`PACKAGE_USAGE_STATS`（关联应用守护需要"使用情况访问权限"；vivo 覆盖安装会弹新权限确认，须在手机上允许）。
- `versionCode 103 / versionName 1.12.1-fok0011`（app/build.gradle.kts gitInfo.commitId=**fok0011** 硬编码）。

### 3.7 桌面小组件快捷开关（v97/v98）
- 不变，见 v98 文档（WGkdWidget 多路刷新）。

### 3.8 关联应用守护（v99 新增）★ 用户点名功能
- **语义**：用户在「设置 → 常规 → 关联应用守护（开关，默认开）→ 守护关联应用（选择页）」自定义一批"关键 App"（如微信/网易云）。这些 App **被打开（回前台）时**，如果 GKD 的无障碍不在运行/不在启用列表，守护会**立即**恢复（不必等 2~10 分钟闹钟，也不必手动打开 GKD）。
- **代码**：
  - store：`SettingsStore.enableGuardAssoc`（开关，默认 true）+ `StoreExt.guardAssocAppListFlow`（包名列表，独立文本文件 `store/guard_assoc_app_list.txt`，跟随备份导入导出）；
  - `service/AssocAppGuard.kt`：进程内守护循环（App.onCreate 启动，幂等）。熄屏休眠 10s/轮，亮屏未运行 4.5s/轮；尊重 `manualA11yOff`/`autoRestoreA11y`/`useA11y`/`局部关闭` 语义；通过 `UsageStatsManager.queryEvents` 感知最近 3s 内回前台的 App（需使用情况访问权限），命中关联列表 → 打日志 `GuardAssoc trigger pkg=...` 并走统一 `autoEnsure()`；
  - UI：`ui/GuardAssocAppListPage.kt` + `Vm`（复用 `useAppFilter` 全局应用列表，搜索 + AppCheckBoxCard 勾选，顶部说明文字），导航注册于 MainActivity（`GuardAssocAppListRoute`），设置页入口在「无障碍自动守护」开关下方。
- **授权**：使用情况访问权限授予命令（USB 调试可用时）：`adb shell appops set li.songe.gkd android:get_usage_stats allow`（已在本机执行，实测 GET_USAGE_STATS: allow）。系统侧另可在 设置→隐私→权限管理→使用情况访问权限 开启（若 manifest 已声明则该项会出现）。
- **局限（务必向用户说明）**：进程被系统彻底杀死时无法"感知"别的 App 打开；此时靠 闹钟(≤2min) + 亮屏/解锁 + 开机 恢复，本守护随进程一活即恢复实时性。进程被"强行停止/一键清理"后无解（系统禁止一切唤醒），只能手动开一次。
- 真机实测：设置页正常渲染；选择页打开正常（标题/说明/列表）；勾选微信后 `guard_assoc_app_list.txt` 立即写入 `com.tencent.mm` 且跨覆盖安装保留。

### 3.9 关于"启用无障碍后个别应用出现横屏"（v99 处理记录）
- 现象：用户报告开启无障碍后部分应用出现横屏。
- ★★ **定论（后续真机 adb 取证，推翻下面的 v99 结论，以本条为准）**：与 GKD/无障碍**无关**。根因是**显示卡在 WMS 的 `free`（跟随握姿传感器）模式**，手机一倾斜或平放，普通竖屏应用启动时就横屏。
  - **权威判据**：`adb shell cmd window user-rotation` → `free`（病） / `lock 0`（好）。⚠ **`settings get system accelerometer_rotation` 会误判**（该 ROM 上它为 0 时 WMS 仍可能停在 free），不能只看它。
  - **修复（三步缺一不可；该 ROM 上只 `settings put accelerometer_rotation 0` 不够）**：
    `settings put system accelerometer_rotation 0` + `settings put system user_rotation 0` + `cmd window user-rotation lock 0`
  - **诱因（adb 调试副作用）**：本仓 `GKD特调版\release\adb-oneclick-setup.ps1`、`adb-tools\adb-setup.ps1` 用
    `monkey -p <pkg> -c android.intent.category.LAUNCHER 1` 拉起 App，而 **monkey 会把 `accelerometer_rotation` 写回 1**
    → 调试跑完，手机就进入"倾斜/平放即横屏"状态。用 `am start` 代替 monkey 可避免。
  - **一键复修**：`E:\AI_workspace\锁竖屏修复.bat`（幂等，可反复跑）。代码上下文见 `adb-oneclick\Program.cs` 的 `HandleRotation` / `PrintRotation`。
- 排查结论（v99 阶段，**已被上面定论推翻**，保留仅为记录）：
  1. 本机「自动旋转」实际为关（`accelerometer_rotation=0`），系统不会因传感器翻转——横屏只能是应用自身进入全屏/横屏（视频全屏、横屏页面、或界面被重建后回到横屏态）。
  2. 真机 6 轮完美校园开屏 + 首页实验**全程竖屏，未复现**；用户现场操作也确认"目前没有这种情况"。
  3. 最可能诱因（代码侧）：v97/v98 守护恢复时**反复"摘除→重写"无障碍服务**，服务断开/重启瞬间可能造成正在前台的应用界面重建（部分 ROM/应用会因此回到/进入横屏播放态）。v99 已改为**非破坏性恢复 + 45s 摘除冷却**（§3.3），从根上消除这一诱因。
  4. 其次可能的诱因（订阅侧）：梦念逍遥订阅里微信等"分段广告"高点击组点击到视频元素/全屏控件。若再遇到横屏，按下面取证后处理（关闭对应 App 的该分组即可，无需改代码）：
     - 取证：`dumpsys display | grep mCurrentOrientation`（0 竖/1 横）、GKD 日志当天文件里该时刻 `addActionLog` 的 `AttrInfo`（GKD 点了哪个节点）、`dumpsys accessibility` Bound/Enabled。
     - 若 GKD 点击与横屏时间吻合 → 在「应用 → 微信/对应 App → 规则组」里关闭"分段广告-XX"类高频组或精确调低。
- 若用户后续能稳定复现，务必记录：哪个 App、操作到哪一步、是否在 GKD 自动恢复的瞬间发生（这三点决定是订阅误点还是服务重启副作用）。

### 3.10 假跳过防护（v100 新增）★ 本次重点
- **要解决的问题**：开屏广告的"跳过"有两种形态，**在节点属性上完全一样**（都是 `clickable=false`）：
  1. **真跳过**：文本节点自身不可点，但**点它的坐标有效** —— 例：学习通 `id=com.chaoxing.mobile:id/btn_jump, text=跳过3s, clickable=false`，`clickCenter` 后正常进 `MainTabActivity`（真机日志 5207-5221 行）；
  2. **假跳过**：那个"跳过"只是装饰文字，**下面盖着广告的可点层**，点它的坐标 = 点广告 → 拉起浏览器/应用市场/落地页，甚至把小程序的广告页点出去（真机日志 429-435 行：点 `text=跳过, clickable=false` 后 7 秒回到桌面 `com.bbk.launcher2`，疑似中招）。
- **为什么会有缝**：GKD 默认动作 `click` 是"节点可点就 `clickNode`，**不可点就退化成 `clickCenter` 打坐标**"（`data/GkdAction.kt:91-104`）。所以**只靠选择器无法区分真假跳过**：加 `[clickable=true]` 会误伤学习通那类真跳过。→ **判据只能是"点击之后落到了哪里"。**
- **代码**：`service/FakeSkipGuard.kt`（新增），两道闸：
  - **闸 A 点前否决** `allowAction(rule, node)`：若该 App **已在降级名单**且目标节点**不可点**且是"跳过类"语义 → **不点**（宁可不跳也不误点）。同一节点被否决后 3s 内，同节点的其它规则（全局开屏组常有多条 rule）一并否决。
  - **闸 B 点后校验** `onActionExecuted(rule, topActivity, node, actionResult)`：对"跳过类点击"在 **1.2s** 后校验前台包，判定如下：

    | 落点 | 判定 | 动作 |
    |---|---|---|
    | 仍是同一个 App（换没换 Activity 都算） | 正常跳过 | 只记日志 `FakeSkipGuard ok` |
    | **另一个应用**（浏览器/市场/落地页/系统设置…） | **假跳过误点** | 记日志 + **BACK 立即返回** + 拉黑该 App + 强制 toast 提示 |
    | 桌面/系统界面（launcher/systemui） | 疑似被广告踢出，但**也可能是用户自己按了 Home** | 只记录，**同进程累计 ≥2 次**才拉黑；**不抢返回键** |

  - **"跳过类"触发前提**（防误伤）：节点 text/desc（≤20 字）含 `跳过/跳過/skip`，**或**所在规则组名含 `开屏/splash/启动广告`。**故意不含泛化的"广告"二字** —— 否则会误伤"分段广告-卡片广告"这类正常的关闭类点击。
  - 参数：`VERIFY_DELAY=1200ms`、`BACK_WAIT=600ms`、`COOLDOWN=4000ms`、`VETO_MEMO=3000ms`、否决日志节流 3000ms（规则匹配循环约 300ms 一轮，不节流会刷屏）。
  - 接入点（`a11y/A11yRuleEngine.kt` 各 1 行，`performAction` 之前/`addActionLog` 之后）。
- **与 §3.1/v97 的关系（重要）**：v96 因"找不到关闭按钮就无条件按返回键"误退微信，v97 已删除兜底。本模块的返回键**不是兜底**，而是"已证实跳到了别的应用"这一确定性条件下的回退，语义完全不同 —— 日志里也能看到正常跳过时它只写 `FakeSkipGuard ok` 不动手。
- **设置与持久化**：`SettingsStore.fakeSkipGuard`（开关，默认开）+ `SettingsStore.fakeSkipVetoApps`（降级名单，换行分隔，随 store.json 持久化、覆盖安装保留）。设置页新增「假跳过防护」开关 + 「已降级应用 (N)」一行（点击一键清空恢复）。
- **v100 验证（MuMu 15，2026-09-10，重启模拟器后复跑过一轮，结论稳定）**：调试台一键脚本 `E:\AI_workspace\gkd\tmp\fakeskip-rig.ps1`（装/卸台；装好后三个靶都能一键冷启动）。

  **A. 假跳过主链路（靶 `FakeSkipActivity`：`fakeSkip` = `clickable=false` 的"跳过"文字，压在整屏可点 `adLayer` 上，点它跳系统设置）**
  1. 朴素规则 `[id=".../fakeSkip"][visibleToUser=true]` + `action:clickCenter` 触发：
     `AttrInfo(id=com.example.fakeadstest:id/fakeSkip, text=跳过, clickable=false, left=804,top=252,right=1008,bottom=379)` → `ActionResult(action=clickCenter, result=true, position=(906.0,315.5))`；
     靶子侧 `FakeSkipTest: touch action=0 raw=(906.0,316.0) deviceId=-1 source=0x1002` ← **注入触摸**穿透到广告层 → 打开系统设置（跨应用跳转成立）。
  2. 防护命中：`FakeSkipGuard misclick pkg=... -> com.android.settings target=跳过, send BACK` → `veto-add ... reason=to:com.android.settings total=1` → toast「假跳过防护: 已停止在「假广告测试」自动点击不可点的跳过文字」。
  3. 回退成功：`FakeSkipGuard back result=true final=com.example.fakeadstest`，前台回到 FakeSkipActivity（没被留在设置里）。
  4. 自学习生效：再开一次陷阱（冷启动）→ **靶子侧一条触摸日志都没有**，只有（节流后的）`FakeSkipGuard veto pkg=... text=跳过`；`store.json` 落盘 `"fakeSkipVetoApps":"com.example.fakeadstest"`。

  **B. 四个判定分支的完整矩阵**（都在 MuMu 上实测）

  | 场景 | 靶 / 配置 | 实际结果 |
  |---|---|---|
  | 跨应用落地 = 假跳过 | `FakeSkipActivity`（默认） | `misclick` → BACK → 拉黑 ✓ |
  | 同包正常跳过 = **不该管** | `RealSkipActivity`（同样 `text=跳过/clickable=false`，但点了进同包 MainActivity） | 只记 `FakeSkipGuard ok ... activity=...MainActivity`，**零回退、名单仍为空** ✓ |
  | 落到桌面 | `FakeSkipActivity --ez toHome true` | 第 1 次 `left-system ... count=1`（**不回退**）；第 2 次才 `veto-add reason=left-system` ✓ |
  | 开关关闭 | store `fakeSkipGuard=false` | 点了陷阱后**留在系统设置**，`FakeSkipGuard` 日志 **0 条** ✓ |
  | 拉黑后真按钮还能点吗 | 拉黑状态下跑 `SplashActivity`（`btnSkip` 是 `clickable=true`） | 照常 `ActionResult(action=clickNode)` 进主界面 ✓（否决**只**挡"不可点的跳过文字"） |
  | `action:'none'`（识别但不点）× 6 轮冷启动 | 本地精准规则 | 每轮 `created=1`、**touch=0**、不跳转 ✓ |

  - 附带发现：把 `com.example.fakeadstest` 这种"真跳过+假跳过"混在一起的 App 交给**梦念逍遥全局开屏组**时，它的规则**也会**去点那个不可点的"跳过"（实测两条订阅在同一节点上各点了一次）—— 这就是真机上假跳过的产生方式，也说明 §3.11 的规则层加固有必要。

- **v101（fok0009）真机修正：回退不再只靠 BACK** ★ 由 vivo 真机实测驱动
  1. **真机现象**（vivo V2238A / Android16，用用户自己的梦念逍遥订阅复现）：全局开屏组点中 `[id=...fakeSkip, text=跳过, clickable=false]` → `clickCenter (906,360)` → 跳到系统设置 → 防护 `misclick ... send BACK` **返回 true 但前台没变**（日志里 BACK 之后**没有任何窗口变化**，人还停在设置里）。
  2. **同时暴露判断缺陷**：`topActivityFlow` 是**缓存值** —— 屏幕锁了/没有新事件时它会一直停在旧值，用它判断"是否回退成功"不可靠（v100 的 `final=` 就是这么读的）。
  3. **v101 改法**（`service/FakeSkipGuard.kt`）：
     - 判定改用 `currentForegroundPkg()` **新读一次**当前窗口包名（先 `A11yService.instance.rootInActiveWindow`，再退到各窗口里 `isFocused/isActive` 的那个），不再只看缓存流；
     - BACK 之后若仍未回到原 App → `relaunchApp()` 用**原 App 的启动意图**把人拉回来（GKD 有 `SYSTEM_ALERT_WINDOW` + 无障碍服务，不受 Android 后台启动限制）；`getLaunchIntentForPackage` 拿不到意图就只记日志不动作；
     - **"被踢到桌面"那一路**：累计到第 2 次、判定成立时，同样执行 `relaunchApp()` 把用户带回原 App；
     - 新增日志：`back ok sent=.. now=..`、`back missed sent=.. now=.. relaunch=.. after=..`、`left-system relaunch=..`。
  4. **MuMu 复验（v101 实测）**：跨应用落地 → `misclick` → `back ok sent=true now=com.example.fakeadstest`（前台回到靶子）✓；桌面路第 2 次 → `veto-add` + `left-system relaunch=true` → 前台回到靶子 ✓；真跳过仍只记 `ok` ✓。
  5. ⚠️ **真机上 v101 尚待复验**：装 v101 时手机 USB 掉线中断（`adb devices` 里消失、安装报空错误）。手机重连后要做的第一件事：`adb -s <真机序列号> install -r E:\AI_workspace\gkd\release\GKD特调版-1.12.1-fok0009.apk`，再跑三靶（见 §4.2）。
- **局限（务必向用户说明）**：
  1. 只处理**跨应用**落地。假跳过若在**同包内**打开 WebView 落地页（`appId` 不变），本模块不介入（不抢返回键，避免误退）。这种场景要靠 §3.11 的规则层（不点不可点文字）。
  2. 落到桌面只记录、不抢 BACK（怕和用户自己的 Home 抢），累计 2 次才拉黑。
  3. 降级后该 App 里"不可点的跳过文字"不再自动点，**真正可点的按钮照常点**；副作用是被降级的 App 可能不再自动跳开屏（用户可在设置页清空名单）。
  4. 首次误点**拦不住事前**，是"事后 1.2s 内感知 + 回退"；不是预防，是止损 + 学习。
  5. ⚠️ 排查记录（已基本排除）：首次验证时曾出现一次（13:56:40）"规则状态 `超出匹配时间`、GKD 日志无点击记录，但靶子仍跳到系统设置"。重启模拟器后做了 **6 轮冷启动压力测试（`action:'none'`，每轮确认 `created=1`）全部 touch=0、零跳转，未复现**；且装上带触摸日志的靶后，正常路径都能看到 `deviceId=-1` 的**注入触摸**与之对应。判断当时是宿主鼠标/MuMu 窗口焦点造成的杂散点击，**不是防护失效**。若日后复现：看靶子 `FakeSkipTest` 触摸日志 + `adb logcat -v time | Select-String 'START u0.*settings'` 定位是谁拉起的。

### 3.11 规则层加固（v100，与 3.10 配套）
- **核心结论**：既然"不可点的跳过文字"分不出真假，那**规则层就不要再对这类节点做坐标点击** —— 把官方/社区订阅里那条泛化的"任意 `text*="跳过"` → 点坐标"的规则去掉，只在**明确是跳过按钮**时才点：
  | 杠杆 | 写法 | 作用 |
  |---|---|---|
  | 可点优先 | `[clickable=true]` + `action:'clickNode'` | 只点真按钮，绝不打坐标 |
  | 明确 id 才用坐标 | `[id="xxx:id/btn_jump"]` + `action:'clickCenter'` | 覆盖学习通这类"文本不可点但坐标有效"的已知按钮 |
  | 只点一次 | `actionMaximum: 1`（rule/group 级） | 避免第二次点落在广告上 |
  | 排除倒计时 | `[!(text~="\\d+\\s*[sS秒]")]`、`[!(text*="秒后可跳过")]` | 官方订阅就栽在这（`debug-notes.md:40-41`：点到"2 秒后可跳过"的倒计时文字） |
  | 尺寸/位置 | `[width<500&&height<300][top>0][left>0]` | 真跳过按钮都小且不在整屏广告层上（订阅里已有 525 处 `clickable=true` 同款写法） |
  - 注意：`screenWidth/screenHeight` 这些变量**只在规则 `position` 表达式里可用**（`data/RawSubscription.kt:624-647`），**不能**写进选择器属性表达式；`!~=`（取反匹配）是支持的（`selector/.../CompareOperator.kt:296`）。
- **产物**：`E:\AI_workspace\gkd\localskip-gkd.json5`（本地精准订阅，可直接导入）：
  - `com.chaoxing.mobile`（学习通）「开屏广告」组：① `[vid="btn_jump"][clickable=true]` → `clickNode`；② `[id="com.chaoxing.mobile:id/btn_jump"]`（排除倒计时）→ `clickCenter`，`actionMaximum:1`。
    **收益**：组名与全局组同名 → 梦念逍遥全局开屏组在该 App 自动让位（其全局组带 `disableIfAppGroupMatch:'开屏广告'`），把真机上"连点 2 次坐标"变成"精准点 1 次"。
  - `com.example.fakeadstest`：真跳过组（`btnSkip` 可点才点）+ 「假跳过陷阱-识别但不点」组（`action:'none'`，只记录不点）。
  - ⚠️ 导入方式：GKD 订阅页导入该文件（走 GKD 的解析/规范化）；**不要直接丢进** `files/subscription/<id>.json` —— 磁盘缓存里是**规范化后的严格 JSON**，手写 JSON5（单引号）会解析失败且不报错（本次踩到）。
- **微信小程序开屏的现场证据（建议处置）**：梦念逍遥 v77 的微信应用组里存在无 `clickable` 约束的 `[text="跳过" || text="跳過"][visibleToUser=true]`（所属组名含"开屏广告-1/2"、"全屏广告-小程序部分通用广告"），默认动作会退化成坐标点击 —— 与真机日志 431-435 行"点掉不可点的跳过 → 回到桌面"高度吻合。
  - 处置：在「应用 → 微信 → 规则组」里关掉上述几条高频组，改用 §3.10 的防护兜底（或把 `[clickable=true]` 补进这些选择器再自建本地订阅）。**别把 localskip 的微信组和订阅里的同名组同时开着**，否则同一节点会被两个组各点一次。

### 3.12 微信朋友圈广告「点进广告 / 关闭很慢」（v102 守卫 + v103 规则烘包/真机导入，均已落地）★ 当前重点
- **用户报的症状**：(a) 朋友圈广告**偶现"按逻辑关闭但点进广告"**（怀疑重复点击）；(b) **有些情况关闭很慢**。
- **根因（真机日志级证据，已定论）**：梦念逍遥 v77 的微信组 `key=0 分段广告-朋友圈广告`（用户 `subs_config` 里显式开了）里有条 **`key=1` 盲坐标规则**：有 `position` 但**没 `action`** → `ResolvedRule.kt:160` 判定成 **`clickCenter` 强制打坐标**；而 `Position.calc`（`RawSubscription.kt:276`）**只用 `ScreenUtils.inScreen` 校验屏幕边界，从不校验这个点是否落在节点自己身上**；该规则的 `anyMatches` **明确包含 `[visibleToUser=false]`** 的装饰节点。
  - 真机现场（`phone-pull-20260908\gkd-20260908.log`）：`AttrInfo(id=com.tencent.mm:id/kbe, clickable=false, visibleToUser=false, top=2286, bottom=2274, height=-12)` → `clickCenter (981.178, 2280.0)` —— **矩形反向（空矩形）+ 节点不可用**，这一枪打在一个"没被绘制"的位置上 = **打在广告卡片上 → 点进广告**。"偶现"的正解：取决于抓取时机那一下矩形的状态。
  - **"关闭慢" = 打空后的重试成本**：第一枪没生效 → 等 `actionCd`（默认 **1000ms**）补一枪；日志里 00:17 那轮 `51.812 → 54.107（同一 key=1 补枪）→ 54.528 key=25 → 54.860 key=50`，**真正关掉只花 0.33s**，总 3.05s 全花在打空上。
  - ⚠️ **不是"同轮双发"**：82 条真机点击日志里**没有一次同毫秒的两条**；用户怀疑的"重复点击"实际形态是**同一条规则隔 1.4~2.3s 补枪**。（补充实测：v102 探针下同轮内两条规则**确实会连着触发**——M6 里"弹菜单→关闭"只隔 12ms，而这是**好事**，见下条 T3 撤销。）
- **上游调研结论（详见 `docs\wechat-ad-research.md`）**：**没有现成可导入的解**——梦念逍遥 npmmirror `latest` 就是 v77（与本地逐字节一致，没修）；Lin-arm(★5210)/YaChengMu/Lin-arm-Rules/AIsouler 自用**全都保留同款坐标规则**，其中两个直接在 `desc` 写"**有可能会误触，请谨慎开启**"；GKD 本体也**从未**加过坐标校验（`gkd-kit/gkd` 最新 release = v1.12.1 = 本 fork 基础版；PR 搜 `坐标 in:title` = 0 条）。官方仓库里同症状 issue **gkd-kit/gkd#1238「微信朋友圈广告误触」**被一句"这是你订阅的问题，更新订阅就行了"关掉。唯一更保守的蓝本是 **`mrlctate/gkd-mrlc`**：坐标那条写成 `[vid="kbe"][childCount=2][visibleToUser=true]`（`vid=kbe` 就是日志里那个节点 id）。
- **方案与施工单**：`docs\v102-wechat-ad-plan.md`（**文末《施工结果》是权威版**，正文 T3/T4/T5 初稿仅作推导过程保留）。已实施：
  1. **代码层（v102=T2，收益最高）**：`GkdAction.kt` 新增 `clickGuardRejectReason()`/`logClickGuardReject()`（2s 节流），在 `ClickCenter.perform`/`LongClickCenter.perform` 的 `ScreenUtils.inScreen` **之前**拦截 —— **G1 `rect-empty`**：`width<=0||height<=0` 拒点（**零误伤**）；**G2 `invisible`**：`!visibleToUser` **且**落点在该节点范围内才拒（故意用不可见节点当"坐标原点"点别处的规则不受影响）。设置项 `strictClickGuard`/`guardInvisibleNode`（都默认开，设置页有开关，「假跳过防护」下面）。⚠️ **仍然不做"点必须落在节点内"**：审计发现订阅里 20 条带 `position` 的规则中有 **5 条是故意点在节点外**（抖音 `top:'width*2.0649'`、鄂汇办 `top:'width*-1.9094'`、软件包安装程序 `left:'width*1.5394'`×2、有道词典 `top:'width*-1.0673'`），加那条会打断它们。
  2. **规则层**：新增本地订阅 `wx-ad-gkd.json5`（朋友圈广告组）：坐标兜底收紧为 `[vid="kbe"][childCount=2][visibleToUser=true][width>0&&height>0]` + `actionCd:1200`；**`[直接关闭]` 从第三段提到第二段**（依据 [Lin-arm/GKD_subscription#194](https://github.com/Lin-arm/GKD_subscription/issues/194) 维护者原话"点击[直接关闭]要放到第二段了，原先是第三段的，所以第一段触发后会卡住"= "关闭慢/卡住"的官方口径）。部署必须**关掉上游同名组**（`disableIfAppGroupMatch` 只对全局组生效）。
  3. **⚠️ 两处证据驱动撤销（与初稿不同，别再照初稿做）**：
     - **T3「同组同轮只执行一次动作」撤销**：M6 实测证明同轮连触发正是分段链能快的原因（12ms）；加 T3 会把链拆成两轮、**多花 300ms**，正好砸在"关闭慢"上。防重复改由规则层 `actionCd` 承担。
     - **规则层禁用 `actionMaximum` 与组级 `actionCdKey`**：`A11yState.kt:199-233` 表明 `resetMatch` 三种取值的重置时机**都是"进入应用/Activity"** → 朋友圈是**单 Activity 滚动流**，`actionMaximum:1` = "整次进朋友圈只关第一张广告" = **回归**；`actionCdKey` 会串行化整组、同样伤速度。
     - **T4「同包落地页回退」推迟**：T2 已拿到 S1 全部收益；T4 判据（同包+WebView）会命中微信正常内嵌网页，误退风险需真机取证才能收敛。
- **✅ MuMu 实测（2026-09-16，serial 127.0.0.1:16384）**：
  - **M0 基线复现（v101 + 不安全探针）**：`AttrInfo(vid=kbe, clickable=false, visibleToUser=false, height=0 空矩形, width=860, childCount=2)` → `ActionResult(clickCenter,(808.4,1248.0))` → 靶侧注入触摸 → ★广告被点击 → **进同包落地页**（症状复现）。
  - **M1 修复（v102 + 同一探针）**：`ClickGuard reject reason=rect-empty rect=[0,1248][860,1248]` + **靶侧 0 触摸** + 前台**没进落地页** ✓
  - **M3/R1/R2 无回归与规则层**：矩形正常时坐标点击照常命中 ✕（无 reject）；安全选择器在正常形态能匹配、在空矩形形态**根本不匹配(0 触摸)** ✓
  - **回归全绿**：`SplashActivity` 真跳过 → `clickNode` 成功；`FakeSkipActivity` → `misclick→veto-add→BACK` 回原 App；`RealSkipActivity` → `FakeSkipGuard ok` 不回退；设置页两个新开关正常渲染无崩溃 ✓
- **残留风险**：①"**合法坐标打偏**"未根治（真机 13:29 那次是节点可见、矩形正常、点在节点内，但卡片在读取与落点之间上移 135px）——两个守卫都拦不住；若真机仍偶发，下一步就是**删掉 `wx-ad-gkd.json5` 里 key=1 那条坐标兜底**（失败方向是"关不掉"，不是"点进去"）。②T4 未实施。③规则组与上游同名，部署必须关上游组。
- **仿真靶（MuMu 可验证，不需要真机）**：测试 App 新增 `MomentsAdActivity`（**行 id 用 `kbe` 对齐真机**、可点 `adBody` 广告层、可点 `adCloseX`、`closeMenu` + **卡片底部留 24dp** 让空矩形时的落点落在卡片内）+ `MomentsAdLandingActivity`（含真 WebView）。启动参数 `--ez hidden true`/`--ez degenerate true`/`--ez toLanding true`/`--ez crossApp true`。
- **★ v103 交付：规则烘进 APK + 手机侧精准导入（2026-09-16，详见 `docs\v102-wechat-ad-plan.md` 文末《v103 交付》）**
  - **关键机制**：内置备份**只在首启导入一次**（`files/firstrun_config_v1.marker` 门控，`App.kt:349-366`）→ **覆盖安装不会重导**。所以"打进 APK"只保**全新安装**开箱即用；已装过的手机必须另做导入。
  - ⚠️ **不要用"删 marker 重导"**：`importBackUpData` 会用备份里的 `store/*` **覆盖**当前设置（`BackupUtils.kt:91-100`），会把用户的 `guard_assoc_app_list.txt`（关联守护名单，含微信）和 store.json 一起冲掉。
  - **做法**：① `tmp\bake-firstrun.mjs` 往 `gkd-backup.zip` 加 `subscription/101.json`（严格 JSON）+ 在 `db.json` 的 `subsItems` 追加 `{id:101,enable:true,enableUpdate:false,order:2}`（脚本自带**字段白名单校验** —— GKD 是 `ignoreUnknownKeys=true`，字段名写错会被静默忽略）② 构建 **v103=fok0011** ③ `tmp\phone-import.ps1`：**先备份**手机 db/subscription/store.json → 推 `101.json` → 用 SQL 文件（`sqlite3 <db> < file`，避开引号地狱）`INSERT OR REPLACE INTO subs_item` 加 101 行 + `UPDATE subs_config SET enable=0 WHERE subs_id=1 AND app_id='com.tencent.mm' AND group_key=0`（**关上游同名组**）→ `am force-stop` + `am start` 重读。
  - **手机验收（V2238A/Android16）**：版本 103 ✓；`subs_item` 101 启用、`subs_id=101` 的 config 行 **0 条**（走默认值）；订阅页显示 `3. 微信广告精准规则(v102) · 1应用/1规则 · v1` ✓；**逐组精算**（按 `getGroupEnable` 公式）→ 梦念逍遥朋友圈组 **有效=false**、我的组 **有效=true** ✅；手机日志**无** `非法选择器`/`非法位置`（6 条选择器全部解析通过、组 valid）；无障碍仍启用；设置页出现 `坐标点击守卫` ✅。
  - **一个未完全解释的现象（如实记录，勿当验收指标）**：首页聚合计数 `1全局/13应用/30规则`，把 101 禁用后变 `31规则`（禁用反而 +1）；已排除订阅自动更新（文件与 mtime 未变），且发现 GKD 会后台自行写 `subs_config`（多出两行 type=3 全局行）→ 该计数会被 GKD 自身扰动，**不可作为验收判据**；可靠判据是**逐组精算 + 无非法选择器日志**。
  - **剩下只有"真人刷朋友圈"的行为验收**：看还点不点进去 / 关得快不快 / 日志有无 `ClickGuard reject`。

---

### 3.13 摇一摇跳转防护（v105 新增，v106 改为**按应用设定**，**v108 修失效 + 开屏时长可配**，`service/JumpGuard.kt`）★ 见 §11.9 的修复取证
- **一句话**：在**用户勾选的应用**里，开屏阶段跳到别的应用 → 判为摇一摇广告跳转 → **BACK 退回 + 需要时把原 App 拉回**。
- **★ 只对「跳转防护应用」名单生效，名单默认为空（= 谁都不拦）**，由用户在「设置 → 摇一摇跳转防护 → 跳转防护应用」里自行勾选（持久化 `store/jump_guard_app_list.txt`）。
- **为什么是这个方案**：老 `ShakeGuard` 要"有可点关闭按钮"才动手（一摇就跳的广告根本不给按钮）；v104 想改「获取设备方向」appop 从根上掐传感器，但真机取证证明 **vivo 上没这个 appop**（§10.5）。所以改成"事后拦截跳转"。
- **★★ v108 修的两个"失效"根因**（真机上"这功能没生效"就是它们）：
  1. **让位判据太粗**：旧代码 `if (lastGkdActionAt >= prevSince) return` = "本次前台期间 GKD 点过任何东西就让位"。开屏时 GKD 几乎必然点过（跳过/关弹窗/关更新提示），于是**整个应用会话都失效**。
     现在只在该模块正有一次"跳过类点击落点校验在飞"（`FakeSkipGuard.isVerifyingSkipClick()`，1.2s 窗口）时才让位 —— 分工精确、不重复按返回键。
  2. **开屏时长写死 1.8 秒**：真机日志（`gkd-20261002.log`，校园卡 App）三次广告跳转分别在开屏页出现后 **4.2s / 5.0s / 6.8s**，1.8 秒一次都追不上。现在**用户可配**（设置页「开屏时长」，1.5/2/3/5/8/10/15 秒，**默认 8 秒**）。
- **计时语义（v108 定稿）**：起点 = **当前页面出现的时刻**（同应用内换页会重新计时），但**只在"应用打开后 60 秒内的开屏阶段"这么算**（`ENTRY_LIMIT_MS`）—— 否则用户在名单应用里逛十分钟后换个页、8 秒内主动点跳转也会被拽回来（真机复现过一次）。超 60 秒后不再干预。
- **新增诊断**：源应用不在名单里但发生了窗口内跳转时，记一条节流日志 `JumpGuard not-guarded pkg=A -> B gap=..ms (若这就是摇一摇广告, 把它加进名单即可)`，用户可据此发现该加哪些应用（原来这功能是个黑盒）。
- 与 `FakeSkipGuard` 分工：**它管"GKD 点过之后落点不对"，本模块管"GKD 没在等落点却被带走"**。
- 详细设计与验收见 **§10.9–§10.10**（v105 初版）、**§10.13**（v106 按应用设定）、**§11.9**（v108 修复）。

### 3.14 关闭快应用（v107 新增，三层：识别 → 秒退 → 根治）★ 本次重点
- **要解决的问题**：流氓广告借**快应用**（厂商预装的"免安装小程序"运行环境，**原生渲染、不是 WebView**）把用户从开屏广告拉进快应用广告页，并在里面**自动下载 APK**。这类页面**没有可点的"跳过"**，所以"找按钮点跳过"的老思路完全无效 —— 能做的只有"别让它留在前台"和"把引擎本身关掉"。
- **三层结构**（代码都在 `service/QuickApp*.kt`）：
  | 层 | 文件 | 做什么 | 门槛 |
  |---|---|---|---|
  | ① 识别 | `service/QuickAppRegistry.kt` | **谁响应 `hap://app/...` 谁就是快应用引擎**（与包名/ROM 无关，最可靠）；加包名特征(quickapp/fastapp/hybrid)、已知厂商包名、用户手动补充；结果缓存进 `enginesFlow`，无障碍热路径只做集合判断 | 无 |
  | ② 秒退 | `service/QuickAppGuard.kt` | 判据极简：**前台从 A 变成"快应用引擎"** → toast + `BACK` 退回 A（没退回去就用启动意图拉回，复用 `GuardUtils`）。从桌面/系统界面进入的不拦（用户主动开快应用中心） | 无（默认开） |
  | ③ 根治 | `service/QuickAppController.kt` | 停用引擎包 `pm disable-user`、掐掉引擎的"安装应用"权限 `cmd appops set <pkg> REQUEST_INSTALL_PACKAGES deny`、结束进程 `am force-stop`；可一键恢复 | 需 Shizuku（或一键 ADB 命令） |
- **界面**：设置页「关闭快应用」开关 + 「快应用引擎 (N)」入口 → `ui/QuickAppEnginePage.kt`（引擎列表/状态/停用/恢复/禁止安装/结束进程/重新识别/**深度扫描**/复制 adb 命令）+ `ui/QuickAppEnginePickPage.kt`（手动补充：任一应用勾成引擎）。持久化 `store/store.json#quickAppGuard` 与 `store/quick_app_engine_list.txt`（自动记住 + 手动补充）。
- **为什么"停用引擎"只用包级命令**：实测（MuMu/Android15）`pm disable-user --user 0 <包>` 对**数据应用与系统应用都成功**；而**组件级** `pm disable <包>/<组件>` 在 Android 14+ 被系统直接拒绝：`SecurityException: Shell cannot change component state ... to 2`（与 §10.7 第 1 条同一个限制）。
- ⚠️ **ROM 差异（真机实测，2026-10-02）**：**vivo 额外禁止非 root 停用"系统应用"**（`Cannot disable ... no root permission`）→ 真机上 `com.vivo.hybrid` / `com.vivo.vhome` 这类引擎**停不掉**，只能"秒退 + 禁止引擎安装应用"，或在系统设置里手动停用。详见 §11.7 的能力矩阵。
- **不新增任何隐藏 API**：命令走 GKD 已有的 **Shizuku 用户服务** `UserServiceWrapper.execCommandForResult()`（`input tap`/`screencap` 同一通道，`shizuku/UserService.kt`），失败原因原样回显到 toast/日志。
- ★ **完整验收矩阵 / 踩坑 / 待办见 §11**。

---

## 4. 设备状态与 adb 速查

### 4.1 模拟器 MuMu 15
- 同 v98 文档 §4.1（MuMu root 后可直接读外部存储；重启会把 adb 包置 stopped 等大坑照旧）。
- 无障碍启用命令：`settings put secure enabled_accessibility_services 'li.songe.gkd/com.google.android.accessibility.selecttospeak.SelectToSpeakService'` + `settings put secure accessibility_enabled 1`。
- **v100 验证环境（2026-09-10）**：serial `127.0.0.1:16384`；已装 **v100（1.12.1-fok0008）** 与测试靶 `com.example.fakeadstest`。调试台默认**装好待用**（本地订阅=开、梦念逍遥=关、`accessibility_enabled=1`、防护开关=开、名单空）；要恢复正常使用跑 `gkd\tmp\fakeskip-rig.ps1 -Disarm`。
- 模拟器上做假跳过实验的**最省事路径**：直接跑 `E:\AI_workspace\gkd\tmp\fakeskip-rig.ps1`（装台）/ `-Disarm`（卸台）。手工步骤：
  1. 停 GKD → 把**严格 JSON**的规则写进 `/sdcard/Android/data/li.songe.gkd/files/subscription/-2.json`（即「本地订阅」）→ `chmod 666`；
  2. `sqlite3 .../db/gkd.db "update subs_item set enable=1 where id=-2"`（要用 `enable` 列，没有 `version` 列）；
  3. 起 GKD → `settings put secure accessibility_enabled 1` → `am start -S -n com.example.fakeadstest/.FakeSkipActivity`。
- ⚠️ **MuMu 上改 GKD 配置的四个大坑（本次全踩过，务必照做）**：
  1. **改 `store.json` 必须 `Get-Content -Raw -Encoding UTF8` 读**。PS 5.1 默认按 ANSI 读，会把中文（`"actionToast":"GKD特调版"` 等）搞坏；GKD 解析后**把整个 store 当成默认值重写**（现象：你改的字段几秒后回到默认、其它设置一起被重置、文件 md5 每次都变成同一个）。**自检**：改完立刻 `cat store.json` 看 `actionToast` 中文是否还完好、本地 md5 与设备 md5 是否一致。
  2. **`.ps1` 脚本必须存成 UTF-8 带 BOM**，否则 PS 5.1 按 ANSI 读，中文会把引号/语法搞坏（现象：`The string is missing the terminator`、`Missing closing }`）。
  3. **`am force-stop` 后无障碍服务会被系统立刻重新拉起**（只要它还在启用列表里）。进程带着**旧内存状态**回来，几秒后会把你的文件改动覆盖回去。正确姿势：先 `settings put secure accessibility_enabled 0` + 清空启用列表 → 再 force-stop → 等 4 秒 → 改文件 → 再启动。脚本里的 `Stop-GkdClean` 就是这么做的。
  4. **测靶子必须冷启动（`am start -S`）**。否则只有 `Warning: Activity not started, its current task has been brought to the front`，Activity 不重建、规则不重新匹配，测出来的是"假通过"（本次被这个坑骗过一轮）。
- 另注：`subs_item` 无 `version` 列；`resetMatch:'app'` 的匹配窗口按**应用变化**重置，同一 App 内换 Activity 不重置 `matchTime` 窗口（会看到规则 `超出匹配时间` 而不触发）。
- 模拟器被关掉后重新拉起：`& 'F:\mumu模拟器\MuMuPlayer\nx_main\MuMuManager.exe' control -v 0 launch`，然后 `adb connect 127.0.0.1:16384`。

### 4.2 用户真机 vivo V2238A（Android16, arm64, 包名 li.songe.gkd）
- serial `<真机序列号>`（USB 调试已授权）；2026-09-08 已装 **v99（1.12.1-fok0007）**，无障碍已开、守护运行中、微信已加入守护关联列表。
- **已授权（v99 全部）**：
  ```
  adb shell pm grant li.songe.gkd android.permission.WRITE_SECURE_SETTINGS
  adb shell appops set li.songe.gkd android:get_usage_stats allow   # v99 新增: 使用情况访问权限
  ```
- **vivo 安装新权限确认（新坑）**：v99 因新增 PACKAGE_USAGE_STATS，`adb install -r` 覆盖安装时手机弹出"新权限"确认框 → 必须点允许/继续，否则 `INSTALL_FAILED_ABORTED: User rejected permissions`。同权限的后续更新不会再弹。
- 关联列表文件：`/sdcard/Android/data/li.songe.gkd/files/store/guard_assoc_app_list.txt`（每行一个包名，如 `com.tencent.mm`）。
- 手机端必须：允许 GKD特调版 自启动 + 后台/忽略电池优化；**不要强行停止/一键清理**（vivo 会清无障碍并置 stopped，见 §3.3 边界）。
- 推送更新：`adb -s <真机序列号> install -r E:\AI_workspace\gkd\release\GKD特调版-1.12.1-fok0009.apk`
- **2026-09-10 真机进度**：
  - 用户 22:5x 开启 USB 调试 → 已成功把 **v100(fok0008)** 装到手机（覆盖安装无弹窗、配置保留、无障碍自动回连 ✓）。
  - 用**用户自己的订阅**（梦念逍遥 v77，本地规则一条没加）在真机上复现了假跳过：全局开屏组点中不可点的"跳过" → 跳系统设置 → v100 防护 `misclick`/`veto-add`/toast 全部正常，**但 BACK 没把人带回来**（这是 v101 的动因，见 §3.10）。
  - **v101(fok0009) 安装被 USB 掉线中断**（手机从 `adb devices` 消失，`adb install` 报空错误）→ 下次接上第一件事就是装 fok0009 并重跑三靶。
- **vivo 真机上的安装确认坑**：`adb install` 装**新 App**、以及**部分覆盖安装**时，vivo 会弹 `com.android.packageinstaller/.PackageInterceptActivity` 确认框；**屏幕熄着没人点就会一直挂着**（表现为 `adb install` 卡住直到超时，或报空错误）。对策：先 `input keyevent KEYCODE_WAKEUP` 唤醒并保持解锁，再发起安装，然后让用户点"继续安装"。
  - 判断是否卡在弹窗：`dumpsys activity activities | Select-String topResumedActivity` 看到 `PackageInterceptActivity` 就是它。
- **USB 掉线**：`adb devices` 里手机整条消失（不是 offline）通常是线/口松了或手机端授权掉了；重插后若显示 `unauthorized`，需要在手机上重新点"允许 USB 调试"。

---

## 5. 订阅 / 规则事实（防重复踩坑）
- 官方 gkd-kit/subscription 已归档停更；当前用 梦念逍遥 v77+（npmmirror 源，每天更新）。
- 网易云 6 规则组、全局开屏组等配置见 v98 文档（内置备份一次到位）。
- GKD 备份 zip 内部结构 & SubsConfig 常量见 v98 文档。

---

## 6. 常用诊断速查（对新对话直接可用）
```powershell
# GKD 运行态
adb shell dumpsys accessibility | Select-String 'Bound services|Enabled services|Crashed'
adb shell dumpsys window | Select-String 'mCurrentFocus'
# 自适应闹钟(应看到 *walarm*:li.songe.gkd.action.AUTO_A11Y_CHECK)
adb shell dumpsys alarm | Select-String 'li.songe.gkd'
# 旋转(0=竖 1=横 3=反向横)
adb shell dumpsys display | Select-String 'mCurrentOrientation'
# 使用情况访问权限(应 allow)
adb shell appops get li.songe.gkd android:get_usage_stats
# 关联列表
adb shell cat /sdcard/Android/data/li.songe.gkd/files/store/guard_assoc_app_list.txt
# ★ v100 假跳过防护: 看落点判定 / 回退 / 降级名单
adb shell "grep -E 'FakeSkipGuard' /sdcard/Android/data/li.songe.gkd/files/log/gkd-YYYYMMDD.log | tail -n 30"
adb shell "grep -o 'fakeSkipVetoApps[^,]*' /sdcard/Android/data/li.songe.gkd/files/store/store.json"
# ★ v100 谁点了靶子(注入触摸 = deviceId=-1)
adb shell logcat -d -s FakeSkipTest
# ★ 谁把落地页拉起来的(确认跨应用跳转来源)
adb shell logcat -d -v time | Select-String 'START u0.*settings'
# 规则是否触发 / 看当天日志尾（文件随日期换名）
adb shell tail -n 30 /sdcard/Android/data/li.songe.gkd/files/log/gkd-YYYYMMDD.log
# ★ v100 假跳过调试台(装/卸) + 三个靶的冷启动命令
& E:\AI_workspace\gkd\tmp\fakeskip-rig.ps1           # 装台
& E:\AI_workspace\gkd\tmp\fakeskip-rig.ps1 -Disarm   # 卸台
adb shell am start -S -n com.example.fakeadstest/.FakeSkipActivity                # 假跳过→落地页
adb shell am start -S -n com.example.fakeadstest/.FakeSkipActivity --ez toHome true  # 假跳过→桌面
adb shell am start -S -n com.example.fakeadstest/.RealSkipActivity                # 真跳过(防误伤)
# ★ v102 朋友圈广告仿真靶 + 调试台(装/卸/切探针)
& E:\AI_workspace\gkd\tmp\moments-rig.ps1 -Probe coord|coordSafe|chain|burst|fake   # 装台(切探针); -GuardOff 关掉新守卫做 A/B; -Disarm 卸台
& E:\AI_workspace\gkd\tmp\moments-run.ps1 -Case normal|hidden|degenerate|landing|crossApp|splash|fakeSkip|realSkip  # 清 logcat→冷启动→取证据
# ★ v103 手机侧(真机) 订阅导入 / 验收  —— adb 用 build-tools 那个, 手机 serial <真机序列号>
& E:\AI_workspace\gkd\tmp\phone-import.ps1            # 备份+推 101.json+SQL+重启 GKD
& E:\AI_workspace\gkd\tmp\phone-import.ps1 -VerifyOnly # 只看结果
node E:\AI_workspace\gkd\tmp\bake-firstrun.mjs        # 改完 wx-ad-gkd.json5 后重烘进内置备份(再构建)
node E:\AI_workspace\gkd\tmp\calc-enable.mjs          # 逐组精算启用状态(最可靠的验收判据)
# ⚠️ 手机首页"启用组数"聚合计数会被 GKD 自身扰动(实测禁用订阅反而 +1), 不要当验收指标
adb shell am start -S -n com.example.fakeadstest/.MomentsAdActivity --ez degenerate true  # 复现"空矩形→点进广告"
adb shell "grep -E 'ClickGuard|BurstGuard|FakeSkipGuard' /sdcard/Android/data/li.songe.gkd/files/log/gkd-YYYYMMDD.log | tail -30"
# ★ v104 一键 ADB 单文件 exe(推荐, 自带 adb; 双击=全自动)
& E:\AI_workspace\GKD特调版\release\GKD一键ADB配置.exe --check       # 只体检, 不改设置
& E:\AI_workspace\GKD特调版\release\GKD一键ADB配置.exe --upgrade     # 顺带覆盖安装同目录最新 fok APK(vivo 需人工点确认框)
& E:\AI_workspace\GKD特调版\release\GKD一键ADB配置.exe --prefer-embedded --check
& E:\AI_workspace\GKD特调版\release\GKD一键ADB配置.exe --reboot-test # 配完自动重启实测开机自启
# ★ 该 exe 的源码与编译命令见 §10.4(源码 E:\AI_workspace\adb-oneclick\Program.cs)
# ★ v104 传感器守卫结案取证(见 §10.5)
adb shell "dumpsys appops | grep -E '^\s*Op [A-Z_0-9]+:' | sort -u"   # 设备全部 op(本机 78 个, 无 orientation 类)
adb shell cmd appops get li.songe.gkd android:post_notification       # 正常: POST_NOTIFICATION: allow
adb shell cmd appops get li.songe.gkd android:get_device_orientation  # 本机: Error: Unknown operation string
# ⚠️ 手机订阅状态核验(注意 SQL 里的引号要能被远端 shell 正确剥掉; 复杂语句就写成 .sql 文件用 sqlite3 db < file)
adb shell "sqlite3 /sdcard/Android/data/li.songe.gkd/files/db/gkd.db 'select id,enable from subs_item;'"
# ⚠️ 跑 rig/run 脚本时**不要**用 `| Select-Object -First N` 截断管道 —— 会提前杀掉脚本(踩过: rig 没走到"打开无障碍"这步)
# ★ v107 关闭快应用: 识别结果 / 秒退日志 / 引擎名单 / 停用状态
adb shell "grep -a QuickApp /sdcard/Android/data/li.songe.gkd/files/log/gkd-YYYYMMDD.log | tail -n 20"
adb shell "cat /sdcard/Android/data/li.songe.gkd/files/store/quick_app_engine_list.txt"   # 识别到/记住的引擎包名
adb shell "grep -o 'quickAppGuard[^,]*' /sdcard/Android/data/li.songe.gkd/files/store/store.json"
adb shell "pm list packages | grep -iE 'hybrid|quickapp|fastapp'"                          # 本机有哪些快应用引擎
adb shell "cmd package query-activities --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d hap://app/com.test"  # 谁响应快应用链接
adb shell "pm disable-user --user 0 <引擎包>; cmd appops set <引擎包> REQUEST_INSTALL_PACKAGES deny"  # 根治(可 pm enable 恢复)
& E:\AI_workspace\GKD特调版\tmp\adbq.ps1 shell "getprop ro.build.version.release"           # ★ 统一 adb 入口(自动 start-server + 连接)
& E:\AI_workspace\GKD特调版\tmp\quickapp-test.ps1 -Case detect|block|fromlauncher|disable|enable|all
# 无 sqlite3 时可 pull db 用 E:\AI_workspace\build-tools\python312\python.exe 查
```
环境/文件策略注意：本交接文档写作时文件策略 danger-full-access、审批 never（后可能改动）。

---

## 7. 遗留事项 / 下一步（重要）
> ★ **2026-10-02 新增的待办见 §11.7**（v107「关闭快应用」的真机验证 / 一键 ADB exe 扩展 / 模拟器假引擎靶清理）。

0. **★ [当前重点] v103 微信广告优化 —— 代码/规则/内置备份/真机导入全部完成，只剩"真人刷朋友圈"的行为验收**（§3.12 / `docs\v102-wechat-ad-plan.md` 文末《施工结果》+《v103 交付》）：
   - ⚠️ **[2026-09-26 前提已变]** 真机上 `subs_item` 的 **101（微信精准规则）现在是禁用状态**、上游朋友圈组**开着**（§10.6，先于本次操作就存在）。
     ⇒ 在此之前，"只剩真人验收"这条**并不成立**：现在管朋友圈的是上游那条带盲坐标的规则。
     ⇒ **用户 2026-09-26 决定：不恢复 101，直接把该订阅删掉**（只靠上游规则 + `ClickGuard` 兜底）——已执行并核验（§10.11）。
     ⇒ 因此这一项的验收口径改为：**刷朋友圈看上游规则还点不点进广告 / 关得快不快 / 日志有无 `ClickGuard reject`**；
       若仍偶发"点进去"，下一步是收紧上游组或补一条本地精准组，而不是恢复 101。
   - **[已完成]** v103=fok0011 已装到真机（vivo 弹确认框需人工点"继续安装/授权"）；手机侧精准导入已完成（`subs_item` 101 启用、上游同名组已关、`subscription/101.json` 就位、GKD 已重读）；**逐组精算确认"朋友圈广告只剩我的组在管"**；设置页出现 `坐标点击守卫`。
   - **[只剩这一步，需要人拿手机]** 刷朋友圈遇到广告 → 看：① 还"点进去"吗 ② 关得快不快 ③ GKD 日志有无 `ClickGuard reject`（频繁 = 订阅里还有别的规则在打非法坐标）。
   - **[判定]** 若仍偶发"点进去" → 取广告落地页快照 → 决定做 T4（同包落地页回退）**或**直接删掉 `wx-ad-gkd.json5` 里 key=1 那条坐标兜底（安全方向是"关不掉"，不是"点进去"）；改完用 `bake-firstrun.mjs` 重烘 + 重建 + `phone-import.ps1` 重导。
   - **[工具已就绪]** 朋友圈仿真靶 `MomentsAdActivity` + `moments-rig.ps1`/`moments-run.ps1`（见 §6 速查）；**不要**用 `| Select-Object -First N` 截断这些脚本的输出（会提前杀掉脚本）。
   - **[环境注意]** 手机 adb 用 `E:\AI_workspace\build-tools\android-sdk\platform-tools\adb.exe`；**vivo 安装必须人工点确认框**（`INSTALL_FAILED_ABORTED: User rejected permissions` 就是这个）；该「超级守护」页面的授权按钮**不暴露给无障碍树**，脚本点不到，只能让人点。
1. **[下次重启手机后必验] vivo 重启不打开 App**：重启后等 1~3 分钟看 系统设置→无障碍 是否已自动开（BootReceiver +60s 重试 / 解锁触发 + 自适应闹钟）；若失败按 §3.3 边界排查（是否被置于 stopped、是否允许自启动）。
2. **[关联应用守护回归]** 真机上一键清理 GKD（会清无障碍）→ 直接打开微信 → 观察 GKD 日志出现 `GuardAssoc trigger pkg=com.tencent.mm`/ensure 行、系统无障碍自动恢复（前提：GKD 进程未被强行停止成 stopped；若被 stopped 则先手动开一次 GKD）。
3. **[横屏] 如再出现**：按 §3.9 记录 App/操作步骤/是否发生在自动恢复瞬间，先查 GKD 日志点击记录，再决定关订阅分组还是进一步改代码（v99 已消除"服务反复重启"这一诱因）。
4. 桌面小组件「GKD快捷开关」状态翻转回归（v98 能力，v99 未动相关逻辑）。
5. 用户手机上 Download 里的旧 APK 替换为 **v100（GKD特调版-1.12.1-fok0008.apk）**。
6. 若要继续加功能：改 `fork\src\gkd-1.12.1` → §2.2 增量构建 → 真机 `install -r`（新权限记得手机上点确认）→ 验证 → 本文档记一笔。
7. 备注：通知栏按钮依赖「控制→常驻通知」开启；覆盖安装不重导内置配置。

### v100/v101（假跳过防护）遗留 / 待办
1. **[真机未完成] 手机接上 USB 后立刻做**：
   `adb -s <真机序列号> install -r E:\AI_workspace\gkd\release\GKD特调版-1.12.1-fok0009.apk`（先唤醒屏幕；若弹 `PackageInterceptActivity` 让用户点"继续安装"），然后跑三靶并核对日志：
   - 假跳过靶 → 期望 `misclick` → (`back ok` 或 `back missed ... relaunch=true`) → 前台回到靶子；
   - 真跳过靶 → 期望只记 `ok`；
   - 再来一次假跳过靶 → 期望只记 `veto`（不再点）。
   - 手机上现在是 **v100(fok0008)**；测试靶 App 已装在手机上（`com.example.fakeadstest`，**调试完记得问用户是否卸载**），降级名单里可能残留该包的记录（可在设置页"已降级应用"里清空）。
2. **[真机观察]** 日常盯微信小程序开屏：出现"点跳过→被踢出小程序/打开落地页/回不到原页面"时看有没有 `FakeSkipGuard misclick`；`fakeSkipVetoApps` 会自动拉黑该 App。
   - **用户 2026-09-10 决定：梦念逍遥那几条微信组（"开屏广告-1/2"、"全屏广告-小程序部分通用广告"）暂不关**，先靠 §3.10 防护兜底。**注意**：真机上已实测证明梦念逍遥的**全局开屏组**也会点不可点的"跳过"，所以这条决定实际上把兜底压在防护上，v101 的回退/拉起就是它的保险。
3. **[可选改进] 同包内落地页不处理**：假跳过若在同一个 App 内打开 WebView 落地页，`appId` 不变 → 防护不介入（§3.10 局限 1）。要覆盖它需要额外判据（如落地页 Activity/URL 特征），当前故意不做以免误退。
4. **[可选改进] 桌面落点阈值**：目前"点了跳过被踢回桌面"要累计 2 次才降级（`LEFT_SYSTEM_LIMIT`）；真机若很常见可调成 1。
5. **[已基本排除] 那次 13:56 异常跳转**：2026-09-10 MuMu 专项调试已做 6 轮冷启动压力测试（`action:'none'`，每轮确认 Activity 重建）**全部 touch=0、零跳转，未复现**；正常路径每次跳转都能对上 `deviceId=-1` 的注入触摸。判断为宿主鼠标/MuMu 窗口焦点的杂散点击，**不是防护失效**。若日后复现，按 §3.10 局限 5 的方法取证。
6. **[规则层继续加]** `localskip-gkd.json5` 目前只覆盖学习通 + 测试靶；真机上再抓到假跳过 App，按 §3.11 的模板给它补一组（可点优先 + 明确 id 才坐标 + actionMaximum 1）。
7. **[已完成的调试能力]** 三个靶 + 一键装/卸脚本（§2.3 / §6）；下次要加"新形态广告靶"就照 `RealSkipActivity` 的写法加（布局 + `dispatchTouchEvent` 日志 + manifest exported）。
---

## 8. 2026-09-18：真机 adb 配置补齐 + v103 重装（本次会话记录）

- **★ 根因（此前"无障碍被清掉却不恢复"的真凶）**：手机侧 `WRITE_SECURE_SETTINGS` **未授予**。
  证据：`files/log/gkd-20260918.log` 18:35~19:49 反复出现 `A11yAutoGuard skip: no WRITE_SECURE_SETTINGS`（A11yAutoGuard.kt:146）
  ⇒ `ensureAuto()` 在权限检查处直接 return，**守护/自愈整条链路是死的**（不是 vivo 无法逾越）。
  修：`adb shell pm grant li.songe.gkd android.permission.WRITE_SECURE_SETTINGS`。
- **GKD 自己给出的权威 adb 命令**（App 内授权页就让你执行这句，比一键 bat 更全）：
  `adb shell sh /storage/emulated/0/Android/data/li.songe.gkd/files/sh/start.sh`
  = pm grant(WRITE_SECURE_SETTINGS / GET_APP_OPS_STATS / POST_NOTIFICATIONS) + 6 个 appops + `ExposeService --ei expose 1`。
  实测 `set -euo pipefail` 不会中途退出，一次跑通。（生成逻辑见 AuthA11yPage.kt:349 `gkdStartCommandText`；expose.sh 见 ExposeService.kt:73）
- **一键脚本升级 v2**（`release\adb-oneclick-setup.ps1`；原版备份 `adb-oneclick-setup.ps1.bak-20260918`）：
  ① 自动在「-Adb / ANDROID_HOME / 本仓库 build-tools / vivo 手机助手 / MuMu / LDPlayer / PATH」中挑**能看到设备**的 adb；
  ② 无设备时 kill-server+start-server 等待（-WaitDevice），并区分 unauthorized / offline / 真没插；
  ③ 补 `appops set ... android:get_usage_stats allow`；④ 优先执行 GKD 的 start.sh，拿不到再逐条等价执行；
  ⑤ 自检闹钟验证改为"必要时 force-stop 后重启一次再查"；⑥ 验证汇总表。
  **接受测试 = 直接双击 `一键ADB配置开机自启.bat`（不带参数）**：adb 自识别 → 设备识别 → 全项 OK。
  ⚠ 本文件必须保持 UTF-8 带 BOM，且**第一行必须以 # 开头**（本次踩到：某次写文件把行首 # 吃掉 →
  `param(` 不再是首语句 → PowerShell 报 "The assignment expression is not valid"（L32-35 指向 `''`））。
- **自检闹钟 AUTO_A11Y_CHECK 的两个坑**：① 闹钟只在 GKD 进程 onCreate 挂载，`monkey` 拉起旧进程**不会**重排
  （旧脚本报"未看到闹钟"是假阴性）；② manifest 未声明 SCHEDULE_EXACT_ALARM，Android 14+ 需靠电池白名单才放行
  精确闹钟（dump 里 `exactAllowReason=allow-listed`）。正确顺序：`dumpsys deviceidle whitelist +li.songe.gkd` → 再 force-stop + 打开一次。
  实测：19:59:49 触发后自动重排到 20:09:49 ⇒ 链路 B 自维持。
- **自愈验收手法（adb 可复现，推荐）**：`settings delete secure enabled_accessibility_services`
  → 列表变 null、Bound services 消失 → **约 3 秒**后 GKD 自己写回并重新绑定
  （日志 `A11yAutoGuard ensureEnabledLocked ... write-back` + `ensure result=true`）。
  ⚠ 两个**不能**当验收的反例：`settings put secure enabled_accessibility_services ''` 会被 settings CLI 拒（Bad arguments）；
  只改 `accessibility_enabled=0` 时服务仍处于 bound，`A11yService.isRunning==true` 会让 ensureAuto **静默 return**（不打日志），
  看起来像"守护失效"，其实是我方姿势不对。
- **手机现状（已覆盖本文件 §7 旧描述）**：本次把手机从 9/16 20:06 **清空数据重装**的 fok0009 升到
  **v103 = 1.12.1-fok0011（versionCode 103）**，并重跑 `tmp\phone-import.ps1`：
  `subs_item` 101 = enable/order=2、`subs_id=101` 的 subs_config 行 **0 条**、`subscription/101.json` 就位、
  上游 subs1 的 `com.tencent.mm` group_key=0 已 `enable=0`；db 与日志**无** `非法选择器/非法位置`。
  UI 复核：首页「已开启 2 条订阅」，订阅页显示 **「3. 微信广告精准规则(v102) · 1应用/1规则」**。
  逐组精算：subs1 的朋友圈组=关、subs101 的=开 ⇒ **朋友圈广告只剩我的组在管**。
- **vivo 装 APK 的坑（复现）**：`adb install -r` 弹 `com.android.packageinstaller/PackageInterceptActivity`
  （「超级守护已管控本次安装」），**其授权按钮不在无障碍树里**（本次 UI dump 里只有「取消安装」可点），
  不点就是 `INSTALL_FAILED_ABORTED: User rejected permissions`。**只能人工点**。
- **遗留**：① 仍需真人刷朋友圈做行为验收（§7.0）；② 关联守护名单里没有微信（现为 校园卡/得物/B站），需要就在 App 里加；
  ③ 会话内 `modlens` 视觉引擎不可用（读安装弹窗截图失败），需要看图时可跑 `npx @liustack/modlens doctor`。
---

## 9. 2026-09-18(深夜)：防摇一摇「传感器开屏撤销」方案 + MuMu 验证 + 构建踩坑

### 新功能 SensorOrientationGuard（`service/SensorOrientationGuard.kt`，v104=fok0012）
- **动机**：老 ShakeGuard 是"看到「摇一摇提示 + 可点关闭按钮」才点击"的事后补救；而摇一摇广告一摇就跳、根本不给你按钮 → 疑似无效。
- **方案（按用户要求）**：应用刚打开(前台包切换)的 2 秒窗口内，经 Shizuku(shell) 把该前台应用的「获取设备方向」appop 置为 ignore（读不到加速度计 → 摇一摇无法触发跳转），窗口结束/切走时恢复原 mode。
- **接线**：ShakeGuard.onAccessibilityEvent 里 `openPkg != pkg` 时调用 `SensorOrientationGuard.onAppOpen(pkg)`；新增 `SafeAppOpsService.setModeForPackage/checkMode`、`SafePackageManager.getUid`。
- **降级**：未连 Shizuku → 跳过(log `no-shizuku`)；ROM 没有该 op(原生 AOSP 就没有) → 跳过(log `no-orientation-op`)。
- **op 名解析**：先反射枚举 AppOpsManager 的 op 表(sOpToSwitch/sOpToString/sOpNames)——**注意 Android 14+ 已把 op 表重构为 AppOpInfo，这些字段可能没了**（MuMu/Android15 实测枚举为空）；兜底用候选名 `android:sensors / get_device_orientation / device_orientation / orientation / access_device_orientation / read_sensors`。**vivo 真机的确切 op 名待重连真机后确认**（在日志里看 `SensorGuard op candidates=...` 或手动 `appops set <pkg> <名> ignore` 探测）。

### MuMu 验证结果（用户要求"先在模拟器验证"）
- debug 构建装到 MuMu，无障碍绑定后冷启动 `com.example.fakeadstest/.SplashActivity`（含「摇一摇有惊喜」+「跳过广告」）。
- 日志确认：`SensorGuard op candidates=[...]` + `SensorGuard skip pkg=com.example.fakeadstest reason=no-orientation-op` —— **钩子正确触发、解析/降级/无崩溃**。
- 原生 AOSP(含 MuMu)没有「获取设备方向」op，所以**传感器拦截的实际效果只能在 vivo 真机 + 连 Shizuku 后验证**。这是平台限制，不是代码问题。
- 附带发现：SplashActivity 的「跳过广告」按钮在 3 秒倒计时后才出现，而 ShakeGuard 窗口只有 2 秒 → 老响应式路径在这个靶子上也点不到（印证"疑似无效"的另一个侧面）。

### 构建踩坑（重要，务必照做）
1. **工程路径不能有中文**：用户把 `gkd` 重命名成 `GKD特调版` 后，AGP 报 `non-ASCII characters`、`overridePathCheck=true` 只能过第一关，**aidl.exe 仍会 `MalformedInputException`**。→ 必须把源码拷到纯 ASCII 目录构建（本次用 `E:\AI_workspace\gkd-build`）。
2. **`gradle.properties` 的 `org.gradle.jvmargs` 必须写在第一行且文件无 BOM**：本次某次写文件把首字符吃掉(先吃 `#`、再吃 `o`)、或写入了 UTF-8 BOM → 第一行的 `org.gradle.jvmargs` 变成 `\uFEFForg...`/`rg.gradle...` → **守护进程只有 512MB 堆**，Kotlin/R8/D8 全 OOM。诊断法：加 `-I diag.init.gradle` 打印 `Runtime.getRuntime().maxMemory()`，正常应看到 7.1GB（-Xmx8g）。另需 `kotlin.daemon.jvmargs=-Xmx4g`（全量编译 Kotlin 会 OOM）。
3. 全量构建 R8 也很吃内存，debug 构建(无 R8/lint)最快，适合验证。

### 待办
- [x] **已结案(2026-09-26, 见 §10.5)**：vivo V2238A 上**不存在**「获取设备方向」appop —— 设备 78 个 op 里只有 `BODY_SENSORS`(心率)，
      6 个候选名 + 大写变体全部 `Unknown operation string`。**"连 Shizuku 后验证"这一步不必做了**：这条技术路线在该 ROM 上不成立。
- [ ] 老设备"关闭广告慢"：本次未做盲改（需真机 profiling）；传感器拦截本身能消掉摇一摇跳转延迟。备选通用方案：`cmd sensorservice set-uid-state <pkg> idle/active`（MuMu 实测命令存在，可把前台 app 标记为 idle 从而限制后台传感器，能否真拦加速度计待用一个真读传感器的靶子验证）。
      → 2026-09-26 补充：既然 §10.5 已判定"传感器权限"路线走不通，**防摇一摇的可行方向改为"事后拦截跳转"**（开屏 1~2s 内无 GKD 点击却跳到别的 App → BACK + 拉回原 App），可复用 `FakeSkipGuard` 的跨应用回退机制。
      → **[2026-09-26 已完成]** 该方向已按用户决定实施为 **v105 的 `JumpGuard`**（见 §10.9），并已真机四组用例验收通过（§10.10）。
      → **[遗留的小清理，下次顺手做]** `SensorOrientationGuard`（v104 的传感器模块）在本 ROM 上已被证实走不通，代码仍保留着：
        每次前台切换都会写一行 `SensorGuard skip pkg=... reason=no-orientation-op`（无害，但是日志噪音）。
        下次改代码时可**整个删掉该模块**及其在 `ShakeGuard`/`A11yService` 里的调用与设置项说明（删完需重新构建 + 装机）。
---

## 10. 2026-09-26：v104 上真机 + 一键 ADB 单文件 exe + §9 待办①结案

### 10.1 本次交付物
| 产物 | 路径 | 说明 |
|---|---|---|
| v104 签名 APK | `GKD特调版\release\GKD特调版-1.12.1-fok0012.apk` | 3,427,223 B；`versionCode=104` / `versionName=1.12.1-fok0012` / label `GKD特调版`；签名 CN=GKD Fork（`gkd-fork.jks`） |
| 一键 ADB 程序 | `GKD特调版\release\GKD一键ADB配置.exe` | 8,494,080 B **单文件 exe，内嵌 platform-tools adb**，双击即用（源码 `E:\AI_workspace\adb-oneclick\Program.cs`） |
| 真机备份 | `GKD特调版\phone-backup-20260926-181003\` | 装 v104 **之前**的 db/store/subscription/sh + 8 天日志（tar 打包后解出） |

### 10.2 构建 v104=fok0012（可复跑）
- **构建根 = 纯 ASCII 路径 `E:\AI_workspace\gkd-build`**（中文路径必踩 §9 坑1）。
- 前置自检（本次已验）：`gradle.properties` 首字节 `6F 72 67 2E`（= `org.`，**无 BOM**）且首行是 `org.gradle.jvmargs=-Xmx8g ...`；`local.properties` = `sdk.dir=E:\\AI_workspace\\build-tools\\android-sdk`；`gkd-build\gkd-fork.jks` 与 `GKD特调版\fork\gkd-fork.jks` **MD5 相同**（可任选其一）。
- 命令同 §2.2，只把工作目录换成 `gkd-build`、`-PGKD_STORE_FILE` 指向 `gkd-build\gkd-fork.jks`。
- 实测 **`BUILD SUCCESSFUL in 3m39s`**（87 tasks，18 executed）。
- ⚠️ **两个"看着像失败其实没失败"的坑**（本次都遇到）：
  1. 日志里有 `Caught exception: Couldn't open current thread, error = 5`、`Failed to compile with Kotlin daemon → Using fallback strategy: Compile without Kotlin daemon`：
     那是**沙箱不允许写 `C:\Users\<用户名>\AppData\Local\kotlin`** 导致的（同一原因还会有 `Unable to initialize metrics ... C:\Users\<用户名>\.android (拒绝访问)`），Gradle 自动降级为**非守护编译**，**不影响产物**。
  2. 用 `Tee-Object`/管道收 gradle 输出时，**作业退出码会因 stderr 被当成 NativeCommandError 而报 1**；
     判定成败**只看日志里的 `BUILD SUCCESSFUL`**，别看 `$LASTEXITCODE`。

### 10.3 真机安装（vivo V2238A / Android16 / serial `<真机序列号>`）
- **权限无变化**：v103 与 v104 都是 18 个 `uses-permission`（用 `aapt2 dump badging` 比对，新增/移除均为空）→ **不触发"新权限"确认框**。
- **但 vivo 仍然拦截安装**（`pm install` 与 `adb install` 都会被拦）：
  - 第一次尝试：`mScreenOn=false`（`dumpsys deviceidle | grep mScreenOn`）→ 直接返回 `INSTALL_FAILED_ABORTED: User rejected permissions`。
  - 正解：`adb shell input keyevent KEYCODE_WAKEUP` 唤醒 + 手机解锁 + 保持亮屏 → 人工在弹窗点「继续安装」→ 成功。
  - 确认框按钮**依旧不在无障碍树里，只能人工点**。
- 装后核验（全部通过）：`versionCode=104 / versionName=1.12.1-fok0012`、`lastUpdateTime=2026-09-26 18:11:00`；进程在跑；`Bound services` 含 GKD 且 `Crashed services:{}`；无障碍仍在启用列表；**当天日志 `Exception|FATAL` 计数 = 0**；`非法选择器|非法位置` = 0；内置备份**没有**重导（首启 marker 门控），用户配置保留。

### 10.4 ★ 一键 ADB 程序（新交付，取代原 ps1+bat 的"傻瓜版"）
- **形态**：单文件 exe，8.49 MB，**内嵌 `tools\platform-tools`（adb.exe + AdbWinApi.dll + AdbWinUsbApi.dll）**，运行时解压。
- **技术选型**：用 **.NET SDK 8 里的 Roslyn 编译器（`csc.dll`）编译到 .NET Framework 4.x**：
  Win10/11 自带 .NET Framework → **目标机器零安装**；不受 PowerShell 执行策略限制；还能用现代 C# 语法。
  （本机 `C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe` 只支持 C# 5，所以走 SDK 里的 Roslyn。
   注意 `dotnet publish` 的 self-contained **不可用**：`E:\AI_workspace\.dotnet\packs` 只有 Host/Ref 包，缺 `Microsoft.NETCore.App.Runtime.win-x64`，离线拉不到。）
- **编译命令（可复跑）**：
  ```powershell
  & 'E:\AI_workspace\.dotnet\dotnet.exe' exec `
    'E:\AI_workspace\.dotnet\sdk\8.0.425\Roslyn\bincore\csc.dll' `
    /nologo /nostdlib+ /target:exe /optimize+ /platform:anycpu /langversion:latest `
    "/out:E:\AI_workspace\adb-oneclick\GKD一键ADB配置.exe" `
    "/r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\mscorlib.dll" `
    "/r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.dll" `
    "/r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Core.dll" `
    "/resource:E:\AI_workspace\GKD特调版源码\tools\platform-tools\adb.exe,GKDOneClick.Embedded.adb.exe" `
    "/resource:E:\AI_workspace\GKD特调版源码\tools\platform-tools\AdbWinApi.dll,GKDOneClick.Embedded.AdbWinApi.dll" `
    "/resource:E:\AI_workspace\GKD特调版源码\tools\platform-tools\AdbWinUsbApi.dll,GKDOneClick.Embedded.AdbWinUsbApi.dll" `
    'E:\AI_workspace\adb-oneclick\Program.cs'
  ```
- **功能**：找 adb(自带兜底) → 等设备(区分 unauthorized/offline/没插) → 安装(可选) → 授权 → 无障碍基线(只追加) → 电池/后台白名单 → 自检闹钟 → **验证表格** → 人工清单 → 可选重启实测；全程写日志 `adb-oneclick-<时间戳>.log` 到 exe 同目录。
- **参数**：`--serial --connect --wait --upgrade --apk --check --reboot-test --skip-launch --no-restart --adb --prefer-embedded --no-pause`；**不带参数双击 = 全自动配置**。
- **实测**：`--check` 真机全绿；`--prefer-embedded` 用自带 adb 全绿（证明内嵌可用）；`--upgrade` 成功把真机 v103→**v104**；全量模式 **无 FAIL、无注意**。
- **设计要点（都是踩过才加的）**：
  1. adb 定位**以"能不能列出设备"来挑**，自带 adb 放最后兜底，`--prefer-embedded` 可强制优先；避免选到版本不匹配的 adb 误报"没设备"。
  2. 自带 adb 解压位置依次试 `%LOCALAPPDATA%\GKDOneClick` → `%TEMP%` → exe 同级 `.adb\`（受限/只读环境也能用）。
  3. **安装类命令超时给到 300s**（默认 120s）：vivo 确认框要等人点，超时太短会把"人还在点"误判成失败。
  4. stderr 用 `ReadToEndAsync` 异步收，避免两个管道互等死锁。
  5. .NET Framework **没有** `ProcessStartInfo.ArgumentList`（那是 .NET Core API）→ 必须自己拼 `Arguments` 并做 Windows 引号/反斜杠转义。

### 10.5 ★★ §9 待办①结案：「获取设备方向」appop 在 vivo 上**根本不存在**
- v104 真机日志（装完 v104 后切前台应用即可复现）：
  ```
  SensorGuard op candidates=[android:sensors, android:get_device_orientation, android:device_orientation,
                             android:orientation, android:access_device_orientation, android:read_sensors]
  SensorGuard skip pkg=com.android.settings reason=no-orientation-op
  ```
  候选表**恰好等于代码里写死的兜底候选** ⇒ 反射枚举 op 表拿到的是**空** ⇒ 与 §9 的预判一致（Android 14+ op 表重构成 AppOpInfo）。
- **设备侧直接取证（这才是结论，不用再试 Shizuku）**：
  | 探针 | 结果 |
  |---|---|
  | `cmd appops get li.songe.gkd android:post_notification` | `POST_NOTIFICATION: allow` |
  | `cmd appops get li.songe.gkd android:body_sensors` | `Uid mode: BODY_SENSORS: ignore` |
  | 6 个候选名 + `GET_DEVICE_ORIENTATION/DEVICE_ORIENTATION/SENSORS/ACCELEROMETER/GYROSCOPE/MOTION/HIGH_SAMPLING_RATE_SENSORS/SET_ORIENTATION/OBSERVE_SENSOR_PRIVACY` | **全部** `Error: Unknown operation string` |
  | `dumpsys appops` 枚举设备全部 op | 共 **78 个**；含 `SENSOR/ORIENT/DIRECTION/GYRO/ACCEL/MOTION` 的**只有 `BODY_SENSORS`（心率）** |
  | `pm list permissions -f -g` | 无「获取设备方向」权限（只有 `BODY_SENSORS`、`SET_ORIENTATION`、`OBSERVE_SENSOR_PRIVACY`、`MANAGE_SENSOR_PRIVACY` 等） |
  - 前两行证明**appops 查询链路本身正常**（不是名字写法/前缀问题），所以"op 名不对"这个假设被排除。
- **结论**：vivo 的「获取设备方向」**不是标准 AppOps op**（应该是 vivo 自家权限框架/传感器隐私实现），
  **Shizuku 改 appop 这条路在这台机器上走不通**。v104 里该模块是**惰性的**：每次前台切换只写一行 skip 日志，
  **无副作用、无崩溃、无性能影响**（当天日志 0 异常）。
- **后续方向（若还要防摇一摇）**：改成"**事后拦截跳转**"——开屏 1~2s 内**没有任何 GKD 点击**却跳到浏览器/应用市场/落地页，
  就判为摇一摇跳转 → `BACK` + 拉回原 App（可复用 `FakeSkipGuard` 的跨应用回退/`relaunchApp`）。
  这条路**可在真机验证**，不像 appop 那样只能靠厂商 ROM 特性。

### 10.6 ⚠️ 发现：手机上「微信广告精准规则」订阅(101)处于**禁用**状态（先于本次操作就存在）
- 证据来自**安装 v104 之前的备份** `phone-backup-20260926-181003\db\gkd.db`（python sqlite3 只读读取）：
  ```
  subs_item      = (-2,0) (1,1) (101,0)          ← 101 enable=0（禁用）
  subs_config    = (1,'com.tencent.mm',0,1,2)    ← 上游梦念逍遥朋友圈组 group_key=0 enable=1（开着）
  subscription\  = -2.json, 1.json（均 2026-09-18 20:58）；**没有 101.json**
  ```
- 也就是说 **§8 记录的"101 开、上游同名组关"已不成立**：现在管朋友圈广告的是**上游那条带盲坐标的规则**，
  只有 v102 起的 `ClickGuard`（空矩形/不可见节点拒点）在兜底 —— 正是 §3.12 里"点进广告"的那条路径。
- 订阅被禁用后 GKD 不再保留其缓存，所以 `101.json` 消失；这与"用户在 App 里把该订阅关掉"的表现一致。
- **恢复办法**：重跑 `tmp\phone-import.ps1`（先备份 → 推 `101.json` → SQL 置 101 启用 + 关上游同名组 → 重启 GKD 重读），
  然后用 `tmp\calc-enable.mjs` 逐组精算复核（§3.12 提醒：首页聚合计数不可当验收判据）。
- **本次未擅自恢复**：也可能是使用者自己在 App 里关的，需要确认后再动（详见 §7 待办）。

### 10.7 本次新增踩坑（都写进 exe 或流程了）
1. **`pm enable <组件>` 在 Android 16 上必抛** `SecurityException: Shell cannot change component state ... to 1`
   （shell 无权改组件状态）。正确姿势：先读 `dumpsys package` 的 `disabledComponents:` 段，**本来就启用就别去 enable**，
   否则会把"一切正常"误报成警告（已在 exe 修掉：现在输出"组件默认启用"）。
2. **`adb pull <远端目录> <本地目录>` 的语义是把远端目录塞进本地目录之下**（生成 `<本地>\<目录名>\...`）。
   把本地目标写成同名路径会多一层嵌套，甚至报 `cannot create ... Not a directory`。
   → **备份改用 `tar czf /data/local/tmp/x.tgz <目录...>` 再 `adb pull` 单文件**，并本地解包，最稳。
3. **vivo 安装确认框 + 熄屏 = 直接 `User rejected permissions`**：先 `input keyevent KEYCODE_WAKEUP` 唤醒解锁，再发起安装。
4. **adb 把进度写到 stderr**，PowerShell 会当 `NativeCommandError` 抛红字 —— 那是**噪音不是失败**（`1 file pulled` 就是成功）。
5. `E:\AI_workspace\gkd-build\java_pid*.hprof` 有 **5 个约 800MB（合计 ~4GB）** 的 OOM 堆转储
   （9/26 上午构建 OOM 时由 `-XX:+HeapDumpOnOutOfMemoryError` 留下；后来修正 `gradle.properties` 堆设置 + 降级编译后已不再产生）。
   **确认无用可删以回收 4GB 空间**（本次未删，等确认）。

### 10.8 手机当前状态（2026-09-26 18:1x，已覆盖 §8 的旧描述）
- **已装 v104 = 1.12.1-fok0012（versionCode 104）**，接 USB 可直连（serial `<真机序列号>`）。
- `WRITE_SECURE_SETTINGS` 已授予、`GET_USAGE_STATS: allow`、无障碍在启用列表且已绑定、电池白名单已加入、待机分组 5（active）、`AUTO_A11Y_CHECK` 自检闹钟已挂载。
- 订阅：`-2`（本地）禁用、`1`（梦念逍遥）启用、**`101`（微信精准规则）禁用**；上游微信朋友圈组**开着**（见 §10.6）。
- 当天日志无异常、无非法选择器。

---

### 10.9 ★ 新增「摇一摇跳转防护」JumpGuard（v105=fok0013，取代走不通的传感器路线）
- **文件与接线**（改的都是各 1 行，便于日后回退）:
  | 文件 | 改动 |
  |---|---|
  | `service/JumpGuard.kt` | ★ 新模块（本功能全部逻辑） |
  | `service/A11yService.kt` | `onAccessibilityEvent` 里加 `JumpGuard.onAccessibilityEvent(event)`，**放在 ShakeGuard 之前** → 不依赖「防摇一摇」开关 |
  | `a11y/A11yRuleEngine.kt` | `addActionLog(...)` 之后加 `if (actionResult.action.startsWith("click")) JumpGuard.onGkdAction()`（与 FakeSkipGuard 钩子并排） |
  | `service/ShakeGuard.kt` | `if (shakeFound) JumpGuard.noteShakeEvidence()` → 给 JumpGuard 留"摇一摇证据" |
  | `store/SettingsStore.kt` | 新增 `jumpGuard: Boolean = true`、`jumpGuardSkipApps: String = ""` |
  | `ui/home/SettingsPage.kt` | 「假跳过防护」下方新增「摇一摇跳转防护」开关 + 「跳转不拦截 (N)」一行(点击清空) |
  | `app/build.gradle.kts` | gitInfo=`fok0013`、versionCode=`105` |
- **判据（三条同时成立才动作）**：① 源应用 A 在前台停留 `<= 1800ms`；② 前台切到**别的应用 B**（B 不是桌面/系统界面/GKD 自己）；③ 该窗口内 **GKD 一次点击都没做过**（`lastGkdActionAt < A 的起始时间`）。
  → ③ 保证了与 `FakeSkipGuard` 的**职责不重叠**：GKD 点过的跳转归它管，GKD 没动手却被带走的归本模块管。
- **动作**：日志 + toast + `A11yRuleEngine.performActionBack()`；BACK 之后**新读一次前台包名**（不用缓存流），若还没回到 A 就用启动意图把 A 拉回（同 FakeSkipGuard 的 `relaunchApp`）。
- **防误伤**（本模块最大风险点是"正常 App 也会打开后立刻跳浏览器"，例如 OAuth/支付/分享）：
  - 命中**摇一摇证据** → 高置信，直接拦、**不计数**；
  - 无证据的"快速跨应用跳转" → 拦但计数，同一源应用累计 **2 次**自动写入 `jumpGuardSkipApps` 放行并 toast 告知（**宁可放过，也不把用户的正常流程反复打断**）；设置页可一键清空（`JumpGuard.clearSkipApps()`）；
  - 同一对 (A→B) 有 **5 秒冷却**，避免来回打架。
- ★★ **必须记住的坑（代码 review 抓到、真机验证有效）**：我们自己按 BACK 把用户退回 A 时，那次 **B→A 切换本身长得就像一次"快速跳转"** → 会再次触发 → 再按 BACK → **一路退到桌面（死循环）**。
  解法：处置前登记 `suppressReturnPkg/suppressReturnUntil`，随后那次 B→A 直接放过。**没有这个抑制，本功能会把用户从 App 里一路弹出去。**

### 10.10 JumpGuard 真机验收（2026-09-26，vivo V2238A / Android16）★ 四组用例全通过
- **手法**：用 adb 依次启动两个应用来"制造"跨应用跳转（`am start -S -n A` → 等 gap → `am start -S -n B`），
  A=`com.android.notes/.Notes`、B=`com.android.settings/.Settings`、桌面=`com.bbk.launcher2`（按设计应被排除）。
  脚本：`tmp\jump-guard-test.ps1 -Case fast|slow|fast2|fast3`（纯 ASCII 内容，PS 5.1 可直接跑，无需 BOM）。

  | 用例 | 输入 | 期望 | 实测 |
  |---|---|---|---|
  | `fast` | A →0.99s→ B | 拦截 + 退回 A | `JumpGuard fast-jump pkg=com.android.notes -> com.android.settings gap=988ms reason=no-gkd-action strike=1, send BACK` → `JumpGuard back ok sent=true now=com.android.notes` ✓ |
  | `slow` | A →3.0s→ B | 不动作 | 无新增 JumpGuard 日志 ✓ |
  | `fast2` | 再来一次快跳 | strike=2 → 自动放行 + 退回 | `strike=2, send BACK` + `JumpGuard skip-add pkg=com.android.notes reason=strike-2 total=1` + `store.json` 落盘 `jumpGuardSkipApps":"com.android.notes"` ✓ |
  | `fast3` | 第三次快跳 | 已放行 → 不动作 | 无新增日志 ✓ |

- **附带验证**：退回之后**没有出现第二次动作** ⇒ §10.9 的抑制逻辑确实生效（否则会连按返回一路退到桌面）；
  `jumpGuard":"true"` 默认值随 `store.json` 正常落盘（覆盖安装后旧 store 缺该字段也能用默认值跑起来）。
- **验收后已清理测试残留**：把 `jumpGuardSkipApps` 置空（按 §4.1 的安全顺序改 `store.json`：先关无障碍+清列表 → force-stop → 改文件 → 重启 → 用一键 exe 恢复无障碍），手机现为干净状态。

### 10.11 手机最终状态（2026-09-26 18:3x，**覆盖 §10.3 与 §10.8**）
- **v105 = 1.12.1-fok0013（versionCode 105）**；进程在跑、无障碍已绑定、`a11y_enabled=1`、当天日志 **0 异常**、无非法选择器。
- `store.json`：`jumpGuard":"true"`、`jumpGuardSkipApps":""`。
- 订阅：**`101`（微信精准规则）已按用户决定删除**（`subs_item` = `-2|0`、`1|1`）；微信朋友圈**只由上游梦念逍遥组 + `ClickGuard` 兜底**（不再有本地精准组）。
- 备份：`phone-backup-20260926-181003`（装 v104 前）、`phone-backup-20260926-181607-pre101rm`（删 101 前）。

### 10.12 两处流程踩坑（本次）
1. **vivo 安装确认框会"秒拒"**：`--upgrade` 第一次发起后**几秒内**就返回 `INSTALL_FAILED_ABORTED: User rejected permissions`，人还没看清弹窗就结束了。
   有效姿势：**先 `input keyevent KEYCODE_WAKEUP` → 确认 `isKeyguardShowing=false` → 再发起安装 → 立刻盯着手机点「继续安装」**
   （本次这样等约 12 秒后 `Success`）。失败时一键 exe 已自动把 APK 推到 `/sdcard/Download/`，可用文件管理器手动装。
   （补充：`mCurrentFocus` 在整个安装过程中一直是 `null`，**不能**用它判断确认框是否出现。）
2. **PowerShell 里给 `adb shell` 传带 `{}`、`:` 的 grep 模式会被 PS 解析器吃掉**（报 `Missing expression after ','` / `The string is missing the terminator`）。
   → 复杂 SQL/模式一律写成文件再执行（`sqlite3 db < file.sql`），或只用 `grep -o 'jumpGuard[^,]*'` 这种最简形式。
3. **`tmp\` 下的老脚本还指着已改名的旧路径**：`phone-import.ps1` / `fakeskip-rig.ps1` / `moments-*.ps1` 里写的是
   `E:\AI_workspace\gkd\tmp` 与 `E:\AI_workspace\gkd\release\...`，**而项目目录早已改名成 `GKD特调版`** →
   直接跑会找不到文件。下次要用先批量替换这两个前缀（本次未改，只是没用它们）。
4. `adb pull <远端目录> <本地目录>` 会把远端目录**塞进本地目录之下**（见 §10.7 第 2 条）；本次备份统一走 `tar czf` 单文件，最稳。

---

### 10.13 ★★ 摇一摇跳转防护改为「按应用设定」（v106=fok0014，用户需求）
- **需求原话**：改为可以设定应用；在设定应用内开屏跳转后再返回原页面；**设定应用默认为空，用户自行添加**。
- **改动清单**：

  | 文件 | 改动 |
  |---|---|
  | `store/StoreExt.kt` | 新增 `jumpGuardAppListFlow`（文件 `store/jump_guard_app_list.txt`，换行分隔包名，**默认空**），并加进 `initStore()` 预加载 |
  | `ui/JumpGuardAppListPage.kt` + `JumpGuardAppListVm.kt` | **新增**「跳转防护应用」选择页（照 `GuardAssocAppListPage` 既有模式：搜索栏 + `AppCheckBoxCard` 勾选 + 底部计数） |
  | `MainActivity.kt` | 注册 `entry<JumpGuardAppListRoute> { JumpGuardAppListPage() }`（**注意：Page 与 Route 两个 import 都要加** —— 本次漏了 Page 的 import，编译报 `Unresolved reference 'JumpGuardAppListPage'`） |
  | `ui/home/SettingsPage.kt` | 开关下方由「跳转不拦截 (N)」改为「跳转防护应用 (N)」入口；为空时文案提示"默认为空 = 不做任何拦截" |
  | `service/JumpGuard.kt` | 判据新增"**源应用必须在名单里**"（日志 `reason=guarded-app`）；**删除** v105 的 strike 计数与"误拦 2 次自动放行"（正向设定取代了反向名单） |
  | `store/SettingsStore.kt` | **删除** `jumpGuardSkipApps` 字段（`ignoreUnknownKeys=true` → 旧 store.json 里的残留键被安全忽略；真机已确认 GKD 重写后该键消失，其余 55 个键完好、`actionToast` 中文无损） |
  | `app/build.gradle.kts` | `fok0014` / versionCode `106` |
- **实际效果**：名单为空 → 本功能**完全不介入任何应用**（出厂态）；只有在名单里的应用里才生效。用户若发现某个 App 的正常跳转（登录/支付/分享）被拦，取消勾选即可。
- **★ 真机三阶段验收（2026-09-26，vivo V2238A / Android16，v106）**：

  | 阶段 | 条件 | 期望 | 实测 |
  |---|---|---|---|
  | A | 名单**为空**（默认态） | 不拦 | 快跳 `notes → settings`：**无** JumpGuard 日志 ✓ |
  | B | 名单 = `com.android.notes` | 拦 + 退回原页面 | `JumpGuard jump pkg=com.android.notes -> com.android.settings gap=1263ms shake=false reason=guarded-app, send BACK` → `JumpGuard back ok sent=true now=com.android.notes` ✓ |
  | C | 名单同 B，**未设定的**日历做同样快跳 | 不拦 | 日志确认 `com.bbk.calendar/.MainActivity -> com.android.settings` **确实发生**（`updateTopActivity` 有记录），但**无** JumpGuard 日志 ✓ |

  验收后已把名单**清空回默认**（删除 `jump_guard_app_list.txt`），手机处于"功能就绪但不干预任何应用"的状态；`jumpGuard` 开关保持 `true`。- **★★ 踩坑 1：手写这个列表文件不能带 UTF-8 BOM。** `AppListString.decode` 按行切分后用 `isValidAppId()` 过滤，
  **BOM 会让第一行包名变成非法 id 而被静默丢弃** —— 现象是"文件里明明写着 `com.android.notes`，功能却完全不生效"。
  本次第一轮测试就栽在这（`od -c` 看到行首 `357 273 277`）。GKD 自己写文件不带 BOM（`writeStoreText` 用 `Charsets.UTF_8`），
  所以**用 App 里的选择页勾选永远不会出问题**；脚本/编辑器手改必须存"UTF-8 无 BOM"：
  `[System.IO.File]::WriteAllText($p,$s,(New-Object System.Text.UTF8Encoding($false)))`。脚本 `tmp\jump-guard-list.ps1` 已按此修正。
- **★ 踩坑 2：排查"本功能不生效"先看两处**：`grep -o 'jumpGuard[^,]*' store.json`（总开关）与 `cat store/jump_guard_app_list.txt`（名单）。
  本次第一轮**两个原因同时存在**：开关是 `false`（且名单因 BOM 解码为空），所以看起来"代码没生效"，其实两条前置条件都没满足。
- **★ 界面路径真机实测（同一天，v106）**：设置页滚动到「摇一摇跳转防护」下方确实出现 **「跳转防护应用 (0)」**（0 = 默认空）；
  点进去是选择页（标题「跳转防护应用」+ 说明文案 + 应用列表 + 搜索按钮；列表项的名字在 `content-desc="应用：XXX"` 里，不在 `text=` 里，做 UI 自动化时要注意）。
  **勾选「原子笔记」→ 立刻生成 `store/jump_guard_app_list.txt`（内容 `com.android.notes`，`od -c` 确认无 BOM）**；
  **再点一次取消勾选 → 文件变成 0 字节**（即空集合 = 恢复默认不拦截）。⇒ 用户侧"加/减设定"的完整链路可用。
- **配套工具**（都在 `tmp\`，纯 ASCII、PS 5.1 可直接跑）：
  - `jump-guard-list.ps1 -ShowOnly` / `-Apps com.a.b,com.c.d` / 不带参数=清空（内部走"关无障碍+清列表 → force-stop → 写文件 → 重启 → 用一键 exe 恢复无障碍"的安全顺序）
  - `jump-guard-test.ps1 -Case fast|slow [-PkgA .. -PkgB ..]`（制造跨应用跳转并抓 JumpGuard 日志）

---

## 11. 2026-10-02：v107=fok0015「关闭快应用」三层防护（本次交付）

### 11.1 交付物
| 产物 | 路径 | 说明 |
|---|---|---|
| v107 签名 APK | `GKD特调版\release\GKD特调版-1.12.2-fok0015.apk` | 3,334,863 B；`versionCode=108` / `versionName=1.12.2-fok0015` / label `GKD特调版`；**fork 密钥**签名（CN=GKD Fork，`gkd-fork.jks`）——与手机上的 fok0014 同钥，可覆盖安装 |
| 假快应用引擎靶 | `跳过广告助手\testapp\engine\build\outputs\apk\debug\engine-debug.apk` | 新模块，包名 `com.example.fakequickapp`，响应 `hap://` |
| 测试脚本 | `tmp\quickapp-test.ps1`、`tmp\adbq.ps1` | 见 §11.4 |
| 模拟器配置备份 | `tmp\gkd-mumu-backup.tgz` | 换签名重装前从 MuMu 备份的 `files/`（含 store/db/subscription/log） |

### 11.2 设计要点（为什么这么做）
- 需求原话："给我的软件增加一个关闭快应用的功能。具体怎么做你说了算"。
- **先取证再动手**（这次四条取证都改变了方案）：
  1. `Shizuku.newProcess` **在 13.1.5 里不存在**（javap 确认）→ 特权动作不能靠"跑 shell 进程"，但 **GKD 已有 `UserServiceWrapper.execCommandForResult()`**（Shizuku 用户服务，uid=shell）→ **零新增隐藏 API**；
  2. 用户服务进程实测能执行 `pm disable-user --user 0 <包>`（数据应用与系统应用都成功）；
  3. **组件级** `pm disable <包>/<组件>` 在 Android 14+ 必被拒（`Shell cannot change component state ... to 2`）→ 方案只用**包级**；
  4. `cmd appops set <pkg> REQUEST_INSTALL_PACKAGES deny` 名字有效（假名会报 `Unknown operation string`）→ 用它掐掉引擎"安装应用"的能力。
- **判据为什么可以这么简单**：正常用户不会"跳进"快应用引擎，所以"前台从 A 变成引擎"本身就是强特征 —— 不需要 JumpGuard 的 1.8 秒窗口，也不需要"GKD 没点过"；即便 GKD 规则点到了假跳过按钮而把快应用拉起来，也应该退回去。**唯一需要排除的是"从桌面/系统界面进入"**（用户主动开快应用中心）。

### 11.3 改动清单（`gkd-build\app\src\main\kotlin\li\songe\gkd\`）
| 文件 | 改动 |
|---|---|
| `service/QuickAppRegistry.kt` | ★ 新增：引擎识别（`hap://` 探测 + 包名特征/已知厂商包名 + 用户名单 + 记住），`enginesFlow`/`isEngine()`、`deepScan()` |
| `service/QuickAppGuard.kt` | ★ 新增：秒退（判据 + `BACK` + 拉回原应用 + 冷却），`blockCountFlow`/`lastBlockFlow` |
| `service/QuickAppController.kt` | ★ 新增：特权动作（停用/恢复/禁装/允装/结束进程）+ `adbCommands()` 兜底文案 |
| `ui/QuickAppEnginePage.kt` / `ui/QuickAppEnginePickPage.kt` / `ui/QuickAppEnginePickVm.kt` | ★ 新增：引擎管理页（含 A/B 用不到的"重新识别/深度扫描/复制 adb 命令"）+ 手动补充页 |
| `service/A11yService.kt` | `onAccessibilityEvent` 里加 `QuickAppGuard.onAccessibilityEvent(event)`（与 JumpGuard 并排，各 1 行） |
| `App.kt` | 启动时 `QuickAppRegistry.refresh()` |
| `store/SettingsStore.kt` | 新增 `quickAppGuard: Boolean = true` |
| `store/StoreExt.kt` | 新增 `quickAppEngineListFlow`（`store/quick_app_engine_list.txt`）+ `initStore()` 预加载 |
| `ui/home/SettingsPage.kt` | 「关闭快应用」开关 + 「快应用引擎 (N)」入口（放在「摇一摇跳转防护」下方） |
| `MainActivity.kt` | 注册 `QuickAppEngineRoute` / `QuickAppEnginePickRoute`（Page 与 Route 两个 import 都要加） |
| `app/build.gradle.kts` | `fok0015` / `versionCode 108` |

### 11.4 ★ MuMu 验收矩阵（2026-10-02，**全绿**；serial 见下方环境注意）
| 用例 | 手法 | 期望 | 实测 |
|---|---|---|---|
| 识别 | 冷启动 GKD | 日志列出引擎 | `QuickApp engines=1 [com.example.fakequickapp(响应hap链接)]` ✓ |
| **秒退** | 宿主(`fakeadstest/.SplashActivity`) → `am start -a VIEW -d hap://app/com.example.fakequickapp` | 引擎起来后立刻退回宿主 | `QuickApp block pkg=com.example.fakeadstest -> com.example.fakequickapp engine=快应用引擎(测试靶), send BACK` → GKD 转场日志 `...QuickAppAdActivity -> ...SplashActivity` → `QuickApp back ok sent=true now=com.example.fakeadstest` ✓ 焦点时间序列 4 次采样**全程在宿主**；引擎靶触摸日志 **0 条** ✓ |
| **A/B 对照** | 同场景，`store.json` 里 `quickAppGuard=false` | 不拦 | 焦点时间序列 4 次采样**全程在引擎**、**零** QuickAppGuard 日志 ✓ |
| 桌面来源不拦 | 回桌面 → `hap://` | 不动作 | 引擎留在前台、无 QuickAppGuard 日志 ✓ |
| **停用引擎(根治)** | `pm disable-user --user 0 com.example.fakequickapp` → 宿主发 deeplink | 拉不起快应用 | `am start` 直接 `unable to resolve Intent`，前台仍是宿主 ✓ |
| 恢复 | `pm enable` | deeplink 又能拉起 | `enabled=1`，重新识别为 `响应hap链接` ✓ |
| **停用后留档** | 停用引擎 → 重启 GKD | 引擎仍在列表(能恢复) | `QuickApp engines=1 [com.example.fakequickapp(手动添加)]` ✓（靠 `quick_app_engine_list.txt`，见踩坑 2） |

**环境注意（本次新发现）**：MuMu 15 这台调试实例的 serial 是 **`emulator-5554`**（不是 §4.1 记的 `127.0.0.1:16384`；`emulator-5556` 是另一台模拟器，没有 GKD）。
另外**本机 adb server 会在多次调用之间消失**，所以本次新增统一入口 `tmp\adbq.ps1`（每次自动 `start-server` + 连接目标设备，`-Serial auto` 优先真机 → `emulator-5554`）。

### 11.5 本次踩坑（都影响过判断，务必记住）
1. **`gkd-build\gkd-fork.jks` 当时并不存在**（§10.2 说它在，其实只有 `GKD特调版\fork\gkd-fork.jks`）→ 构建前先从那边复制过来。
2. **`createTextFlow` 的 `drop(1)` 竞态会让"启动后立刻写名单"静默丢写**：引擎识别到了、拦截也生效，但 `store/quick_app_engine_list.txt` 一直不生成 →
   修复：`refreshNow()` 先读一次名单 flow（触发懒初始化），`rememberEngines()` 再**延后 300ms** 写入。修复后实测文件正常生成，且"停用引擎后重启 GKD"仍能靠它识别到引擎（`手动添加`）。
3. ★★ **做对照实验时 FakeSkipGuard 会抢答，害我误判一轮**：宿主靶 `SplashActivity` 上有"跳过广告"，GKD 的规则（模拟器上的本地订阅）会点它 → FakeSkipGuard 在 1.2s 后校验落点，若这期间我发的 deeplink 把引擎拉起来了，它会判定 `FakeSkipGuard misclick pkg=com.example.fakeadstest -> com.example.fakequickapp target=跳过广告, send BACK` 并**按返回键** —— 于是"关掉 quickAppGuard 后引擎仍会自己退"，看起来像"我的守卫没生效/有别的守卫在拦"。
   **正确姿势**：A/B 前先把 `enableMatch`、`fakeSkipGuard`、`shakeGuard`、`jumpGuard` 都置 false，只留 `quickAppGuard` 对比（本次就是这么拿到干净结论的）。
4. **宿主靶选错 Activity**：`fakeadstest/.MainActivity` 是 `exported=false`，shell 起不来（`Permission Denial: ... not exported`），于是"宿主根本没进前台、prev=桌面、按设计不拦" → 白查一轮。**adb 起靶只能用 `.SplashActivity`**。
5. **单次读焦点会有竞态**（引擎冷启动要 1~2 秒，早读会读到宿主）→ 脚本改成**焦点时间序列**（+700/1400/2100/2800ms 各采一次），一眼看出"起来→被退回"。
6. 换签名重装（见 §11.6）时**先备份再卸载**：`tar czf /data/local/tmp/x.tgz -C /sdcard/Android/data/li.songe.gkd files` → pull；重装后 `tar xzf` 还原（`settime ... Operation not permitted` 是正常的，只有 mtime 没设上）；实测 store.json 里的中文（`"actionToast":"GKD特调版"`）与订阅/设置全部保留。

### 11.6 ★ MuMu 上 GKD 的签名问题（已处理，需知道原委）
- 现象：`adb install -r` 装 fok0015 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE: ... signatures do not match`。
- 取证：把设备上的 base.apk pull 下来 `apksigner verify --print-certs` → 已装的 fok0014 是 **CN=Android Debug**（SHA-256 `<debug 证书 SHA-256 已省略>`），而 fork 构建是 `CN=GKD Fork`（`<fork 证书 SHA-256 已省略>`）。
- 又查了本机全部 13 个 keystore：`C:\Users\<用户名>\.android\debug.keystore` = `<debug keystore A 的 SHA-256 已省略>`、`tools\androidhome\debug.keystore` = `<debug keystore B 的 SHA-256 已省略>`，**都不等于 `<debug 证书 SHA-256 已省略>`** ⇒ 那个调试密钥在本机已经不存在了，**没法构建"同签名"的包**。
- 处理（保留模拟器配置）：备份 `files/` → `adb uninstall li.songe.gkd` → 装 **fork 签名版 fok0015** → `tar` 还原 `files/`。
  ⇒ 结论：**以后模拟器与真机统一用 fork 签名版**（`release\GKD特调版-1.12.2-fok0015.apk`），不要再指望那个来路不明的 debug 密钥。
  （另：`app/build.gradle.kts` 里不带 `-PGKD_STORE_FILE` 时 release 会**回退用 debug 密钥签名** —— 模拟器上那个 fok0014 多半就是这么来的；本次一度构建过这种包，已删除避免混淆。）

### 11.7 ★★ 真机验收（2026-10-02，vivo V2238A / Android16 / serial `<真机序列号>`，全部实测）
- **装包**：`adb install -r` **本次直接 Success（没弹 vivo 确认框**，`mScreenOn=true` + `isKeyguardShowing=false` 时发起）；`versionCode=108 / versionName=1.12.2-fok0015`。
- **配置零丢失**：订阅 `subs_item = -2|0, 1|1`；`jump_guard_app_list.txt`(102B，5 个应用)、`guard_assoc_app_list.txt`(127B) 都在；无障碍仍在启用列表且已绑定；store.json 无 `quickAppGuard` 键 → 走默认 `true`。

| 项 | 期望 | 实测 |
|---|---|---|
| **识别 vs 地面真值** | 与独立取证一致 | ★ `QuickApp engines=2 [com.vivo.hybrid(响应hap链接+包名特征), com.vivo.vhome(响应hap链接)]`，与 `cmd package query-activities --brief -a VIEW -c BROWSABLE -d hap://app/...` 的返回（`com.vivo.hybrid/.main.DispatcherActivity`、`com.vivo.vhome/com.vivo.hybrid.main.DispatcherActivity`）**逐字一致** ✓ |
| 只有 `hap` 有效 | 其它 scheme 无响应 | `hapjs / hwfastapp / fastapp / quickapp / vivoquickapp` 全部 **No activities found** ⇒ 按 `hap://` 探测是对的 ✓ |
| **拦截真引擎** | 引擎起来 → 立刻退回宿主 | 宿主=系统设置：GKD 转场日志 `com.android.settings/.homepage.SettingsHomepageActivity -> com.vivo.hybrid/null` → `QuickApp block pkg=com.android.settings -> com.vivo.hybrid engine=快应用框架服务, send BACK` → `com.vivo.hybrid/.LauncherActivity$Launcher3 -> com.android.settings/.homepage.SettingsHomepageActivity` → `QuickApp back ok sent=true now=com.android.settings`；焦点 5 次采样（700ms 间隔）**全程在宿主** ✓ |
| 引擎名单落盘 | 真机首次生成 | `store/quick_app_engine_list.txt` = `com.vivo.hybrid` + `com.vivo.vhome` ✓ |
| 手机 UI 冒烟 | 看「关闭快应用」「快应用引擎 (2)」 | ⚠️ **未做**：测试途中手机自己锁屏了（uiautomator dump 拿到的是锁屏）。APK 与 MuMu 上验证过 UI 的是**同一个二进制**，逻辑等价；要看的话解锁后进「设置」往下滑即可 |

**★★ 真机能力矩阵（决定"根治层"在这台机器上能做到哪一步）**
| 能力 | 实测 | 证据 |
|---|---|---|
| 停用/恢复**数据应用**（包级） | ✓ 双向通 | `pm disable-user --user 0 com.example.fakeadstest` → `enabled=3`；`pm enable` → `enabled=1` ⇒ **§10.7 记录的那个 `pm enable` 老坑只适用于"组件级"**，包级没问题 |
| 停用**系统应用**（包级） | ✗ **被 vivo 加锁** | `pm disable-user --user 0 com.vivo.base.player` → `SecurityException: Cannot disable com.vivo.base.player no root permission`（`PackageManagerService.setEnabledSettingInternalLocked:4699`）。这是 **vivo 私有加固，AOSP/MuMu 上没有**（MuMu 上系统应用可停用）⇒ **`com.vivo.hybrid` 这类引擎非 root 停不掉** |
| appops 写引擎「安装应用」权限 | ✓ 可写 | 对 `com.vivo.hybrid`、`com.vivo.vhome` 都实测 `default → deny → default`，**已还原**（`get` 读回确认） |
| Shizuku | 未安装 | `pm list packages \| grep -iE 'shizuku\|rikka'` 为空 ⇒ App 引擎页只显示可复制的 adb 命令，动作按钮不可用 |
| 探测用小白鼠清理 | ✓ | `com.example.fakeadstest` 已 `adb uninstall`（当时它已不在手机上，是本次临时装的） |

⇒ **对这台手机的现实结论**：默认开的「秒退」**已真机验证有效**（本功能主力）；「禁止引擎安装应用」可执行（App 里复制命令 / 装 Shizuku 后一键）；「停用引擎」在 vivo 上会**失败并如实报错**（需 root，或让用户在系统设置里手动停用/卸载更新快应用）。

### 11.8 待办（下一步）
1. **[小改进，已定位，尚未改代码] 引擎页文案要按 ROM 说清楚**：现在写的是"「停用」= 系统层面关掉这个引擎(可随时恢复, 推荐)"，但在 vivo 上非 root 必然失败（§11.7）。建议改成："优先用「禁止安装应用」（多数 ROM 可用）；「停用」在部分 ROM（如 vivo）对系统应用需要 root，失败时请改用系统设置里的快应用开关"。改完要重建 + 重装（真机与模拟器都装一次）。
2. **[需你确认才动] 彻底关掉快应用引擎**：vivo 非 root 做不到 `pm disable-user`，但仍有三条路 —— ① 系统设置里手动"停用/卸载更新/关闭快应用"；② 装 Shizuku（或 root）后由 App 一键停用；③ 只掐安装权限（已验证可写）。**注意**：停用引擎会影响快应用本身，若用户还想用某些快应用就别停用。
3. **[可选] 给 `GKD一键ADB配置.exe` 增加"关闭快应用"步骤**：按本次结论实现为"**`appops deny` 一律执行 + `pm disable-user` 尝试执行并如实报告（vivo 会失败）**"，加 `--restore-quickapp` 回滚。编译命令见 §10.4。
4. **[环境] 模拟器上现在装了假引擎靶**：`com.example.fakequickapp` 会一直被当成快应用引擎（预期行为）。不再需要时：`adb uninstall com.example.fakequickapp` + 删 `store/quick_app_engine_list.txt`。
5. **[手机上已生效]** 手机现为 **v107=fok0015**，`quickAppGuard=true`（默认），引擎名单里是真机的两条 vivo 引擎 —— 之后正常用手机即可，遇到快应用广告会秒退（日志 tag `QuickApp`）。

---

## 12. 2026-10-02（同日第二轮）：修「摇一摇跳转防护失效」+ 开屏时长可配（v108=fok0016 / v108b=fok0017）

### 12.1 用户原话与结论
- 原话："修复摇一摇跳转防护失效的问题。改成用户可以自定义开屏的时长。"
- 结论：**失效是真 bug（两条根因），不是"没配对"**；已修，并按要求把"开屏时长"交给用户（1.5/2/3/5/8/10/15 秒，默认 8 秒）。

### 12.2 ★ 根因（用户自己手机日志里就抓到了）
`gkd-20261002.log`，校园卡 App（`com.newcapec.mobile.ncp`，在用户的防护名单里）当天有 **三次 `SplashActivity → 菜鸟裹裹` 的跨应用跳转，且没有任何 JumpGuard 记录**：

| 源页面出现 | 跳到菜鸟 | 时间差 | 旧 1.8s 窗口 |
|---|---|---|---|
| 13:29:16.835 | 13:29:21.868 | **5.03 s** | 追不上 |
| 13:29:40.414 | 13:29:47.184 | **6.77 s** | 追不上 |
| 13:30:27.780 | 13:30:31.932 | **4.15 s** | 追不上 |

- **根因① 开屏时长写死 1.8 秒** → 上面三次全部漏掉（实测 4~7 秒）。
- **根因② 让位判据太粗**：旧代码 `if (lastGkdActionAt >= prevSince) return`（"本次前台期间 GKD 点过任何东西就让位"）。开屏时 GKD 几乎必然点过（跳过/关弹窗），而 `prevSince` 是"应用成为前台"的时刻 → **从那一刻起的整个应用会话都被让位**，等于"GKD 规则能生效的应用"里这个功能整体失效。
- 附带发现（决定语义）：若按"应用成为前台"计时，上面第 2 次的时间差会累加成 **14 s**（同应用内换页也被算进去）——所以计时起点必须是**页面**，才符合"开屏时长"这个说法。

### 12.3 改动清单（v108）
| 文件 | 改动 |
|---|---|
| `service/JumpGuard.kt` | ① 让位判据改为 `FakeSkipGuard.isVerifyingSkipClick()`（只在"跳过类点击的落点校验在飞"时让位）；② 计时起点改为**当前页面出现时刻**，并用 `ENTRY_LIMIT_MS=60s` 限定"只在开屏阶段这么算"（防"逛久了换页后主动跳转被拽回"）；③ 开屏时长读 `store.jumpGuardWindowMs`；④ 新增 `not-guarded` 候选提示日志；⑤ 动作日志补 `window=` 与 `gkdClickAgo=` |
| `service/FakeSkipGuard.kt` | 新增 `lastSkipClickAt` + `isVerifyingSkipClick()`（语义很窄：只有"刚点过跳过、正在等落点"才算） |
| `util/Option.kt` | 新增 `JumpGuardWindowOption`（1.5/2/3/5/8/10/15 秒） |
| `store/SettingsStore.kt` | 新增 `jumpGuardWindowMs: Long = 8_000`（老 store 无此键 → 自动走默认 8 秒） |
| `ui/home/SettingsPage.kt` | 「摇一摇跳转防护」下新增 **TextMenu「开屏时长」** + 一行说明（含"调大/调小的取舍"与 not-guarded 日志提示）；开关副标题改为按开屏时长描述 |
| `ui/JumpGuardAppListPage.kt` | 页内说明同步（不再提"1.8 秒"与"GKD 没点过任何东西"） |
| `app/build.gradle.kts` | `fok0016`/`versionCode 109` → **`fok0017`/`versionCode 110`**（fok0017 = fok0016 + 上面那条 60 秒上界） |

### 12.4 验收（MuMu 模拟器，`tmp\jump-guard-fix-test.ps1 -Case all` 全绿）
| 用例 | 条件 | 实测 |
|---|---|---|
| A1 窗口生效 | window=8000，gap=2500ms（旧窗口必漏） | `JumpGuard jump pkg=com.example.fakeadstest -> com.android.settings gap=2091ms window=8000ms ... send BACK` → `back ok`，焦点 7 次采样全程在宿主 ✓ |
| A2 设置真的生效 | window=1500，同样 gap | **无** jump 日志、焦点停在设置 ✓ |
| **B 让位 bug 已修** | enableMatch=true（GKD 先点掉宿主开屏的"跳过"），3.5s 后再跳 | `gap=3633ms window=8000ms **gkdClickAgo=7911ms** ... send BACK` → 仍然拦住 ✓（旧代码在这里必失效） |
| C 候选线索 | 源应用不在名单里 | 不拦，但记 `JumpGuard not-guarded pkg=... -> ... gap=2180ms (未在「跳转防护应用」名单里...)` ✓ |
| D 开屏阶段上界（fok0017） | 打开宿主后等 **65 秒**（>60s）再跳 | jump 行数 before=after=4、焦点停在设置 → **不干预** ✓ |

### 12.5 真机验收（vivo V2238A / Android16，fok0016=fok0017 同逻辑）
| 用例 | 实测 |
|---|---|
| 京东 → 设置（gap 4.2s，旧窗口必漏） | `JumpGuard jump pkg=com.jingdong.app.mall -> com.android.settings gap=4247ms window=8000ms ... send BACK` → `back ok now=com.jingdong.app.mall` ✓ |
| **校园卡（真有摇一摇广告）** | `13:48:51 FakeSkipGuard ok ... target=跳过 5`（GKD 先点了跳过）→ `13:49:06 JumpGuard jump pkg=com.newcapec.mobile.ncp -> com.android.settings gap=6915ms window=8000ms **gkdClickAgo=7772ms** ... send BACK` → toast「跳转防护: 已从「设置」退回「完美校园」(开屏 8.0 秒内的跳转)」→ 转场日志确认已退回 ✓ |
| 12 秒 gap（>8 秒窗口） | 不拦 ✓（当天日志里最后一次 `JumpGuard jump` 就是上面那条） |
| 设置页 UI | 「摇一摇跳转防护」下出现 **开屏时长 / 8 秒**（uiautomator dump 实证）✓ |

### 12.6 本次踩坑与取舍（务必知道）
1. **页面计时会"换页就重新上膛"**：真机测试里我 `am start -S` 强停应用时，下层任务浮起来造成一次 `gap=180ms` 的假跳转被拦；更值得记的是"用户在名单应用里逛着、换个页面后 8 秒内主动点跳转也会被拽回"。为此加了 `ENTRY_LIMIT_MS=60s`（只在开屏阶段用页面计时）。**若用户还嫌烦 → 调小开屏时长，或把该应用移出名单。**
2. **`Measure` 会被 PowerShell 内置别名 `measure` 抢走**（`Measure-Object`）→ 脚本里的测量函数必须换名（本次改成 `Run-Case`）；另外 `.ps1` 里**不能出现中文路径字面量**（PS 5.1 按 ANSI 读会变成乱码路径），要用 `$PSScriptRoot`。
3. **手机上装 fok0017 时 vivo 又弹了 `PackageInterceptActivity` 确认框**（按钮不在无障碍树里，脚本点不到）→ 只能人工点「继续安装」。装完核对 `versionName=1.12.2-fok0017`。
4. **调大窗口的代价**是"用户自己刚进应用就点跳转"也会被退一次（有 toast 明确告知）——这是用户主动要的可配置项，默认取 8 秒是按真机实测定的。

---

## 13. 2026-10-02（同日第三轮）：再修「完美校园 → 百度网盘」漏拦 + 重做「防摇一摇广告」（v109=fok0018/fok0019）

### 13.1 用户原话
"还是不行，从完美校园跳到百度网盘时没能成功拦截，修复一下。然后再看看防摇一摇广告的功能（打开应用后2秒内自动拦截的功能）是否正常。"

### 13.2 ★ 漏拦取证（`gkd-20261002.log`，两次都没拦）
| # | 源页面出现 | 跳到百度网盘 | gap | 当时窗口 |
|---|---|---|---|---|
| 1 | 14:07:04.052 | 14:07:10.380 | **6.33 s** | 3000 ms |
| 2 | 14:08:25.675 | 14:08:30.801 | **5.13 s** | 3000 ms |

- 直接原因：**用户手机上 `jumpGuardWindowMs` 是 3000**（我上一轮默认给的是 8000；这个 3000 是用户自己在设置页选的，或早先 S3 版本写进 store 的），而广告是 5~6 秒才跳。
- ⇒ 结论：**"让用户自己猜自家广告几秒"这个设计不可靠**。所以 v109 加了**语义兜底**：见 13.3②。

### 13.3 改动（v109=fok0018，随后 fok0019 修一处取页面名的坑）
1. **`ShakeGuard` 重做**（"防摇一摇广告/开屏自动关闭"，原来基本是死的）：
   - 取证：4 天日志 `ShakeGuard handled` **0 次**（skip 1225~1345 次/天），`shake=true` 每天 0~1 次。
   - 实测完美校园开屏页（uiautomator dump）：**1.2s 什么都没有 → 3.2s 才出现真正可点的 `text="0S | 跳过"`（clickable=true）→ 6s 页面就没了**；整棵树里**没有任何"摇一摇"字样**。
   - 旧版两条门一起把人卡死：①窗口 2 秒（按钮 3.2s 才出现）②**强制要求先看到"摇一摇"提示词**（广告里那行字是图片/动画，不在无障碍树里）。
   - 改法：①窗口改用**共用的「开屏时长」**（默认 8 秒），且**同应用内换页也重置窗口**；②**不再强制**要提示词（看到就记给 JumpGuard 当证据，日志带 `shakeHint=`）；③**规则刚点过 1.5 秒内不补刀**（避免同一按钮被两处各点一次）；④只做**节点点击**（绝不打坐标、不做返回键兜底），⑤不再 recycle `rootInActiveWindow`（它与规则引擎共用，回收可能让同一次事件里的规则匹配拿到已回收节点）。
2. **`JumpGuard` 加"开屏/广告页"语义兜底**：源页面类名里含 `splash / advert / adactivity / .ads. / welcome / guideactivity` 时，**有效窗口取 max(用户设定, 15 秒)**（仍受 `ENTRY_LIMIT_MS=60s` 限制）。这样即使用户把开屏时长设成 3 秒，开屏页上的跳转照样拦得住 —— 上面两次 5~6 秒的漏拦都落在这条上。
3. **取"源页面名"改走 `topActivityFlow`**（fok0019）：实测完美校园开屏页那一次事件的 `event.className` 是**空的**（GKD 靠 `fixAppId` 才解析出 `...basebusiness.SplashActivity`），只信 className 会让"开屏页"这个强信号丢掉 —— 这正是我第一次真机复现失败的原因。`JumpGuard` 在 `A11yService` 里跑在规则引擎**之前**，此刻 `topActivityFlow` 还是"上一个页面"，正好是要判的跳转来源。
4. 设置页文案同步：`防摇一摇广告` 副标题改成"打开应用后的「开屏时长」内自动点掉真正可点的跳过/关闭按钮"；`开屏时长` 说明里点明"两个模块共用 + 开屏/广告页另有 15 秒兜底"。

### 13.4 验收（MuMu，fok0019）
| 用例 | 实测 |
|---|---|
| ShakeGuard 开屏点掉广告（靶按钮 3s 才 enable，旧版 2s 窗口必错过） | `ShakeGuard handled pkg=com.example.fakeadstest via click=跳过广告 shakeHint=true window=8000ms nodes=12` → 界面进入主界面 ✓ |
| **ShakeGuard 无提示词也点**（靶的"我知道了弹窗"，树里没有摇一摇字样） | `ShakeGuard handled pkg=com.example.fakeadstest via click=我知道了 **shakeHint=false** window=8000ms nodes=10` ✓ |
| JumpGuard 回归（`jump-guard-fix-test.ps1 -Case all`） | A1 拦 / A2 不拦 / **B `gkdClickAgo=3925ms` 仍拦** / C 给 `not-guarded` 线索 —— 全绿 ✓ |

### 13.5 真机（vivo）状态与待办
- **真机 fok0019 安装需要人工点 vivo 确认框**（`PackageInterceptActivity`；实测 UI dump 里**只有「取消安装」可点**，确认按钮不在无障碍树里，脚本点不到；第一次发起后被我其他 adb 操作打断，报 `INSTALL_FAILED_ABORTED: User rejected permissions`）。
- 装完后要做的真机复现（**命令可直接照抄**，宿主用完美校园开屏页、窗口保持用户自己的 3000ms）：
  ```
  adb shell am start -S -n com.newcapec.mobile.ncp/com.wanxiao.basebusiness.activity.SplashActivity
  # 等 5.5 秒
  adb shell am start -n com.baidu.netdisk/.ui.DefaultMainActivity
  adb shell "grep -a 'JumpGuard jump' /sdcard/Android/data/li.songe.gkd/files/log/gkd-$(date +%Y%m%d).log | tail -2"
  # 期望: ... -> com.baidu.netdisk gap=~5500ms window=15000ms page=SplashActivity ... send BACK
  ```
  ⚠️ 测之前先 `cmd statusbar collapse` + `input keyevent KEYCODE_HOME`：**通知栏展开时焦点被 `NotificationShade` 抢走**，会让 a11y 事件跟丢（本次踩过，白测一轮）。
  ⚠️ 另一坑：`am start -S` 强停应用时**下层任务会浮起来**，产生一次 `gap≈200ms` 的假跳转被拦（日志里看得到 `-> com.vivo.upslide gap=209ms`）——这是测试手法造成的，不是误伤；但也提示：**用户自己划掉名单应用时，下层应用浮起可能被判成一次跳转**（概率低、有 toast 可辨识，暂不处理，记录在此）。
- **用户手机当前设置已恢复**：`enableMatch=true`、`shakeGuard=true`、`jumpGuard=true`、`quickAppGuard=true`、`jumpGuardWindowMs=3000`（用户自己设的，未改动）。

### 13.6 fok0020（收尾两处小缺陷）+ ★ 真机验收通过
- **fok0020 改了两点**：
  1. `hintNotGuarded` 在"刚处置过"（`now < suppressReturnUntil`）时不再打印 —— 真机实测踩到：我们 `relaunch` 把用户拉回完美校园后，"**百度网盘 -> 完美校园**"被记成一条 `not-guarded` 候选线索，**会误导用户去把百度网盘加进防护名单**（等于把自家动作当成广告源）。
  2. `ShakeGuard` 拿不到 `rootInActiveWindow` 时**不占用**那 300ms 扫描配额（vivo 转场瞬间 root 常为 null，旧写法要白等一轮，开屏那几秒很容易被浪费）。
- **★ 真机验收（vivo V2238A / Android16，fok0019 与 fok0020 各跑一次，均通过）**：

  | 用例 | 实测 |
  |---|---|
  | **完美校园开屏页 → 百度网盘**（用户报的漏拦，窗口仍是用户设的 3000ms） | fok0019：`JumpGuard jump pkg=com.newcapec.mobile.ncp -> com.baidu.netdisk gap=4809ms **window=15000ms page=SplashActivity** ... send BACK` → `back missed sent=true now=null **relaunch=true** after=com.newcapec.mobile.ncp`；焦点全程在完美校园 ✓<br>fok0020：`gap=4690ms window=15000ms page=SplashActivity ... send BACK` ✓ **且日志里不再出现 `not-guarded pkg=com.baidu.netdisk -> ...` 假线索** ✓ |
  | ShakeGuard 真机扫描 | `ShakeGuard skip pkg=com.newcapec.mobile.ncp reason=no-close-button shakeHint=false nodes=154` —— 说明它**确实在扫**（154 个节点），当次页面确实没有可点的跳过/关闭（这广告页有时只有不可点的"跳过"文字，那类它一律不碰）；MuMu 上"有按钮"的场景两次都点掉了（§13.4） |
- **结论**：用户报的两件事都闭环了 —— ①（完美校园→百度网盘）漏拦：已拦；②（防摇一摇广告）"是否正常"：原来 4 天 0 次生效（窗口 2 秒 + 强制提示词），现在窗口共用「开屏时长」、不再强制提示词、规则刚点过不补刀，MuMu 双路径 + 真机扫描均已验证。
- 安装记录：手机历次版本 fok0017 → **fok0019 → fok0020**（vivo 确认框每次都要人工点；同时把 APK 推到 `/sdcard/Download/` 作为备选路径）。
