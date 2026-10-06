package li.songe.gkd.service

import li.songe.gkd.META
import li.songe.gkd.a11y.launcherAppId
import li.songe.gkd.app
import li.songe.gkd.util.systemUiAppId
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.ConcurrentHashMap

/**
 * fork(v115=fok0022): 「系统界面」识别 —— [ShakeGuard]/[JumpGuard]/[FakeSkipGuard]/[QuickAppGuard]
 * 共用的第一道闸门。
 *
 * ## 为什么要它(真机 vivo V2238A / Android16 取证, `gkd-20261002.log` + 面板界面树)
 *
 * 用户报"**上拉到控制面板时会触发东西, 关掉 GKD 就不触发**"。日志与界面树把两个肇事模块钉死了:
 *
 * 1. `ShakeGuard` 把控制面板当成了开屏广告页。面板里 **7 个可点开关**的节点 `text` 恰好就是"关闭"
 *    (`class=android.widget.Switch`, 功能名在 `content-desc` 里):
 *
 *    | class | text | content-desc |
 *    |---|---|---|
 *    | android.widget.Switch | 关闭 | 飞行模式 |
 *    | android.widget.Switch | 关闭 | WLAN- |
 *    | android.widget.Switch | 关闭 | 振动模式 / 静音模式 |
 *    | android.widget.Switch | 关闭 | 省电模式 |
 *    | android.widget.Switch | 关闭 | 手电筒 |
 *    | android.widget.Switch | 关闭 | GKD特调版(本 App 的磁贴) |
 *
 *    而 ShakeGuard 的"关闭按钮"判据是 `clickable && text.contains("关闭")` ⇒ 一天点了 **40+ 次**
 *    (`ShakeGuard handled pkg=com.android.systemui via click=关闭`), 其中点到 GKD 自己的磁贴时
 *    直接把无障碍关掉了(`A11yAutoGuard manualOff=true` + "无障碍已关闭")。
 *    同一形态还误伤了应用内的**功能开关**: `tv.danmaku.bili via click=关闭弹幕`,
 *    `com.android.camera via click=超微距,关闭`。
 * 2. `JumpGuard` 把用户"上滑"产生的 `com.vivo.upslide`(上滑面板/手势窗口)和 `com.vivo.hiboard`
 *    (负一屏)**当成了"跳到了别的应用"**, 一天按了 **7 次返回键 + 拉起原应用**
 *    (`JumpGuard jump pkg=com.newcapec.mobile.ncp -> com.vivo.upslide gap=174ms ... send BACK`)。
 *
 * ⇒ 结论: 这些窗口**不是"别的应用"**, 任何一个守卫都不该在它们上面动手, 也不该把它们当成跳转目标。
 * 把这件事集中定义一次, 免得每个模块各写一份(以前就是各写各的, 于是 `systemui` 有人过滤、
 * `upslide` 谁都没过滤)。
 *
 * ## 两类系统窗口(语义不同, 不能混为一谈)
 *
 * - [isTransientSurface] **瞬时/浮层窗口**: 状态栏、通知、转场动画、厂商权限弹窗、以及所有
 *   "没有桌面启动入口"的系统服务包。它们**不代表用户去了别的地方** —— 必须**忽略但不改状态**。
 *   这一点是 fok0021 的结论: 让它们覆盖"源应用"会让紧随其后的真跳转被判成 `systemui -> B`
 *   而**连日志都不留**(表现为"摇一摇跳转防护像是没生效")。
 * - [isUserLeftSurface] **用户主动离开应用的面板**: 桌面、上滑/控制面板、负一屏、AI 助手。
 *   它们说明**用户已经离开当前应用** —— 既不能当跳转目标去按返回键(用户自己的操作),
 *   也不能继续把原应用当成"还在开屏阶段的源应用"([JumpGuard] 因此清空源状态, 见其调用点)。
 *
 * ## 判据为什么这样写
 *
 * 除了厂商包名清单, 还有一条**与 ROM 无关**的判据: **这个包有没有任何 Activity**
 * (见 [hasAnyActivity])。完全没有 Activity 的包只能是系统服务/插件, 不可能是"用户所在的应用"。
 *
 * ⚠️ fok0025: 这条判据原本是"有没有**桌面启动入口**"(`getLaunchIntentForPackage`), 真机实测
 * **联想平板(ZUI)** 上 `com.android.settings` 会被判成"没有入口" ⇒ 被当成系统浮层忽略 ⇒
 * "从设置页被广告拉进快应用"静默失效(QuickAppGuard 日志里看到 `prev=com.zui.launcher`,
 * 而当时设置就在前台)。而 `cmd package resolve-activity -a MAIN -c LAUNCHER` 能解析出
 * `com.android.settings/.Settings` ⇒ 那个 API 在该 ROM 上不可靠。改成"有没有 Activity"后:
 *
 * ```
 * 本机实测(联想 TB710FU / Android 16): com.android.camera / com.android.gallery3d /
 *   com.android.documentsui 都**没有**桌面入口, 但都有 Activity ⇒ 现在一律当成正常应用(修好前会被误判)
 * vivo V2238A: com.vivo.hiboard / smartmultiwindow / frameworkui / daemonService / globalanimation /
 *   systemuiplugin / gamecube / fingerprintui / nightpearl / com.bbk.launcher2 / com.android.systemui
 *   -> 没有启动入口(其中纯服务类还会被"无 Activity"判据挡住)
 * com.vivo.browser / com.android.settings / com.baidu.netdisk -> 正常应用, 不拦
 * ```
 *
 * ⚠️ 例外要记住: `com.vivo.upslide` **有** Activity(`InteractionActivity`), 所以判据挡不住它,
 * 必须显式写在 [userLeftIds] 里 —— 这也正是"只靠通用判据不够、仍要一份厂商清单"的原因。
 */
object SystemSurfaces {

    /** 瞬时/浮层窗口: 状态栏、通知、转场、权限弹窗… (忽略, 但**不改**任何状态) */
    private val transientIds = setOf(
        systemUiAppId,
        "android",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        META.appId, // 自己(GKD)的界面: 旧代码各模块各自过滤, 这里统一
        // ---- vivo / OriginOS: 转场动画、系统插件、系统服务 ----
        "com.vivo.globalanimation",
        "com.vivo.frameworkui",
        "com.vivo.daemonService",
        "com.vivo.fingerprintui",
        "com.vivo.nightpearl",
        "com.vivo.smartmultiwindow",
        "com.vivo.gamecube",
        "com.vivo.systemuiplugin",
        "com.vivo.contentcatcher",
    )

    /**
     * 用户主动离开当前应用的面板。桌面本尊([launcherAppId])在 [isUserLeftSurface] 里**动态**读取
     * (它在 A11yState 里是被赋值的 var, 放到这个 set 里会把启动时的空串固化下来)。
     */
    private val userLeftIds = setOf(
        "com.vivo.upslide", // ★ 上滑面板/手势窗口(真机 7 次误按返回键的来源)
        "com.vivo.hiboard", // 负一屏(智慧桌面)
        "com.vivo.ai.copilot", // Jovi/AI 助手
        "com.bbk.launcher2", // vivo 桌面
        "com.zui.launcher", // 联想 ZUI 桌面
        "com.android.launcher3",
        "com.android.launcher",
    )

    /**
     * "这个包**有没有任何 Activity**" —— 结果缓存: 无障碍热路径**每次窗口事件**都会问一次。
     *
     * ⚠️ fok0025 修正: 原来这里用的是 `getLaunchIntentForPackage(pkg) != null`(有没有桌面入口),
     * 真机实测在**联想平板(ZUI)**上 `com.android.settings` 被判成了"没有入口" ⇒ 被当成系统浮层忽略,
     * 于是"从设置页被广告拉进快应用"这条路径**静默失效**(QuickAppGuard 日志里 `prev=com.zui.launcher`,
     * 而当时设置明明在前台)。`cmd package resolve-activity -a MAIN -c LAUNCHER` 却能解析出
     * `com.android.settings/.Settings` ⇒ 应用内那个 API 在该 ROM 上不可靠。
     *
     * 现在改成**只看"有没有 Activity"**(与 GKD 自己的 [li.songe.gkd.data.AppInfo] 里
     * `checkHasActivity` 同一套判据): 只有**完全没有 Activity 的纯服务/插件包**才算系统浮层,
     * 真实应用(设置/相机/图库/文件管理, 实测后三个在这台平板上都没有桌面入口)一律不受影响。
     * 出错时默认 true(当作普通应用): 宁可少拦, 也不要因为查询失败而把正常应用当浮层。
     */
    private fun hasAnyActivity(pkg: String): Boolean = launchableCache.getOrPut(pkg) {
        runCatching {
            val pm = app.packageManager
            if (pm.getLaunchIntentForPackage(pkg) != null) return@runCatching true
            if (pm.queryIntentActivities(
                    Intent().setPackage(pkg),
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                ).isNotEmpty()
            ) return@runCatching true
            pm.getPackageInfo(
                pkg,
                PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_ACTIVITIES,
            ).activities?.isNotEmpty() == true
        }.getOrDefault(true)
    }

    private val launchableCache = ConcurrentHashMap<String, Boolean>()

    /**
     * ★ v121: **输入法(键盘)包** —— 用户敲键盘**不是**"跳到了别的应用"。
     *
     * 取证: `gkd-20261002.log` 20:52:31 `JumpGuard jump pkg=com.aliyun.tongyi -> com.baidu.input_vivo
     * gap=835ms window=1500ms … send BACK` —— 用户在千问里点开键盘, GKD 把输入法当成"跳转目标"
     * **按了返回键**(表现: 键盘一闪没了)。
     *
     * 动态读(懒加载一次): 启用的输入法服务 + secure 里的当前输入法 —— 与包名表无关, 换输入法也跟得上。
     * 语义按 [isTransientSurface] 处理: **忽略但保留源应用**(用户还在原应用里打字)。
     */
    private val imePackages: Set<String> by lazy {
        runCatching {
            val ids = linkedSetOf<String>()
            (app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.enabledInputMethodList
                ?.forEach { imi -> runCatching { ids.add(imi.packageName) } }
            Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { flat -> ComponentName.unflattenFromString(flat)?.packageName?.let { ids.add(it) } }
            ids.filter { it.isNotEmpty() }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * ★ v121: **相机 / 相册 / 文件选择器**包 —— 这些是"用户主动发起的跨应用意图"(拍照、选图、选文件),
     * 不是广告跳转目标。
     *
     * 判据不写死包名(各 ROM 千差万别), 而是**查谁会响应这些标准 Intent**(ACTION_IMAGE_CAPTURE /
     * ACTION_VIDEO_CAPTURE / ACTION_PICK / ACTION_GET_CONTENT / ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT);
     * 懒加载一次后就是集合判断, 热路径零开销。
     *
     * 用法与输入法不同: **只**用在"要不要按返回键/要不要算误点"这种地方([JumpGuard] 的跳转目标、
     * [FakeSkipGuard] 的落点), 不把它整体当系统浮层 —— 免得连"在相机里找跳过按钮"这种扫描也一起停掉。
     */
    private val userIntentPackages: Set<String> by lazy {
        runCatching {
            val pm = app.packageManager
            val ids = linkedSetOf<String>()
            val actions = listOf(
                MediaStore.ACTION_IMAGE_CAPTURE,
                MediaStore.ACTION_VIDEO_CAPTURE,
                Intent.ACTION_PICK,
                Intent.ACTION_GET_CONTENT,
                Intent.ACTION_OPEN_DOCUMENT,
                Intent.ACTION_CREATE_DOCUMENT,
            )
            val types = listOf(null, "image/*", "*/*")
            actions.forEach { action ->
                types.forEach { type ->
                    runCatching {
                        val intent = Intent(action).apply {
                            addCategory(Intent.CATEGORY_DEFAULT)
                            if (type != null) setTypeAndNormalize(type)
                        }
                        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { ri ->
                            ri.activityInfo?.packageName?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
                        }
                    }
                }
            }
            // 兜底: AOSP/多数 ROM 的文件选择器包名(查询拿不到时至少挡住它)
            ids.add("com.android.documentsui")
            ids.filter { it.isNotEmpty() }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * ★ v121: 这个包是"用户主动发起的意图目标"吗(输入法 / 相机 / 相册 / 文件选择器)。
     *
     * 用在**判错就会误按返回键**的地方: [JumpGuard] 的跳转目标判定、[FakeSkipGuard] 的落点判定 ——
     * 用户从 A 打开相机拍照、或点开键盘, 都不该被当成"被广告带走了"。
     */
    fun isUserIntentTarget(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return pkg in imePackages || pkg in userIntentPackages
    }

    /** 桌面(动态读 [launcherAppId], 见 [userLeftIds] 的说明) */
    private fun isLauncher(pkg: String): Boolean = launcherAppId.isNotEmpty() && pkg == launcherAppId

    /**
     * 瞬时/浮层窗口(状态栏、通知、转场、厂商系统服务) —— 守卫应**忽略**它们, 但**不要**因此清空
     * "源应用/开屏窗口"状态(见类注释里 fok0021 的结论)。
     */
    fun isTransientSurface(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return true // 包名未知: 宁可不动作
        if (isLauncher(pkg) || pkg in userLeftIds) return false
        if (pkg in transientIds) return true
        // ★ v121: 输入法(键盘)窗口 —— 用户在自己应用里打字, 不是"去了别的地方", 也不是跳转目标
        if (pkg in imePackages) return true
        // fok0025: 只有"完全没有 Activity 的包"才算系统浮层(见 hasAnyActivity 的说明)
        return !hasAnyActivity(pkg)
    }

    /** 桌面/上滑面板/负一屏等"用户已经离开当前应用"的界面 */
    fun isUserLeftSurface(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return isLauncher(pkg) || pkg in userLeftIds
    }

    /**
     * **只看显式清单**(不含"没有桌面启动入口"那条启发式)。
     *
     * 用在"源应用/落点"这种**判错就会丢功能**的地方([QuickAppGuard] 的 `prev` 判断):
     * 启发式会把没有桌面图标的**真实应用**也算成系统浮层 —— 本机实测联想平板上
     * `com.android.camera / com.android.gallery3d / com.android.documentsui` 都没有 LAUNCHER 入口,
     * 而它们明明是用户能用的普通应用。若拿启发式去判"来源应用", 用户在这些应用里被广告拉进快应用
     * 就不会被拦(功能静默失效)。
     */
    fun isExplicitSystemSurface(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return true
        return isUserLeftSurface(pkg) || pkg in transientIds
    }

    /** 以上两类都算(含启发式): 用在"**别在它上面动手**"这种判错只是少做一次动作的地方 */
    fun isSystemSurface(pkg: String?): Boolean =
        isUserLeftSurface(pkg) || isTransientSurface(pkg)
}
