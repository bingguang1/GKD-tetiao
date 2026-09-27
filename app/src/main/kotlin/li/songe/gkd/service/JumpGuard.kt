package li.songe.gkd.service

import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import li.songe.gkd.META
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.a11y.launcherAppId
import li.songe.gkd.app
import li.songe.gkd.appScope
import li.songe.gkd.store.jumpGuardAppListFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.systemUiAppId
import li.songe.gkd.util.toast
import java.util.concurrent.ConcurrentHashMap

/**
 * 免 root「摇一摇跳转防护」(fork v105 新增, v106 改为**按应用设定**)。
 *
 * 为什么需要它(A→B 两条老路都走不通):
 *   1. `ShakeGuard` 是"看到摇一摇提示 **且** 有可点关闭按钮才点"的事后补救 —— 广告一摇就跳、
 *      根本不给按钮时无效;
 *   2. v104 想从根上掐掉传感器(把该应用的「获取设备方向」appop 置 ignore), 但 2026-09-26 真机取证
 *      证明 **vivo 上根本没有这个 appop**(设备 78 个 op 里只有 BODY_SENSORS 心率那个, 见交接文档 §10.5),
 *      该路线在本 ROM 不成立。
 *   → 于是改成"事后拦截跳转": 这是本 ROM 上唯一可验证可行的方向。
 *
 * ★ v106 起**只对用户勾选的应用生效**(设置 → 摇一摇跳转防护 → 跳转防护应用):
 *   列表**默认为空 = 本功能不做任何拦截**, 由用户自行添加。未勾选的应用一律不介入,
 *   所以不会影响"正常 App 打开后自己跳浏览器/支付宝"(登录/支付/分享)这类流程 ——
 *   用户如果发现某个 App 的正常跳转被拦了, 取消勾选即可。
 *
 * 判据(全部成立才动作):
 *   1. **源应用 A 在用户的"跳转防护应用"名单里**;
 *   2. A 在前台的停留时间 <= [WINDOW_MS](开屏很短的窗口内);
 *   3. 前台从 A 切到了**别的应用 B**, 且 B 不是桌面/系统界面/GKD 自己;
 *   4. 这段窗口内 **GKD 一次点击动作都没做过** —— 有 GKD 点击时属于 `FakeSkipGuard` 的职责
 *      (点后落点校验), 两边互不重叠、不抢返回键。
 *
 * 动作: 记日志 + toast + `BACK` 退回 A; 若 BACK 没生效则用 A 的启动意图把它拉回前台。
 *
 * 与 `FakeSkipGuard` 的关系: 它管"GKD 点过之后落点不对", 本模块管"GKD 完全没动手却被带走",
 * 两者是互补的; 因为都要求"窗口内无 GKD 动作 / 有 GKD 动作", 所以同一场景不会被两只手同时处置。
 */
object JumpGuard {

    /** 源应用在前台多久之内发生的跨应用跳转才算"开屏跳转"(越短越准, 也越不容易误伤用户自己点开的跳转) */
    private const val WINDOW_MS = 1800L

    /** BACK 之后的观察时间 */
    private const val BACK_WAIT = 700L

    /** 拉起原 App 之后的观察时间 */
    private const val RELAUNCH_WAIT = 900L

    /** 同一对 (A→B) 的处置冷却, 防止来回打架 */
    private const val PAIR_COOLDOWN_MS = 5000L

    @Volatile private var curPkg: String? = null
    @Volatile private var curSince = 0L

    /** 最近一次 GKD 真正点击动作的时间(由 A11yRuleEngine 调用 onGkdAction 维护) */
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
    private val toastedPairs = ConcurrentHashMap.newKeySet<String>()

    // ---------------- 对外 ----------------

    /** GKD 执行了一次真实点击动作(由 A11yRuleEngine 在 addActionLog 之后调用) */
    fun onGkdAction() {
        lastGkdActionAt = System.currentTimeMillis()
    }

    /** ShakeGuard 在某个窗口里看到了摇一摇/转动手机类提示词 —— 说明这次跳转大概率就是摇一摇广告 */
    fun noteShakeEvidence() {
        shakeEvidenceAt = System.currentTimeMillis()
    }

    /** 用户设定的"跳转防护应用"(默认为空 = 不拦截任何应用) */
    fun guardAppIds(): Set<String> = jumpGuardAppListFlow.value

    /** 只跟踪前台包变化, 开销极小(仅 TYPE_WINDOW_STATE_CHANGED) */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isEmpty() || pkg == META.appId) return
        val now = System.currentTimeMillis()
        val prev = curPkg
        if (pkg == prev) return // 同一应用内换 Activity 不算跨应用跳转
        val prevSince = curSince
        curPkg = pkg
        curSince = now
        if (prev == null || prev.isEmpty()) return // 首次观察, 没有"源应用"可比
        runCatching { evaluate(prev, prevSince, pkg, now) }
            .onFailure { LogUtils.d("JumpGuard evaluate error", it) }
    }

    // ---------------- 内部 ----------------

    private fun evaluate(prevPkg: String, prevSince: Long, newPkg: String, now: Long) {
        if (!storeFlow.value.jumpGuard) return
        // ① 只对用户勾选的应用生效(默认为空 → 什么都不做)
        if (!guardAppIds().contains(prevPkg)) return
        // 刚被我们退回来的那次切换 → 放过(防"退回A又被判成跳转再按返回"的死循环)
        if (newPkg == suppressReturnPkg && now < suppressReturnUntil) {
            suppressReturnPkg = null
            return
        }
        // 跳到桌面/系统界面 → 可能是用户自己按了 Home, 不抢返回键(交给 FakeSkipGuard 的累计逻辑)
        if (newPkg == launcherAppId || newPkg == systemUiAppId) return
        // ② 必须发生在"刚打开"的窗口内
        val gap = now - prevSince
        if (gap > WINDOW_MS) return
        // ③ 窗口内 GKD 点过 → 属于 FakeSkipGuard 的"点后落点校验", 本模块不介入
        if (lastGkdActionAt >= prevSince) return
        val pairKey = "$prevPkg->$newPkg"
        if (now - (lastHandleAt[pairKey] ?: 0L) < PAIR_COOLDOWN_MS) return
        lastHandleAt[pairKey] = now

        val shakeSeen = shakeEvidenceAt >= prevSince
        LogUtils.d(
            "JumpGuard jump pkg=$prevPkg -> $newPkg gap=${gap}ms shake=$shakeSeen reason=guarded-app, send BACK"
        )
        handle(prevPkg, newPkg, shakeSeen)
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
            val fresh = currentForegroundPkg()
            if (fresh == prevPkg) {
                LogUtils.d("JumpGuard back ok sent=$backed now=$fresh")
                return@launchTry
            }
            val relaunched = relaunchApp(prevPkg)
            delay(RELAUNCH_WAIT)
            LogUtils.d(
                "JumpGuard back missed sent=$backed now=${fresh ?: "null"} relaunch=$relaunched after=${currentForegroundPkg() ?: "null"}"
            )
        }
    }

    private fun showToastOnce(prevPkg: String, newPkg: String, shakeSeen: Boolean) {
        val key = "$prevPkg->$newPkg"
        if (!toastedPairs.add(key)) return
        val from = appName(prevPkg)
        val to = appName(newPkg)
        val text = if (shakeSeen) {
            "摇一摇拦截: 「$from」开屏期间被带到「$to」, 已退回"
        } else {
            "跳转防护: 已从「$to」退回「$from」(开屏 1.8 秒内的跳转)"
        }
        toast(text, forced = true)
    }

    private fun appName(appId: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(appId, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: appId

    /** 新读一次当前前台包名(不看缓存流); 都拿不到就返回 null(未知 → 不做动作) */
    private fun currentForegroundPkg(): String? {
        runCatching {
            A11yService.instance?.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            A11yRuleEngine.compatWindows()
                .firstOrNull { w -> runCatching { w.isFocused || w.isActive }.getOrDefault(false) }
                ?.root?.packageName?.toString()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 把原 App 拉回前台 */
    private fun relaunchApp(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return runCatching {
            val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            app.startActivity(intent)
            true
        }.getOrElse {
            LogUtils.d("JumpGuard relaunch error pkg=$pkg", it)
            false
        }
    }
}
