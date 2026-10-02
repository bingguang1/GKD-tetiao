package li.songe.gkd.service

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.appScope
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.toast
import java.util.concurrent.ConcurrentHashMap

/**
 * fork(v107): 「关闭快应用」的**无障碍层**(默认开启, 零权限)。
 *
 * 场景: 宿主 App 的开屏广告里藏着 `hap://app/<快应用>` deeplink, 一点(或摇一摇)就把用户
 *   拉进**快应用引擎**的广告页, 接着在快应用里自动下载 APK —— 这类广告没有可点的"跳过",
 *   所以"找按钮点跳过"的老思路在这里完全无效; 能做的只有"**别让它留在前台**"。
 *
 * 判据(极简且几乎零误伤): **当前台从 A 变成"快应用引擎"** 就判为流氓跳转。
 *   - 正常用户不会"跳进"快应用引擎(从桌面/负一屏主动打开快应用中心的那条路已被排除);
 *   - 不要求"1.8 秒窗口"、不要求"GKD 没点过"(见 [JumpGuard] 的两条判据) —— 因为引擎本身就是强特征:
 *     即使 GKD 的规则点到了假跳过按钮而把快应用拉起来, 这里同样应该把它退回去。
 *
 * 动作: toast + `BACK` 退回原应用; 若 BACK 没生效(真机实测 BACK 返回 true 也可能什么都没发生),
 *   再用原应用的启动意图把它拉回前台 —— 这两步复用 [FakeSkipGuard]/[JumpGuard] 同款工具
 *   ([currentForegroundPkg]/[relaunchApp], 见 GuardUtils.kt, 坑位说明只有一份)。
 *
 * 与 [QuickAppController] 的分工: 本模块负责"秒退"(任何设备都能用), 控制器负责"根治"
 *   (停用引擎 + 掐掉引擎的安装应用权限, 需要 Shizuku/一键 ADB)。
 */
object QuickAppGuard {

    private const val LOG_TAG = "QuickApp"

    /** BACK 之后的观察时间 */
    private const val BACK_WAIT = 700L

    /** 拉起原应用之后的观察时间 */
    private const val RELAUNCH_WAIT = 900L

    /** 同一对 (原应用 → 引擎) 的处置冷却, 防止持续打架 */
    private const val PAIR_COOLDOWN_MS = 4000L

    /** 诊断日志("看到引擎")的节流 */
    private const val SEEN_LOG_INTERVAL_MS = 5000L

    /** 当前前台包(只跟踪 TYPE_WINDOW_STATE_CHANGED, 开销极小) */
    @Volatile
    private var curPkg: String? = null

    private val lastHandleAt = ConcurrentHashMap<String, Long>()

    /**
     * ★ fok0024 诊断日志: 每次"看到快应用引擎"都记一条(按"来源→引擎"节流)。
     *
     * 为什么加它: 真机排查时"引擎起来了但没被拦"完全是个黑盒 —— 到底是开关关了、来源被判成系统界面、
     * 还是这次是从桌面主动打开的, 从日志里一个字都看不出来(本次在联想平板上就卡在这里)。
     * 现在日志会直接给出: `prev=... guardOn=true prevIsSystemSurface=false engines=2`, 一眼定位。
     */
    private val lastSeenLogAt = ConcurrentHashMap<String, Long>()

    /** UI 展示用: 累计拦截次数 / 最近一次拦截 */
    val blockCountFlow = MutableStateFlow(0)
    val lastBlockFlow = MutableStateFlow("")

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isEmpty()) return
        // ★★ fok0023: **引擎判定必须排在浮层判定之前**。
        //   快应用引擎是系统包, **通常没有桌面启动入口**(vivo 的 com.vivo.hybrid / com.vivo.vhome 就是),
        //   而 [SystemSurfaces] 的通用判据恰恰是"没有启动入口 ⇒ 系统浮层" ⇒ 若先走浮层判定就会被直接
        //   return(且不改状态), 于是本模块的"秒退"变成**永不触发** —— 这是 fok0022 引入的回归, fok0023 修掉。
        val isEngine = QuickAppRegistry.isEngine(pkg)
        // 非引擎的瞬时/浮层窗口(状态栏/通知/转场/厂商系统服务)不参与"来源→目标"判定
        // (与 JumpGuard 同一条纪律, 见 SystemSurfaces)
        if (!isEngine && SystemSurfaces.isTransientSurface(pkg)) return
        val prev = curPkg
        if (pkg == prev) return // 同一应用内换 Activity 不算跨应用跳转
        if (isEngine) logEngineSeen(pkg, prev)
        curPkg = pkg
        if (!storeFlow.value.quickAppGuard) return
        if (prev == null || prev.isEmpty()) return // 首次观察, 没有"来源应用"可比
        // ★ 核心判据: 目标是快应用引擎
        if (!isEngine) return
        // 引擎内部换 Activity(同一个引擎包)不算
        if (QuickAppRegistry.isEngine(prev)) return
        // 从桌面/上滑面板/负一屏/系统界面进入 → 用户主动打开(如负一屏的快应用中心), 不拦
        // ★ fok0024: 这里用**只看显式清单**的判据, 不用"没有启动入口"那条启发式 —— 否则
        //   在"相机/图库/文件管理"这类**没有桌面图标的真实应用**里被广告拉进快应用时, 会被当成
        //   "从系统界面进入"而静默放过(真机实测这几个包在联想平板上都没有 LAUNCHER 入口)。
        if (SystemSurfaces.isExplicitSystemSurface(prev)) return
        val now = System.currentTimeMillis()
        val pairKey = "$prev->$pkg"
        if (now - (lastHandleAt[pairKey] ?: 0L) < PAIR_COOLDOWN_MS) return
        lastHandleAt[pairKey] = now
        runCatching { handle(prev, pkg) }
            .onFailure { LogUtils.d("$LOG_TAG handle error", it) }
    }

    /** 见 [lastSeenLogAt] 的说明: 把"看到引擎但没拦"的原因直接写进日志 */
    private fun logEngineSeen(enginePkg: String, prev: String?) {
        val key = "${prev ?: "-"}->$enginePkg"
        val now = System.currentTimeMillis()
        if (now - (lastSeenLogAt[key] ?: 0L) < SEEN_LOG_INTERVAL_MS) return
        lastSeenLogAt[key] = now
        LogUtils.d(
            "$LOG_TAG seen engine=$enginePkg prev=${prev ?: "null"} guardOn=${storeFlow.value.quickAppGuard} " +
                "prevIsSystemSurface=${if (prev == null) "n/a" else SystemSurfaces.isExplicitSystemSurface(prev)} " +
                "engines=${QuickAppRegistry.enginesFlow.value.size}"
        )
    }

    private fun handle(prevPkg: String, enginePkg: String) {
        val engineLabel = appLabel(enginePkg)
        val backLabel = appLabel(prevPkg)
        blockCountFlow.update { it + 1 }
        lastBlockFlow.value = "$engineLabel → $backLabel"
        LogUtils.d("$LOG_TAG block pkg=$prevPkg -> $enginePkg engine=$engineLabel, send BACK")
        appScope.launchTry(Dispatchers.Default) {
            toast("快应用拦截: 已从「$engineLabel」退回「$backLabel」", forced = true)
            val backed = A11yRuleEngine.performActionBack()
            delay(BACK_WAIT)
            // 与其它守卫同样的坑: topActivityFlow 是缓存值, 必须新读一次窗口包名
            val fresh = currentForegroundPkg()
            if (fresh == prevPkg) {
                LogUtils.d("$LOG_TAG back ok sent=$backed now=$fresh")
                return@launchTry
            }
            val relaunched = relaunchApp(prevPkg, LOG_TAG)
            delay(RELAUNCH_WAIT)
            LogUtils.d(
                "$LOG_TAG back missed sent=$backed now=${fresh ?: "null"} relaunch=$relaunched after=${currentForegroundPkg() ?: "null"}"
            )
        }
    }
}
