package li.songe.gkd.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import li.songe.gkd.META
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils

/**
 * 免 root「防摇一摇」内置防护(摇一摇广告拦截)。
 *
 * 原理: 应用刚打开(切换到新前台包)的 2 秒窗口内, 若屏幕上出现"摇一摇/转动手机"等
 * 开屏摇一摇广告提示, 且同屏存在可点击的"跳过/关闭/知道了"按钮, 则点击该按钮关闭广告页。
 *
 * v97 收敛(修复"启用后微信等软件被误退/乱跳"):
 * 1. 移除"找不到关闭按钮就模拟返回键"的兜底 —— BACK 会把正常 App 的开屏页/页面直接退掉,
 *    是"微信等软件退出"的元凶; 现在没有可点的关闭按钮就什么都不做(不做危险的全局操作)。
 * 2. 动作前置条件收紧: 必须 摇一摇提示 + 可点击关闭按钮 **同时** 出现才处理;
 *    提示词/按钮文本均要求是"短句"(防长文本内容误命中), 按钮还要求 enabled。
 * 3. 遍历带节点上限, 防止超大窗口(朋友圈等)拖慢无障碍线程导致服务被系统判定无响应。
 *
 * 该功能仅在 GKD「设置」开关 `shakeGuard` 开启时生效, 且每个应用每次打开最多处理一次。
 */
object ShakeGuard {

    // 摇一摇/传感器广告的常见提示词
    private val keywords = arrayOf(
        "摇一摇", "摇一摇得", "摇动手机", "晃动手机", "转动手机",
        "转一转", "左右摇摆", "摇摆手机", "摇一摇有奖",
    )

    // 关闭按钮候选文本/desc(包含匹配, 优先级从高到低: 跳过 > 关闭 > 知道了)
    private val closeCandidates = arrayOf("跳过", "关闭", "知道了")

    private const val WINDOW_MS = 2000L        // 打开应用后的防护窗口
    private const val SCAN_MIN_INTERVAL = 300L // 两次扫描最小间隔
    private const val MAX_NODES = 2500         // 单次遍历节点上限(防大窗口卡顿)
    private const val MAX_TEXT_LEN = 20        // 命中节点文本最大长度(排除长文本内容)

    @Volatile
    private var openPkg: String? = null
    @Volatile
    private var openAt = 0L
    @Volatile
    private var handled = false
    @Volatile
    private var lastScan = 0L

    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (event == null) return
        if (!storeFlow.value.shakeGuard) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == META.appId) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (openPkg != pkg) {
            openPkg = pkg
            openAt = System.currentTimeMillis()
            handled = false
            SensorOrientationGuard.onAppOpen(pkg)
        }
        if (handled) return
        val now = System.currentTimeMillis()
        if (now - openAt > WINDOW_MS) return
        if (now - lastScan < SCAN_MIN_INTERVAL) return
        lastScan = now
        val root = service.rootInActiveWindow ?: run {
            LogUtils.d("ShakeGuard noRoot pkg=$pkg")
            return
        }
        try {
            scanAndHandle(service, root)
        } catch (e: Throwable) {
            LogUtils.d("ShakeGuard", e)
        } finally {
            runCatching { root.recycle() }
        }
    }

    /** 节点文本是否像一句"短提示"(过长的正文/聊天内容不参与命中) */
    private fun shortText(t: String): Boolean = t.isNotEmpty() && t.length <= MAX_TEXT_LEN

    private fun scanAndHandle(service: AccessibilityService, root: AccessibilityNodeInfo) {
        var shakeFound = false
        var bestClose: AccessibilityNodeInfo? = null
        var bestClosePriority = -1
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
                // 关闭按钮候选: 可点击、可用、文本/desc 为短句且含关闭词; 取关键词优先级最高者
                if (node.isClickable && node.isEnabled) {
                    val cand = text.ifEmpty { desc }
                    for ((idx, kw) in closeCandidates.withIndex()) {
                        if (cand.length in 1..MAX_TEXT_LEN && cand.contains(kw)) {
                            if (idx > bestClosePriority) {
                                bestClosePriority = idx
                                bestClose = node
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
            // v97: 必须"摇一摇提示 + 可点关闭按钮"同时成立才动作; 不再有返回键兜底, 避免误退正常应用
            // fork v105: 只要看到了摇一摇提示词, 就给 JumpGuard 留个"证据", 供它判定后续的跨应用跳转
            if (shakeFound) JumpGuard.noteShakeEvidence()
            if (!shakeFound || bestClose == null) {
                LogUtils.d("ShakeGuard skip pkg=$openPkg shake=$shakeFound close=${bestClose != null}")
                return
            }
            val target = bestClose
            val clicked = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (clicked) {
                handled = true
                LogUtils.d("ShakeGuard handled pkg=$openPkg via click")
            } else {
                // 点击未生效(如按钮被系统拦截)则不再做任何兜底, 保持安全
                LogUtils.d("ShakeGuard click failed pkg=$openPkg nodes=$visited")
            }
        } finally {
            children.forEach { n ->
                runCatching { n.recycle() }
            }
        }
    }
}
