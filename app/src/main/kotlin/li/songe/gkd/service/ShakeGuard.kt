package li.songe.gkd.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
 *
 * ★★ fok0022 修「在系统操作面板上误触」(用户报: "上拉到控制面板时会触发东西, 关掉 GKD 就不触发"):
 *   真机取证(vivo V2238A / `gkd-20261002.log` + 控制面板界面树) 说明旧版有三条通道会点到面板:
 *     ① **事件包名没过滤系统界面**: 面板的 `TYPE_WINDOW_STATE_CHANGED` 一样会进来, 于是"开屏窗口"
 *        被面板刷新, 面板里的节点参与选择器匹配 ⇒ 一天 40+ 次 `handled pkg=com.android.systemui via click=关闭`;
 *     ② **树和事件可能不是同一个窗口**: 事件来自应用 A、而 `rootInActiveWindow` 是面板的树时,
 *        旧代码照扫面板(现在要求 `root.packageName == 事件包名` 才扫);
 *     ③ **"关闭"是包含匹配**: 面板 7 个可点开关的节点 `text` 恰好就是"关闭"
 *        (`class=android.widget.Switch`, 功能名在 content-desc: 飞行模式/WLAN/振动/静音/省电/手电筒/
 *        GKD 自己的磁贴), 于是全被当成开屏广告的关闭按钮 —— 点到 GKD 磁贴时直接把无障碍关了。
 *   现在: [SystemSurfaces] 挡掉所有系统界面 + 只扫应用自己的窗口 + 开关类控件不点 + "关闭"只认关闭类短语。
 */
object ShakeGuard {

    // 摇一摇/传感器广告的常见提示词(只用于记录"高置信证据", 不再是动手的必要条件)
    private val keywords = arrayOf(
        "摇一摇", "摇一摇得", "摇动手机", "晃动手机", "转动手机",
        "转一转", "左右摇摆", "摇摆手机", "摇一摇有奖",
    )

    /**
     * ★★ fok0022: "关闭"**只认真正的关闭按钮**, 不再做无脑包含匹配。
     *
     * 真机取证(用户"上拉到控制面板"): 面板开关的节点 `text` 就是"关闭"(功能名在 content-desc 里),
     * 而应用里的功能开关也叫"关闭弹幕/关闭超微距" —— 旧代码 `text.contains("关闭")` 把两类全吃了。
     * 现在: 裸 "关闭"/"關閉"/"close" 才算, 或命中下面这些**关闭类短语**。
     */
    private val closePhrases = arrayOf(
        "关闭广告", "關閉廣告", "关闭弹窗", "关闭页面", "关闭提示", "关闭浮层", "关闭遮罩",
        "关闭视频", "关闭图片", "关闭应用", "关闭小程序", "关闭下载", "关闭安装", "关闭活动",
        "close ad",
    )

    /** 开关的"状态文字": 这类文字**单独出现**时描述的是某个功能的状态, 不是关闭按钮 */
    private val stateWords = setOf("关闭", "關閉", "开启", "已关闭", "已开启", "打开", "关闭中", "开启中")

    /** 开关类控件(控制面板磁贴、设置里的开关…): 其文字/desc 描述状态, 一律不点 */
    private val toggleClassWords =
        arrayOf("Switch", "ToggleButton", "CompoundButton", "CheckBox", "RadioButton")

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
        // ★★ fok0022: 系统界面/厂商系统组件(状态栏、通知、控制面板、上滑面板、桌面…)一律**不参与** ——
        //   既不重置开屏窗口, 也绝不在它们上面找"跳过/关闭/知道了"按钮。
        //   真机"上拉到控制面板"的元凶就是这里: 面板 7 个可点开关的节点 text 恰好是"关闭",
        //   一天被点了 40+ 次(还把 GKD 自己的磁贴点了 → 无障碍被关)。详见 SystemSurfaces 的取证表。
        if (SystemSurfaces.isSystemSurface(pkg)) return
        val activity = event.className?.toString()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        if (openPkg != pkg) {
            openPkg = pkg
            openActivity = activity
            openAt = now
            handled = false
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
        // ★★ fok0022: 只扫**这个应用自己的窗口**。事件包名是 A、而活动窗口是控制面板/通知栏时,
        //   `rootInActiveWindow` 返回的是**面板的树** —— 旧代码照扫不误, 面板上的开关照样被点
        //   (这正是"上拉到控制面板会触发东西"的另一条通道: 事件来自应用、树却来自面板)。
        val rootPkg = runCatching { root.packageName?.toString() }.getOrNull()
        if (rootPkg.isNullOrEmpty() || rootPkg != pkg) {
            logSkip("root-pkg=${rootPkg ?: "null"}")
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
                // 关闭/跳过按钮候选: **必须可点击且可用**(只做节点点击, 绝不打坐标 —— 那才是假跳过的坑),
                // 且 **不能是开关类控件**、**不能是"关闭/开启"这种状态文字** —— 见 fok0022 的类注释
                if (node.isClickable && node.isEnabled && !isToggleNode(node)) {
                    val exactText = text.trim()
                    val descText = desc.trim()
                    // 带 content-desc 的状态文字(text=关闭, desc=手电筒/飞行模式)= 控制面板/设置里的开关
                    val labeledStateWord = exactText.isNotEmpty() && descText.isNotEmpty() &&
                        stateWords.contains(exactText)
                    if (!labeledStateWord) {
                        val cand = exactText.ifEmpty { descText }
                        val priority = closePriority(cand)
                        if (priority != null && priority > bestClosePriority) {
                            bestClosePriority = priority
                            bestClose = node
                            bestCloseLabel = cand
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

    /** 开关类控件: 它的文字/desc 描述的是某个功能的状态, 不可能是"关闭广告"按钮 */
    private fun isToggleNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable) return true
        val cls = runCatching { node.className?.toString() }.getOrNull() ?: return false
        return toggleClassWords.any { cls.contains(it) }
    }

    /**
     * 命中优先级(0=跳过 1=关闭 2=知道了), null = 不是"跳过/关闭"类按钮。
     *
     * ★ fok0022 收紧点: "关闭"不再做包含匹配。控制面板开关的 text 就是"关闭"(它的 desc 才是功能名),
     * 应用里的功能开关叫"关闭弹幕/关闭超微距" —— 两者都不是开屏广告的关闭按钮。
     * 现在只认裸 "关闭"/"關閉"/"close", 或 [closePhrases] 里的关闭类短语。
     */
    private fun closePriority(cand: String): Int? {
        val t = cand.trim()
        if (t.isEmpty() || t.length > MAX_TEXT_LEN) return null
        val lower = t.lowercase()
        if (t.contains("跳过") || t.contains("跳過") || lower.startsWith("skip")) return 0
        if (t == "知道了" || t == "我知道了") return 2
        if (t == "关闭" || t == "關閉" || lower == "close") return 1
        if (closePhrases.any { lower.contains(it.lowercase()) }) return 1
        return null
    }

    /** skip 日志节流: 窗口从 2 秒放宽到"开屏时长"后, 扫描次数明显变多, 不节流会刷屏 */
    private fun logSkip(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastSkipLog < SKIP_LOG_INTERVAL_MS) return
        lastSkipLog = now
        LogUtils.d("ShakeGuard skip pkg=$openPkg reason=$reason")
    }
}
