package li.songe.gkd.service

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.appScope
import li.songe.gkd.store.jumpGuardAppListFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.toast
import java.util.concurrent.ConcurrentHashMap

/**
 * 免 root「摇一摇跳转防护」(fork v105 新增, v106 改为按应用设定, **v108 修失效 + 开屏时长可配**)。
 *
 * 为什么需要它(A→B 两条老路都走不通):
 *   1. `ShakeGuard` 是"看到摇一摇提示 **且** 有可点关闭按钮才点"的事后补救 —— 广告一摇就跳、
 *      根本不给按钮时无效;
 *   2. v104 想从根上掐掉传感器(把该应用的「获取设备方向」appop 置 ignore), 但 2026-09-26 真机取证
 *      证明 **vivo 上根本没有这个 appop**(设备 78 个 op 里只有 BODY_SENSORS 心率那个, 见交接文档 §10.5),
 *      该路线在本 ROM 不成立。
 *   → 于是改成"事后拦截跳转": 这是本 ROM 上唯一可验证可行的方向。
 *
 * ★ 只对用户勾选的「跳转防护应用」生效(设置 → 摇一摇跳转防护 → 跳转防护应用), 名单默认为空。
 *
 * 判据(全部成立才动作):
 *   1. 源应用 A 在「跳转防护应用」名单里;
 *   2. 跨应用跳转发生在 **A 的当前页面出现之后的开屏时长内**(`store.jumpGuardWindowMs`, 用户可配, 默认 8 秒);
 *   3. 前台从 A 切到了别的应用 B, 且 B **不是系统界面**(桌面 / 状态栏 / 上滑面板 / 负一屏 / GKD 自己 —— 判据见 [SystemSurfaces]);
 *   4. 此刻**没有**一次 `FakeSkipGuard` 的"跳过类点击落点校验"正在进行中(见下方 ★★ 修复说明)。
 *
 * 动作: 记日志 + toast + `BACK` 退回 A; 若 BACK 没生效则用 A 的启动意图把它拉回前台。
 *
 * ★★ v108 修的两个"失效"根因(真机 09-30 之后再也拦不住, 就是这两条):
 *   ① **旧判据 ④ 太粗**: 旧代码是 `if (lastGkdActionAt >= prevSince) return`,
 *      含义是"本次前台期间 GKD 点过任何东西就让位"。可开屏时 GKD 几乎必然点过东西(跳过、关弹窗、
 *      关更新提示…), 而 `prevSince` 是"源应用成为前台"的时刻, 于是**从那一刻起的整个应用会话都被让位** ——
 *      等于本功能对"GKD 规则能生效的应用"整体失效。现在改成精确判据: 只有当 `FakeSkipGuard`
 *      **正有一次跳过类点击的落点校验在飞**(1.2s 窗口内)时才让位 —— "GKD 点了跳过、正在等落点"才归它管;
 *      十几秒前关过的弹窗不再影响本模块。
 *   ② **开屏时长写死 1.8 秒**: 真机上摇一摇广告常是"开屏页停 3~5 秒, 用户拿起手机那一刻才被晃走",
 *      超过 1.8 秒就判不出来。现在可以在设置里直接选(1.5/2/3/5/8/10/15 秒)。
 *
 * 与 `FakeSkipGuard` 的关系: 它管"GKD 点过之后落点不对", 本模块管"GKD 没在等落点却被带走";
 * 两者用上面那条"在飞就让位"的握手, 不会对同一次跳转各按一次返回键。
 */
object JumpGuard {

    private const val LOG_TAG = "JumpGuard" // 日志/共用工具(GuardUtils)里区分调用方

    /** 开屏时长的合法区间(防手改 store.json 写出离谱值) */
    private const val MIN_WINDOW_MS = 500L
    private const val MAX_WINDOW_MS = 60_000L

    /** BACK 之后的观察时间 */
    private const val BACK_WAIT = 700L

    /** 拉起原 App 之后的观察时间 */
    private const val RELAUNCH_WAIT = 900L

    /** 同一对 (A→B) 的处置冷却, 防止来回打架 */
    private const val PAIR_COOLDOWN_MS = 5000L

    /** "没在防护名单里"的候选提示日志: 同一对 (A→B) 的节流(否则每次正常跳转都刷一行) */
    private const val HINT_LOG_INTERVAL_MS = 10 * 60_000L

    /**
     * 开屏阶段的上界: 只有"应用刚打开 [ENTRY_LIMIT_MS] 之内"才允许用**页面**计时。
     *
     * 为什么必须加这个上界: 页面计时是"页面一换就重新上膛", 若不加限, 用户在名单应用里逛了十分钟、
     * 换了个页面后 8 秒内主动点了跳转, 也会被拽回去(2026-10-02 真机测试就复现过一次:
     * 校园卡 Index 页出现后 1.46s 我 adb 主动跳设置 → 被拦)。有了上界, 同一个应用开着很久之后
     * 就不再干预 —— 而摇一摇广告只发生在开屏那几秒, 所以这样既准又不烦人。
     */
    private const val ENTRY_LIMIT_MS = 60_000L

    /**
     * "源页面本身像开屏/广告页"时用的**兜底时长** —— 从这种页面跳走，几乎只可能是广告干的。
     *
     * 依据(2026-10-02 真机 `gkd-20261002.log`): 完美校园的开屏页 `...basebusiness.SplashActivity`
     * 上发生了两次跳到**百度网盘**(gap 6.3s / 5.1s), 而用户当时把「开屏时长」设成了 3 秒 → 两次都漏。
     * 光靠"用户自己把时长调大"不可靠(没人知道自家的广告是几秒), 所以对**开屏/广告页**这种强特征直接放宽:
     * 只要还在开屏阶段([ENTRY_LIMIT_MS] 内)从这种页面跳走, 就按 [SPLASH_PAGE_WINDOW_MS] 判。
     */
    private const val SPLASH_PAGE_WINDOW_MS = 15_000L

    // 页面类名像不像"开屏/广告页"的判据 v121 起移到 GuardUtils.isSplashLikePage(与 ShakeGuard 共用同一份,
    // 免得同一页面在一个模块里算开屏页、在另一个模块里不算), 调用点见 effectiveWindow()。
    // SPLASH_PAGE_WINDOW_MS 仍留在这里: 它是"从开屏页跳走时放宽到多久"这个策略, 只属于本模块。

    @Volatile private var curPkg: String? = null
    @Volatile private var curActivity: String? = null

    /** 应用(包)成为前台的时刻 */
    @Volatile private var appEntryAt = 0L

    /** 当前页面出现的时刻(开屏阶段的有效计时起点) */
    @Volatile private var pageAt = 0L

    /** 最近一次 GKD 真正点击动作的时间(由 A11yRuleEngine 调用 onGkdAction 维护), 现在只用于日志排障 */
    @Volatile private var lastGkdActionAt = 0L

    /** 最近一次"在窗口里看到摇一摇提示词"的时间(由 ShakeGuard 调用 noteShakeEvidence 维护), 仅用于日志/文案 */
    @Volatile private var shakeEvidenceAt = 0L

    /**
     * 我们自己刚把用户退回 A —— 紧接着发生的那次 B→A 切换**不能再当成一次跳转**,
     * 否则会形成 "退回A → 判成 A 被快速跳走 → 再按返回" 的死循环(退到桌面)。
     */
    @Volatile private var suppressReturnPkg: String? = null
    @Volatile private var suppressReturnUntil = 0L

    private val lastHandleAt = ConcurrentHashMap<String, Long>()
    private val lastHintLogAt = ConcurrentHashMap<String, Long>()
    private val toastedPairs = ConcurrentHashMap.newKeySet<String>()

    /** 当前生效的开屏时长(毫秒) */
    fun windowMs(): Long = storeFlow.value.jumpGuardWindowMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)

    // ---------------- 对外 ----------------

    /** GKD 执行了一次真实点击动作(由 A11yRuleEngine 在 addActionLog 之后调用) */
    fun onGkdAction() {
        lastGkdActionAt = System.currentTimeMillis()
    }

    /**
     * 最近一次 GKD 规则动作距今多久(毫秒); 进程内还没有过动作时返回 -1。
     * 供 [ShakeGuard] 用: 规则刚点过就别再补一刀(避免同一个"跳过/关闭"按钮被两处各点一次)。
     */
    fun lastActionAgoMs(now: Long = System.currentTimeMillis()): Long =
        if (lastGkdActionAt <= 0L) -1L else now - lastGkdActionAt

    /** ShakeGuard 在某个窗口里看到了摇一摇/转动手机类提示词 —— 说明这次跳转大概率就是摇一摇广告 */
    fun noteShakeEvidence() {
        shakeEvidenceAt = System.currentTimeMillis()
    }

    /** 用户设定的"跳转防护应用"(默认为空 = 不拦截任何应用) */
    fun guardAppIds(): Set<String> = jumpGuardAppListFlow.value

    /** 只跟踪前台包/页变化, 开销极小(仅 TYPE_WINDOW_STATE_CHANGED) */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isEmpty()) return
        // ★★ fok0021 修复「系统界面的瞬时窗口冲掉源应用 → 窗口内跳转 100% 漏拦」:
        //   com.android.systemui 会频繁插入 TYPE_WINDOW_STATE_CHANGED(状态栏/通知/转场/厂商悬浮窗),
        //   它**不代表用户去了别的地方**。旧代码会让它覆盖 curPkg/pageAt/appEntryAt, 于是紧随其后的
        //   那次真正的跨应用跳转被当成 "systemui -> B" 评估 —— 而 evaluate() 对系统界面是直接 return,
        //   **连一行日志都不会留**, 表现就是"功能像是没生效"。
        //   实测(Lenovo TB710FU / Android 16 / fok0020, 2026-10-02): 便签页出现后 1.25s 插入一次
        //   systemui 事件 ⇒「便签 → 设置」的窗口内快跳连续 3 次全部漏拦(名单里确实有 com.zui.notes);
        //   该机 activity_log 显示 systemui 瞬时窗口在同一分钟内可出现 4 次 ⇒ 真机上表现为"偶发失效"。
        //   fok0022 起这条判据扩展到**所有**瞬时/浮层窗口(厂商转场动画、系统插件、无启动入口的系统包),
        //   见 [SystemSurfaces.isTransientSurface] —— 判据不再只认"包名等于 com.android.systemui"。
        if (SystemSurfaces.isTransientSurface(pkg)) return
        // ★★ fok0022 修复「用户上滑到面板/回桌面被当成跳转 → 误按返回键」:
        //   真机(vivo V2238A)`gkd-20261002.log` 里 `JumpGuard jump pkg=... -> com.vivo.upslide`
        //   出现 **7 次**(gap 174~1609ms), 还有 `-> com.vivo.hiboard`(负一屏) —— 那都是**用户自己的
        //   手势**, 而本模块把它判成"摇一摇广告把我带到别的应用", 于是按返回键 + 把原应用拉回前台
        //   (用户看到的就是"上拉到控制面板时突然触发了什么东西")。
        //   它们的真实含义是"**用户已经离开当前应用**": 所以既不能当跳转目标(上面那条已挡住),
        //   也要**结束本次开屏计时** —— 否则用户从面板回到应用后, 再打开别的 App 会被当成开屏跳转拽回来。
        if (SystemSurfaces.isUserLeftSurface(pkg)) {
            clearSource()
            return
        }
        val activity = event.className?.toString()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val prev = curPkg
        // ★ 源页面取 GKD 自己维护的 topActivityFlow: 本模块在 A11yService 里跑在规则引擎**之前**,
        //   此刻它还是"上一个页面"(正是我们要判的跳转来源)。为什么不用 event.className:
        //   真机实测(vivo/完美校园)开屏页那一次事件的 className 是**空的**, GKD 靠 fixAppId 才解析出
        //   `...basebusiness.SplashActivity` —— 只信 className 会让"开屏页"这个强信号丢掉(本次实测漏拦的原因)。
        val prevActivity = topActivityFlow.value.activityId?.takeIf { it.isNotEmpty() } ?: curActivity
        val sameApp = pkg == prev
        // ★ v108: 计时起点 = "**当前这个页面**出现的时刻", 不是"这个应用成为前台的时刻"。
        //   依据(真机 gkd-20261002.log): 校园卡 App 的三次广告跳转分别是开屏页出现后
        //   4.2s / 5.0s / 6.8s —— 按"应用成为前台"算还会把同应用内的换页时间一起累加(那次是 14s),
        //   1.8 秒的旧窗口一次都追不上。按"页面"算才符合用户说的"开屏时长"。
        //   上界见 ENTRY_LIMIT_MS: 只在开屏阶段这么算。
        if (sameApp) {
            if (activity == null || activity == curActivity) return // 同页面(含弹窗/内容变化)不动计时
            curActivity = activity
            pageAt = now
            return // 同应用内换页: 只重置计时, 不参与跨应用跳转判定
        }
        val prevRef = refTime(now)
        curPkg = pkg
        curActivity = activity
        appEntryAt = now
        pageAt = now
        if (prev == null || prev.isEmpty()) return // 首次观察, 没有"源应用"可比
        runCatching { evaluate(prev, prevActivity, prevRef, pkg, now) }
            .onFailure { LogUtils.d("$LOG_TAG evaluate error", it) }
    }

    /** 当前生效的计时起点(见 ENTRY_LIMIT_MS): 开屏阶段用"页面出现时刻", 之后退回"应用进入时刻"(必然已超窗口) */
    private fun refTime(now: Long): Long =
        if (now - appEntryAt <= ENTRY_LIMIT_MS) maxOf(appEntryAt, pageAt) else appEntryAt

    /**
     * 用户离开了当前应用(回桌面 / 上滑面板 / 负一屏 / 打开 GKD 自己) → **结束本次开屏计时**。
     *
     * 下一次真实应用进入时源为空, 于是"用户从面板/桌面再打开别的 App"不会被判成开屏跳转拽回来
     * (这正是 fok0022 修的误触路径之一)。注意: 瞬时浮层(状态栏/通知/转场)**不会**走到这里 ——
     * 它们必须保留源应用, 否则会重演 fok0021 的"窗口内跳转 100% 漏拦"。
     */
    private fun clearSource() {
        curPkg = null
        curActivity = null
        appEntryAt = 0L
        pageAt = 0L
    }

    /** 这个时长是否该判: 用户设定值 vs 开屏/广告页的兜底值(见 SPLASH_PAGE_WINDOW_MS) */
    private fun effectiveWindow(prevActivity: String?): Long {
        val base = windowMs()
        // isSplashLikePage 是 GuardUtils 里的顶层函数(v121 起与 ShakeGuard 共用同一份判据)
        return if (isSplashLikePage(prevActivity)) maxOf(base, SPLASH_PAGE_WINDOW_MS) else base
    }

    // ---------------- 内部 ----------------

    private fun evaluate(
        prevPkg: String,
        prevActivity: String?,
        prevRef: Long,
        newPkg: String,
        now: Long,
    ) {
        if (!storeFlow.value.jumpGuard) return
        // 刚被我们退回来的那次切换 → 放过(防"退回A又被判成跳转再按返回"的死循环)
        if (newPkg == suppressReturnPkg && now < suppressReturnUntil) {
            suppressReturnPkg = null
            return
        }
        // 跳到桌面/系统界面/上滑面板 → 用户自己的操作(Home、上滑、负一屏), 不抢返回键
        // (fok0022 起判据换成共用的 SystemSurfaces, 不再只认 launcher/systemui 两个包名)
        if (SystemSurfaces.isSystemSurface(newPkg)) return
        // ★ v121: 相机/相册/文件选择器/输入法 = **用户主动发起的意图目标**, 不是"广告把我带走了"。
        //   取证: 20:52:31 `千问 -> com.baidu.input_vivo`(点开键盘)被按了返回键; 用户视角就是"键盘一闪没了"。
        //   广告落地页从来不是相机/相册/选择器, 所以这个排除几乎不损失拦截能力。
        if (SystemSurfaces.isUserIntentTarget(newPkg)) return
        // ① 必须发生在"刚打开"的窗口内(时长由用户设定; 源页面像开屏/广告页时用兜底值, 见 effectiveWindow)
        val window = effectiveWindow(prevActivity)
        val gap = now - prevRef
        if (gap > window) return
        // ② 只对用户勾选的应用生效(默认为空 → 什么都不做)
        if (!guardAppIds().contains(prevPkg)) {
            // 窗口内的跨应用跳转才算可疑 —— 顺手给用户一条"该把谁加进名单"的线索(按 A→B 节流)
            hintNotGuarded(prevPkg, newPkg, gap, now)
            return
        }
        // ③ ★ v108: 只有 FakeSkipGuard 正有一次"跳过类点击落点校验"在飞时才让位(见类注释 ★★)
        if (FakeSkipGuard.isVerifyingSkipClick(now)) return
        val pairKey = "$prevPkg->$newPkg"
        if (now - (lastHandleAt[pairKey] ?: 0L) < PAIR_COOLDOWN_MS) return
        lastHandleAt[pairKey] = now

        val shakeSeen = shakeEvidenceAt >= prevRef
        LogUtils.d(
            "$LOG_TAG jump pkg=$prevPkg -> $newPkg gap=${gap}ms window=${window}ms page=${prevActivity?.substringAfterLast('.') ?: "?"} shake=$shakeSeen gkdClickAgo=${if (lastGkdActionAt > 0) now - lastGkdActionAt else -1}ms reason=guarded-app, send BACK"
        )
        handle(prevPkg, newPkg, shakeSeen)
    }

    /**
     * 窗口内的跨应用跳转, 但源应用不在防护名单里 → 常见于"用户自己点了跳转"(正常操作), 所以**不动作**;
     * 但记一条节流日志, 让用户能据此把"确实会摇一摇跳转"的应用加进名单(否则这个功能永远是黑盒)。
     */
    private fun hintNotGuarded(prevPkg: String, newPkg: String, gap: Long, now: Long) {
        // 从桌面/系统界面/自己切过去属于正常打开应用, 不算候选
        // (fok0022: 系统组件之间的切换 —— 例如 `com.vivo.upslide -> com.vivo.daemonService` ——
        //  以前会刷一堆"把它加进名单即可"的假线索, 会误导用户把系统组件加进防护名单)
        if (SystemSurfaces.isSystemSurface(prevPkg) || SystemSurfaces.isSystemSurface(newPkg)) return
        // 刚处置过(含我们把用户拉回原应用之后的那一串转场) → 别把自家动作记成线索
        // (真机踩过: 我们 relaunch 回完美校园后, "百度网盘 -> 完美校园" 被记成 not-guarded, 会误导用户去加百度网盘)
        if (now < suppressReturnUntil) return
        val key = "$prevPkg->$newPkg"
        if (now - (lastHintLogAt[key] ?: 0L) < HINT_LOG_INTERVAL_MS) return
        lastHintLogAt[key] = now
        LogUtils.d(
            "$LOG_TAG not-guarded pkg=$prevPkg -> $newPkg gap=${gap}ms (未在「跳转防护应用」名单里, 未拦截; 若这就是摇一摇广告, 把它加进名单即可)"
        )
    }

    private fun handle(prevPkg: String, newPkg: String, shakeSeen: Boolean) {
        appScope.launchTry(Dispatchers.Default) {
            showToastOnce(prevPkg, newPkg, shakeSeen)
            // 先登记"即将把用户退回 prevPkg", 让随后那次 B→A 切换被放过
            suppressReturnPkg = prevPkg
            suppressReturnUntil = System.currentTimeMillis() + BACK_WAIT + RELAUNCH_WAIT + 2500L
            val backed = A11yRuleEngine.performActionBack()
            delay(BACK_WAIT)
            // 与 FakeSkipGuard 同样的坑: topActivityFlow 是缓存值, 必须**新读一次**窗口包名
            // (实现见 GuardUtils.currentForegroundPkg, 两边共用同一份, 坑位说明也只需维护一处)
            val fresh = currentForegroundPkg()
            if (fresh == prevPkg) {
                LogUtils.d("$LOG_TAG back ok sent=$backed now=$fresh")
                return@launchTry
            }
            val relaunched = relaunchApp(prevPkg, LOG_TAG)
            delay(RELAUNCH_WAIT)
            // now= 是"按返回后、拉起前"的一次读取, after= 是拉起之后再读一次 —— 不是同一时刻
            LogUtils.d(
                "$LOG_TAG back missed sent=$backed now=${fresh ?: "null"} relaunch=$relaunched after=${currentForegroundPkg() ?: "null"}"
            )
        }
    }

    private fun showToastOnce(prevPkg: String, newPkg: String, shakeSeen: Boolean) {
        val key = "$prevPkg->$newPkg"
        if (!toastedPairs.add(key)) return
        val from = appLabel(prevPkg)
        val to = appLabel(newPkg)
        val text = if (shakeSeen) {
            "摇一摇拦截: 「$from」开屏期间被带到「$to」, 已退回"
        } else {
            "跳转防护: 已从「$to」退回「$from」(开屏 ${"%.1f".format(windowMs() / 1000.0)} 秒内的跳转)"
        }
        toast(text, forced = true)
    }
}
