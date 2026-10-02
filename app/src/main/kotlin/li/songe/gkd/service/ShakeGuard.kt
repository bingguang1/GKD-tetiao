package li.songe.gkd.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import li.songe.gkd.META
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils

/**
 * 免 root「防摇一摇广告 / 开屏自动关闭」内置防护。
 *
 * 做什么: 应用刚打开(或刚换到开屏页)的**开屏时长**内, 若屏幕上出现**真正可点**的
 *   "跳过 / 关闭 / 知道了"按钮, 就替你点掉它 —— 在摇一摇把页面晃走之前先把广告关掉。
 *
 * ★★ v109 重做(真机实测驱动, 原来这条基本是死的):
 *   取证: 4 天日志里 `ShakeGuard handled` **0 次**(skip 1225~1345 次/天), 且 `shake=true` 每天只有 0~1 次。
 *   实测完美校园开屏页(uiautomator dump): 1.2s 时页面上什么都没有, **3.2s 才出现真的可点按钮
 *   `text="0S | 跳过"`(clickable=true)**, 6s 页面就没了; **整棵树里没有任何"摇一摇"字样**。
 *   ⇒ 旧版两条前置条件在真机上一起把人卡死: ①窗口只有 2 秒(按钮 3.2s 才出现); ②强制要求先看到
 *      "摇一摇/转动手机"提示词(广告里那行字是图片/动画, 无障碍树里根本没它)。
 *   改法:
 *     ① 窗口改用**共用的「开屏时长」**(设置页那一个值, 默认 8 秒), 且**同应用内换页也重置窗口**
 *        (开屏页往往在主页面之后才出现);
 *     ② **不再强制**要求摇一摇提示词 —— 只要窗口内有关键词命中且**可点+可用**的按钮就点;
 *        提示词若真看到了, 仍记给 [JumpGuard] 当"高置信证据"(日志里带 shakeHint=);
 *     ③ 规则引擎刚点过(RULE_ACTED_RECENT_MS 内)就不补刀, 避免同一个按钮被两处各点一次;
 *     ④ 只做**节点点击**(不做坐标点击、不做返回键兜底) —— 只碰"自己写着跳过/关闭/知道了"的可点控件,
 *        所以不会重蹈"假跳过按坐标点到广告上"的坑(那类不可点节点 ShakeGuard 一律不碰)。
 *
 * 与其它模块的分工: 本模块**事前**把广告关掉; 已经被晃走的情况由 [JumpGuard] 事后退回;
 *   [FakeSkipGuard] 负责"规则点过之后落点不对"的回退。三者互不重叠。
 */
object ShakeGuard {

    // 摇一摇/传感器广告的常见提示词(只用于记录"高置信证据", 不再是动手的必要条件)
    private val keywords = arrayOf(
        "摇一摇", "摇一摇得", "摇动手机", "晃动手机", "转动手机",
        "转一转", "左右摇摆", "摇摆手机", "摇一摇有奖",
    )

    // 关闭按钮候选文本/desc(包含匹配, 优先级从高到低: 跳过 > 关闭 > 知道了)
    private val closeCandidates = arrayOf("跳过", "关闭", "知道了")

    private const val SCAN_MIN_INTERVAL = 300L      // 两次扫描最小间隔
    private const val MAX_NODES = 2500              // 单次遍历节点上限(防大窗口卡顿)
    private const val MAX_TEXT_LEN = 20             // 命中节点文本最大长度(排除长文本内容)
    private const val RULE_ACTED_RECENT_MS = 1500L  // 规则刚点过就别补刀
    private const val SKIP_LOG_INTERVAL_MS = 3000L  // skip 日志节流(窗口变长后扫描次数变多)

    @Volatile
    private var openPkg: String? = null
    @Volatile
    private var openActivity: String? = null
    @Volatile
    private var openAt = 0L
    @Volatile
    private var handled = false
    @Volatile
    private var lastScan = 0L
    @Volatile
    private var lastSkipLog = 0L

    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (event == null) return
        if (!storeFlow.value.shakeGuard) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == META.appId) return
        val activity = event.className?.toString()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        if (openPkg != pkg) {
            openPkg = pkg
            openActivity = activity
            openAt = now
            handled = false
            SensorOrientationGuard.onAppOpen(pkg)
        } else if (activity != null && activity != openActivity) {
            // v109: 同应用内换页也重置窗口(开屏页常在主页面之后才出现; 真机上按钮 3.2s 才出现)
            openActivity = activity
            openAt = now
            handled = false
        }
        if (handled) return
        val windowMs = JumpGuard.windowMs()
        if (now - openAt > windowMs) return
        if (now - lastScan < SCAN_MIN_INTERVAL) return
        val root = service.rootInActiveWindow ?: run {
            // 拿不到窗口时**不占用**这次扫描配额: 真机(vivo)在转场瞬间 root 常为 null,
            // 若照旧写入 lastScan 就要再等 SCAN_MIN_INTERVAL 才重试, 开屏那几秒很容易被浪费掉
            logSkip("no-root")
            return
        }
        lastScan = now
        try {
            // 注意: 不 recycle rootInActiveWindow —— 它由系统与 GKD 规则引擎共用, 主动回收可能让
            // 同一次事件里的规则匹配拿到已回收的节点(旧版是回收的, 本次收紧为只回收自己收集的子节点)
            scanAndHandle(root, windowMs)
        } catch (e: Throwable) {
            LogUtils.d("ShakeGuard", e)
        }
    }

    /** 节点文本是否像一句"短提示"(过长的正文/聊天内容不参与命中) */
    private fun shortText(t: String): Boolean = t.isNotEmpty() && t.length <= MAX_TEXT_LEN

    private fun scanAndHandle(root: AccessibilityNodeInfo, windowMs: Long) {
        var shakeFound = false
        var bestClose: AccessibilityNodeInfo? = null
        var bestClosePriority = -1
        var bestCloseLabel = ""
        val children = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        var visited = 0
        stack.add(root)
        try {
            while (stack.isNotEmpty() && visited < MAX_NODES) {
                val node = stack.removeLast()
                visited++
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                if (!shakeFound &&
                    (shortText(text) && keywords.any { text.contains(it) } ||
                        shortText(desc) && keywords.any { desc.contains(it) })
                ) {
                    shakeFound = true
                }
                // 关闭按钮候选: **必须可点击且可用**(只做节点点击, 绝不打坐标 —— 那才是假跳过的坑)
                if (node.isClickable && node.isEnabled) {
                    val cand = text.ifEmpty { desc }
                    for ((idx, kw) in closeCandidates.withIndex()) {
                        if (cand.length in 1..MAX_TEXT_LEN && cand.contains(kw)) {
                            if (idx > bestClosePriority) {
                                bestClosePriority = idx
                                bestClose = node
                                bestCloseLabel = cand
                            }
                            break
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        children.add(child)
                        stack.add(child)
                    }
                }
            }
            // 摇一摇提示词若真看到了: 给 JumpGuard 留"高置信证据"(不再是本模块动手的前提)
            if (shakeFound) JumpGuard.noteShakeEvidence()
            val target = bestClose
            if (target == null) {
                logSkip("no-close-button shakeHint=$shakeFound nodes=$visited")
                return
            }
            // 规则引擎刚点过 → 不补刀(避免同一个按钮被两处各点一次)
            val ruleAgo = JumpGuard.lastActionAgoMs()
            if (ruleAgo in 0..RULE_ACTED_RECENT_MS) {
                logSkip("rule-acted-${ruleAgo}ms shakeHint=$shakeFound close=$bestCloseLabel")
                return
            }
            val clicked = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (clicked) {
                handled = true
                LogUtils.d(
                    "ShakeGuard handled pkg=$openPkg via click=$bestCloseLabel shakeHint=$shakeFound window=${windowMs}ms nodes=$visited"
                )
            } else {
                // 点击未生效则不再做任何兜底(不打坐标、不按返回键), 保持安全
                logSkip("click-failed close=$bestCloseLabel nodes=$visited")
            }
        } finally {
            children.forEach { n ->
                runCatching { n.recycle() }
            }
        }
    }

    /** skip 日志节流: 窗口从 2 秒放宽到"开屏时长"后, 扫描次数明显变多, 不节流会刷屏 */
    private fun logSkip(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastSkipLog < SKIP_LOG_INTERVAL_MS) return
        lastSkipLog = now
        LogUtils.d("ShakeGuard skip pkg=$openPkg reason=$reason")
    }
}
