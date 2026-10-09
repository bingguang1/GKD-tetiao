package li.songe.gkd.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import li.songe.gkd.shizuku.casted
import li.songe.gkd.util.ScreenUtils

/**
 * fork(fok0030): 「跳过类节点」的**事前形态判据** —— 只用无障碍树, **绝不看屏幕位置**。
 *
 * ## 为什么需要它(用户实测的痛点)
 *
 * fok0029 之前的假跳过防护只有"事后落点校验": 点完 1.2 秒看前台包名, 变了就按返回键 + **把整个 App 拉黑**。
 * 用户实测反馈: **"有些广告并不是假的跳过, 只是跳过的按钮区域不在右上角, 这时也会触发这个,
 * 导致广告不跳过"** —— 也就是说真跳过被当成假跳过处理了, 而且拉黑之后**这个 App 的跳过永远不再点**。
 *
 * 只靠位置(右上角/底部)区分真假是错的(真机上两种位置的广告都存在)。真正稳定的区别是**结构**:
 *
 * | 形态 | 无障碍树长什么样 | 点那个坐标会发生什么 |
 * |---|---|---|
 * | **假跳过** | "跳过"是不可点文字, 它的那个点上**压着一个大得多的可点层**(广告层/整屏容器) | 点它 = 点广告 → 拉起落地页 |
 * | **真跳过** | "跳过"是不可点文字, 但它那个点上**没有任何可点节点**(或只有一个跟它差不多大的可点按钮) | 点它 = 交给 App 自己的处理 → 正常跳过 |
 *
 * 例(真机取证): 学习通 `id=com.chaoxing.mobile:id/btn_jump, text=跳过3s, clickable=false` ——
 * 它的坐标点击是**有效**的, 说明那个点上没有别的可点层盖着 ⇒ 本判据会判 `Real` ⇒ 照点。
 *
 * ## 判据(逐个可核对)
 *
 * 1. 取目标节点矩形与**点击点**(与 `GkdAction.ClickCenter` 同口径: 有 position 表达式时用表达式算出来的点);
 * 2. 在窗口树里找**所有"包含该点 且 clickable 且 visibleToUser"**的节点(排除目标自己);
 * 3. **一个都没有** → `Real`(证据 `no-clickable-at-point`): 坐标点击不会被别的可点层吃掉;
 * 4. 有, 但面积都比目标**大不了多少**(< [OVERLAY_AREA_RATIO] 倍, 且不占半屏) → `Real`(证据 `small-clickable-at-point`):
 *    那是"和文字差不多大的按钮"(按钮自己就是可点的容器) ⇒ 点它就是点那个按钮;
 * 5. 有任意一个**面积 ≥ 目标 [OVERLAY_AREA_RATIO] 倍 或 占屏幕一半以上** → `Fake`(证据 `covered-by-large-clickable`):
 *    那就是盖在跳过文字上面的广告层/整屏容器, 点它必然点到广告;
 * 6. 拿不到矩形/拿不到树根/树太大走不完 → `Unknown` —— **调用方必须把 Unknown 当"放行"处理**
 *    (用户定的铁律: **判不准就点, 误拦优先避免**)。
 *
 * ## 有意不做的事
 * - **不看绝对位置**(不做"右上角才是真跳过"这种判据 —— 那正是用户报的误拦来源);
 * - 不截图、不 OCR、不联网;
 * - 不修改任何节点、不执行任何动作(纯只读判定, 判完由调用方决定)。
 */
object SkipTreeJudge {

    /** 一次判定最多走多少个节点(树很大时宁可 Unknown 也不拖慢规则循环) */
    private const val MAX_WALK_NODES = 500

    /** 目标祖先链最多往上找几层(找不到根就 Unknown) */
    private const val MAX_PARENT_DEPTH = 25

    /** 覆盖层面积 / 目标面积 的阈值: ≥ 这个倍数就认为"被广告层压住" */
    private const val OVERLAY_AREA_RATIO = 8.0

    /** 覆盖层占屏幕面积的比例阈值(整屏广告层通常远超这个值) */
    private const val OVERLAY_SCREEN_RATIO = 0.5

    fun judge(node: AccessibilityNodeInfo, x: Float, y: Float): SkipJudgement {
        val targetRect = boundsOf(node) ?: return SkipJudgement(SkipVerdict.Unknown, "no-rect")
        val targetArea = area(targetRect)
        if (targetArea <= 0) return SkipJudgement(SkipVerdict.Unknown, "empty-rect")
        val root = findRoot(node) ?: return SkipJudgement(SkipVerdict.Unknown, "no-root")
        val targetKey = nodeKey(node)

        var bestArea = 0
        var bestDesc = ""
        var visited = 0
        val stack = ArrayList<AccessibilityNodeInfo>(64)
        stack.add(root)
        while (stack.isNotEmpty() && visited < MAX_WALK_NODES) {
            val n = stack.removeLast()
            visited++
            if (n.isClickable && n.isVisibleToUser && nodeKey(n) != targetKey) {
                val r = boundsOf(n)
                if (r != null && contains(r, x, y)) {
                    val a = area(r)
                    if (a > bestArea) {
                        bestArea = a
                        bestDesc = "${n.className}[${r.left},${r.top}][${r.right},${r.bottom}]"
                    }
                }
            }
            val cc = runCatching { n.childCount }.getOrDefault(0)
            for (i in 0 until cc) {
                runCatching { n.getChild(i) }.getOrNull()?.let { stack.add(it) }
            }
        }
        if (visited >= MAX_WALK_NODES) {
            // 没走完就下结论容易冤枉人 —— 宁可 Unknown(放行)
            return SkipJudgement(SkipVerdict.Unknown, "walk-limit")
        }
        if (bestArea <= 0) {
            return SkipJudgement(SkipVerdict.Real, "no-clickable-at-point")
        }
        val ratio = bestArea.toDouble() / targetArea.toDouble()
        val screenArea = screenArea()
        val screenRatio = if (screenArea > 0) bestArea.toDouble() / screenArea else 0.0
        val covered = ratio >= OVERLAY_AREA_RATIO || screenRatio >= OVERLAY_SCREEN_RATIO
        return SkipJudgement(
            verdict = if (covered) SkipVerdict.Fake else SkipVerdict.Real,
            evidence = (if (covered) "covered-by-large-clickable" else "small-clickable-at-point") +
                " ratio=${"%.1f".format(ratio)} screen=${"%.2f".format(screenRatio)}",
            ratio = ratio,
            overlay = bestDesc,
        )
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect? =
        runCatching { node.casted.boundsInScreen }.getOrNull()

    private fun area(r: Rect): Int = (r.width()).coerceAtLeast(0) * (r.height()).coerceAtLeast(0)

    private fun contains(r: Rect, x: Float, y: Float): Boolean =
        x >= r.left && x < r.right && y >= r.top && y < r.bottom

    private fun screenArea(): Int = runCatching {
        val size = ScreenUtils.getScreenSize()
        size.width * size.height
    }.getOrDefault(0)

    private fun findRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < MAX_PARENT_DEPTH) {
            val parent = runCatching { cur.parent }.getOrNull()
            if (parent == null) return cur
            cur = parent
            depth++
        }
        // 祖先链太深(或读到空) → 退回无障碍服务的当前窗口根; 再拿不到就让调用方按 Unknown 放行
        return runCatching { A11yService.instance?.rootInActiveWindow }.getOrNull()
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String {
        val r = boundsOf(node)
        val rectText = if (r == null) "[]" else "[${r.left},${r.top}][${r.right},${r.bottom}]"
        return "$rectText@${node.text ?: node.contentDescription ?: ""}@${node.className ?: ""}"
    }
}

/** 形态判定的结论(字符串证据都带在结果里, 便于日志与真机排障) */
enum class SkipVerdict(val text: String) {
    /** 那个点上盖着明显更大的可点层(广告层) ⇒ 点击必然点到广告 */
    Fake("fake"),

    /** 那个点上没有可点层, 或只有跟它差不多大的按钮 ⇒ 点击交给 App 自己处理(= 真跳过) */
    Real("real"),

    /** 树读不全/超时/拿不到坐标 ⇒ **判不准, 调用方必须放行** */
    Unknown("unknown"),
}

data class SkipJudgement(
    val verdict: SkipVerdict,
    val evidence: String,
    val ratio: Double = 0.0,
    val overlay: String = "",
)
