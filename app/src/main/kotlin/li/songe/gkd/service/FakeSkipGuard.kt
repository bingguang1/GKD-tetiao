package li.songe.gkd.service

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import li.songe.gkd.META
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.a11y.TopActivity
import li.songe.gkd.a11y.launcherAppId
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.app
import li.songe.gkd.appScope
import li.songe.gkd.data.ActionResult
import li.songe.gkd.data.ResolvedRule
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.systemUiAppId
import li.songe.gkd.util.toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 免 root「假跳过防护」(v100 新增)。
 *
 * 背景: 开屏广告里的"跳过"有两种在节点属性上无法区分的形态——
 *   1. 真跳过: 文本节点自身 clickable=false, 但点击它的坐标有效(例: 学习通 com.chaoxing.mobile:id/btn_jump);
 *   2. 假跳过: "跳过"只是装饰性文字/图片(clickable=false), 下面盖着广告的可点层,
 *      点它的中心坐标 = 点广告 → 拉起浏览器/应用市场/落地页, 甚至退出当前小程序。
 * 因为两种形态都是 clickable=false, 只靠选择器(如加 [clickable=true])区分会误伤真跳过
 * (见 gkd-20260908.log: btn_jump 与微信小程序"跳过"均为 clickable=false, 但结果相反)。
 *
 * 因此本模块的判据是"点击之后落到了哪里", 分两道闸:
 *   A. 点后校验(主): 对"跳过类点击"在 ~1.2s 后校验前台包。
 *      - 前台包未变 → 正常跳过, 只记日志;
 *      - 前台变成桌面/系统界面 → 疑似被广告踢出(可能是用户自己按 Home, 故需同进程内累计 2 次才降级);
 *      - 前台变成**别的应用**(浏览器/市场/落地页) → 判定假跳过误点 → 立刻返回键退回 + 把该 App 记入降级名单。
 *   B. 点前否决(降级名单生效后): 已被判定过假跳过的 App 里, 不再自动点击"不可点的跳过类文字",
 *      只允许点真正 clickable=true 的按钮 —— 宁可不跳, 也不误点进广告。名单可在设置页一键清空。
 *
 * 与 §3.1 防摇一摇的关系: v96 曾因"找不到关闭按钮就无条件按返回键"导致微信等被误退, v97 已移除。
 * 本模块的返回键**不是兜底**, 而是"已经证实跳到了别的应用"这一确定性条件下的回退, 语义不同。
 */

private const val VERIFY_DELAY = 1200L      // 点击后多久校验落点
private const val BACK_WAIT = 600L          // 返回键之后的观察时间
private const val RELAUNCH_WAIT = 900L      // 拉起原 App 之后的观察时间
private const val COOLDOWN_MS = 4000L       // 两次处置之间的冷却
private const val VETO_MEMO_MS = 3000L      // 同一节点被否决后的记忆窗口(挡同节点的其它规则)
private const val LEFT_SYSTEM_LIMIT = 2     // "疑似被踢到桌面"累计多少次才降级该 App
private const val VETO_LOG_INTERVAL_MS = 3000L // 否决日志节流(规则匹配循环约 300ms 一轮)
private const val MAX_TEXT_LEN = 20         // 命中节点文本最大长度(排除正文长文本)

object FakeSkipGuard {

    /** 跳过类按钮的文本/desc 关键词 */
    private val skipWords = arrayOf("跳过", "跳過", "跳 过", "skip", "Skip", "SKIP")

    /** 规则组名里出现这些词时, 也认为这次点击是"跳过开屏广告"语义(故意不含泛化的"广告": 避免误伤分段广告的关闭类点击) */
    private val adGroupWords = arrayOf("开屏", "splash", "Splash", "启动广告")

    private val verifyToken = AtomicLong(0)

    @Volatile
    private var lastHandleTime = 0L

    @Volatile
    private var vetoNodeKey: String? = null

    @Volatile
    private var vetoNodeTime = 0L

    private val leftSystemCount = ConcurrentHashMap<String, Int>()
    private val toastShownAppIds = ConcurrentHashMap.newKeySet<String>()
    private val lastVetoLogTime = ConcurrentHashMap<String, Long>()

    // ---------------- 对外: 设置页用 ----------------

    /** 已被判定过假跳过、当前处于"降级"(不点不可点跳过文字)状态的应用 */
    fun vetoAppIds(): MutableSet<String> {
        return storeFlow.value.fakeSkipVetoApps.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
    }

    fun clearVetoApps() {
        storeFlow.value = storeFlow.value.copy(fakeSkipVetoApps = "")
        LogUtils.d("FakeSkipGuard clear-veto")
    }

    // ---------------- 闸 A: 点前否决 ----------------

    /**
     * 在规则真正执行动作前调用。返回 false 表示本次点击被否决(仅限"已降级 App + 不可点的跳过文字")。
     */
    fun allowAction(rule: ResolvedRule, node: AccessibilityNodeInfo): Boolean {
        if (!storeFlow.value.fakeSkipGuard) return true
        val appId = topActivityFlow.value.appId
        if (appId.isEmpty() || appId == META.appId) return true
        if (node.isClickable) return true
        if (!vetoAppIds().contains(appId)) return true
        if (!isSkipLikeTarget(rule, node)) return true
        val key = nodeKey(node)
        val now = System.currentTimeMillis()
        // 同一节点上的其它规则(如全局开屏组的多个 rule)也一并否决
        vetoNodeKey = key
        vetoNodeTime = now
        // 规则匹配是 ~300ms 一轮的循环, 否决日志做节流(否则同一节点会刷屏)
        val lastLog = lastVetoLogTime[appId] ?: 0L
        if (now - lastLog >= VETO_LOG_INTERVAL_MS) {
            lastVetoLogTime[appId] = now
            LogUtils.d(
                "FakeSkipGuard veto pkg=$appId text=${label(node)} bounds=${nodeBounds(node)}"
            )
        }
        showVetoToast(appId)
        return false
    }

    // ---------------- 闸 B: 点后校验 ----------------

    /**
     * 在规则动作执行成功后调用(引擎侧 addActionLog 之后)。
     */
    fun onActionExecuted(
        rule: ResolvedRule,
        topActivity: TopActivity,
        node: AccessibilityNodeInfo,
        actionResult: ActionResult,
    ) {
        if (!storeFlow.value.fakeSkipGuard) return
        if (!actionResult.result) return
        if (!actionResult.action.startsWith("click")) return
        val pkgBefore = topActivity.appId
        if (pkgBefore.isEmpty() || pkgBefore == META.appId) return
        if (!isSkipLikeTarget(rule, node)) return
        val key = nodeKey(node)
        val now = System.currentTimeMillis()
        // 刚被否决过的同一节点不再进入校验(理论上不会走到这里, 兜底)
        if (key == vetoNodeKey && now - vetoNodeTime < VETO_MEMO_MS) return
        val token = verifyToken.incrementAndGet()
        val activityBefore = topActivity.activityId
        appScope.launchTry(Dispatchers.Default) {
            delay(VERIFY_DELAY)
            if (token != verifyToken.get()) return@launchTry
            verify(pkgBefore, activityBefore, label(node))
        }
    }

    private suspend fun verify(pkgBefore: String, activityBefore: String?, target: String) {
        val now = System.currentTimeMillis()
        if (now - lastHandleTime < COOLDOWN_MS) return
        val after = topActivityFlow.value
        val pkgAfter = after.appId
        if (pkgAfter == pkgBefore) {
            LogUtils.d(
                "FakeSkipGuard ok pkg=$pkgBefore activity=${after.activityId} before=${activityBefore ?: ""} target=$target"
            )
            return
        }
        if (pkgAfter.isEmpty() || pkgAfter == META.appId || pkgAfter == systemUiAppId || pkgAfter == launcherAppId) {
            // 回到桌面/系统界面: 不抢返回键(可能只是用户自己按了 Home), 仅累计记录
            val n = (leftSystemCount[pkgBefore] ?: 0) + 1
            leftSystemCount[pkgBefore] = n
            LogUtils.d(
                "FakeSkipGuard left-system pkg=$pkgBefore -> ${pkgAfter.ifEmpty { "null" }} count=$n target=$target"
            )
            if (n >= LEFT_SYSTEM_LIMIT) {
                markMisclick(pkgBefore, reason = "left-system", toastEnabled = true)
                // 判定成立时才把用户带回原 App(单次可能只是用户自己按了 Home, 不抢)
                val ok = relaunchApp(pkgBefore)
                LogUtils.d("FakeSkipGuard left-system relaunch=$ok pkg=$pkgBefore")
            }
            return
        }
        // 跳到别的应用 → 假跳过误点
        lastHandleTime = now
        LogUtils.d("FakeSkipGuard misclick pkg=$pkgBefore -> $pkgAfter target=$target, send BACK")
        markMisclick(pkgBefore, reason = "to:$pkgAfter", toastEnabled = true)
        val backed = A11yRuleEngine.performActionBack()
        delay(BACK_WAIT)
        // 真机(vivo/Android16)实测: BACK 返回 true 也可能什么都没发生, 且 topActivityFlow 是**缓存值**
        // (屏幕锁了/没有新事件时会停在旧值) —— 所以这里必须用**新读一次**的窗口包名来判断
        val freshPkg = currentForegroundPkg()
        if (freshPkg == pkgBefore) {
            LogUtils.d("FakeSkipGuard back ok sent=$backed now=$freshPkg")
            return
        }
        // BACK 没把用户带回来 → 用原 App 的启动意图拉回(有 SYSTEM_ALERT_WINDOW/无障碍服务, 不受后台启动限制)
        val relaunched = relaunchApp(pkgBefore)
        delay(RELAUNCH_WAIT)
        LogUtils.d(
            "FakeSkipGuard back missed sent=$backed now=${freshPkg ?: "null"} relaunch=$relaunched after=${currentForegroundPkg() ?: "null"}"
        )
    }

    // ---------------- 内部 ----------------

    /**
     * 新读一次当前前台包名(不看缓存流)。
     * 依次尝试: 活动窗口根节点 → 各窗口里 focused/active 的那个 → 都没有就返回 null(未知, 不做动作)。
     */
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

    /** 把原 App 拉回前台(落地页误点/被踢到桌面后的兜底) */
    private fun relaunchApp(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return runCatching {
            val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            app.startActivity(intent)
            true
        }.getOrElse {
            LogUtils.d("FakeSkipGuard relaunch error pkg=$pkg", it)
            false
        }
    }

    private fun markMisclick(appId: String, reason: String, toastEnabled: Boolean) {
        if (appId.isEmpty()) return
        val list = vetoAppIds()
        if (!list.add(appId)) return
        storeFlow.value = storeFlow.value.copy(fakeSkipVetoApps = list.joinToString("\n"))
        LogUtils.d("FakeSkipGuard veto-add pkg=$appId reason=$reason total=${list.size}")
        if (toastEnabled) showVetoToast(appId)
    }

    private fun showVetoToast(appId: String) {
        if (!toastShownAppIds.add(appId)) return
        toast(
            "假跳过防护: 已停止在「${appName(appId)}」自动点击不可点的跳过文字\n(可在 设置 页恢复)",
            forced = true,
        )
    }

    private fun appName(appId: String): String {
        return runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(appId, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: appId
    }

    /** 这次点击是否属于"跳过广告"语义(节点文本像跳过, 或所在规则组名像开屏/广告) */
    private fun isSkipLikeTarget(rule: ResolvedRule, node: AccessibilityNodeInfo): Boolean {
        val text = label(node)
        if (text.isNotEmpty() && text.length <= MAX_TEXT_LEN && skipWords.any { text.contains(it) }) {
            return true
        }
        val groupName = runCatching { rule.g.group.name }.getOrNull() ?: ""
        return groupName.isNotEmpty() && adGroupWords.any { groupName.contains(it) }
    }

    private fun label(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString() ?: ""
        if (t.isNotEmpty()) return t
        return node.contentDescription?.toString() ?: ""
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): String {
        return runCatching {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            "[${r.left},${r.top}][${r.right},${r.bottom}]"
        }.getOrDefault("[]")
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String = "${nodeBounds(node)}@${label(node)}"
}
