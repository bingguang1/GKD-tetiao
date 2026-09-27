package li.songe.gkd.data

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.service.A11yService
import li.songe.gkd.service.TrackService
import li.songe.gkd.shizuku.casted
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.ScreenUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * fork v102: 坐标点击合法性守卫 —— 拒绝"打在节点自己不成立的位置上"的盲坐标点击。
 *
 * 真机实证 (vivo V2238A, 2026-09-08 微信朋友圈广告): 订阅里"有 position 无 action"的规则会被
 * ResolvedRule 强制走 clickCenter 打坐标, 而 Position.calc 只用 ScreenUtils.inScreen 校验**屏幕**
 * 边界, 从不校验这个点是否落在匹配到的节点自己身上。当真机抓到一个"空/反向矩形"的节点时
 * (top=2286 > bottom=2274, height=-12, visibleToUser=false), 算出来的点落在节点之外, 实际打在了
 * 下面的广告卡片上 —— 这就是"按逻辑关闭却点进广告"。
 *
 * 返回 null 表示放行, 否则返回拒绝原因:
 *   rect-empty: 矩形为空/反向(width<=0 || height<=0)。空矩形上做比例计算没有任何合法语义。
 *   invisible : 节点 visibleToUser=false **且**算出来的点落在这个节点自己的范围内 ——
 *               点在用户看不到的东西上不可能是有意为之。
 * ⚠️ 故意**不做**"点必须落在节点矩形内": 订阅里存在故意点在节点外的规则
 *   (抖音 top:'width*2.0649'、鄂汇办 top:'width*-1.9094'、软件包安装程序 left:'width*1.5394' 等 5 条),
 *   加了会打断它们。误伤审计见 docs/v102-wechat-ad-plan.md。
 */
private fun clickGuardRejectReason(
    node: AccessibilityNodeInfo,
    rect: Rect,
    x: Float,
    y: Float,
): String? {
    val store = storeFlow.value
    if (store.strictClickGuard && (rect.width() <= 0 || rect.height() <= 0)) return "rect-empty"
    if (store.guardInvisibleNode && !node.isVisibleToUser &&
        x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
    ) {
        return "invisible"
    }
    return null
}

private val clickGuardLogTime = ConcurrentHashMap<String, Long>()

/** 规则匹配是 ~300ms 一轮的循环, 被否决的坐标会反复出现, 日志做节流(与 v100 veto 日志同样的理由) */
private fun logClickGuardReject(
    reason: String,
    node: AccessibilityNodeInfo,
    rect: Rect,
    x: Float,
    y: Float,
) {
    val key = "$reason@${rect.left},${rect.top},${rect.right},${rect.bottom}"
    val now = System.currentTimeMillis()
    if (now - (clickGuardLogTime[key] ?: 0L) < 2000L) return
    clickGuardLogTime[key] = now
    LogUtils.d(
        "ClickGuard reject reason=$reason rect=[${rect.left},${rect.top}][${rect.right},${rect.bottom}] " +
            "pos=($x,$y) node=${node.className} pkg=${node.packageName} visible=${node.isVisibleToUser}"
    )
}

@Serializable
data class GkdAction(
    val selector: String,
    val fastQuery: Boolean = false,
    val action: String? = null,
    override val position: RawSubscription.Position? = null,
    override val swipeArg: RawSubscription.SwipeArg? = null,
) : RawSubscription.LocationProps

@Serializable
data class ActionResult(
    val action: String,
    val result: Boolean,
    val shell: Boolean = false,
    val position: Pair<Float, Float>? = null,
)

sealed class ActionPerformer(val action: String) {
    abstract suspend fun perform(
        node: AccessibilityNodeInfo,
        locationProps: RawSubscription.LocationProps,
    ): ActionResult

    data object ClickNode : ActionPerformer("clickNode") {
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            TrackService.addA11yNodePosition(node)
            return ActionResult(
                action = action,
                result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
        }
    }

    data object ClickCenter : ActionPerformer("clickCenter") {
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            val rect = node.casted.boundsInScreen
            val p = locationProps.position?.calc(rect)
            val x = p?.first ?: ((rect.right + rect.left) / 2f)
            val y = p?.second ?: ((rect.bottom + rect.top) / 2f)
            // fork v102: 坐标守卫 —— 空/反向矩形、以及落在不可见节点范围内的盲坐标, 一律不点
            clickGuardRejectReason(node, rect, x, y)?.let { reason ->
                logClickGuardReject(reason, node, rect, x, y)
                return ActionResult(
                    action = action,
                    result = false,
                    position = x to y,
                )
            }
            if (!ScreenUtils.inScreen(x, y)) {
                return ActionResult(
                    action = action,
                    result = false,
                    position = x to y,
                )
            }
            TrackService.addXyPosition(x, y)
            return ActionResult(
                action = action,
                result = if (shizukuContextFlow.value.tap(x, y)) {
                    true
                } else {
                    val gestureDescription = GestureDescription.Builder()
                    val path = Path()
                    path.moveTo(x, y)
                    gestureDescription.addStroke(
                        GestureDescription.StrokeDescription(
                            path, 0, ViewConfiguration.getTapTimeout().toLong()
                        )
                    )
                    A11yService.instance?.dispatchGesture(
                        gestureDescription.build(), null, null
                    ) != null
                },
                position = x to y
            )
        }
    }

    data object Click : ActionPerformer("click") {
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            if (node.isClickable) {
                val result = ClickNode.perform(node, locationProps)
                if (result.result) {
                    return result
                }
            }
            return ClickCenter.perform(node, locationProps)
        }
    }

    data object LongClickNode : ActionPerformer("longClickNode") {
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            TrackService.addA11yNodePosition(node)
            return ActionResult(
                action = action,
                result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK).apply {
                    if (this) {
                        delay(LongClickCenter.LONG_DURATION)
                    }
                }
            )
        }
    }

    data object LongClickCenter : ActionPerformer("longClickCenter") {
        const val LONG_DURATION = 500L
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            val rect = node.casted.boundsInScreen
            val p = locationProps.position?.calc(rect)
            val x = p?.first ?: ((rect.right + rect.left) / 2f)
            val y = p?.second ?: ((rect.bottom + rect.top) / 2f)
            // fork v102: 坐标守卫(同 ClickCenter)
            clickGuardRejectReason(node, rect, x, y)?.let { reason ->
                logClickGuardReject(reason, node, rect, x, y)
                return ActionResult(
                    action = action,
                    result = false,
                    position = x to y,
                )
            }
            // 某些系统的 ViewConfiguration.getLongPressTimeout() 返回 300 , 这将导致触发普通的 click 事件
            if (!ScreenUtils.inScreen(x, y)) {
                return ActionResult(
                    action = action,
                    result = false,
                    position = x to y,
                )
            }
            TrackService.addXyPosition(x, y)
            return ActionResult(
                action = action,
                result = if (shizukuContextFlow.value.tap(x, y, LONG_DURATION)) {
                    true
                } else {
                    val gestureDescription = GestureDescription.Builder()
                    val path = Path()
                    path.moveTo(x, y)
                    gestureDescription.addStroke(
                        GestureDescription.StrokeDescription(
                            path, 0, LONG_DURATION
                        )
                    )
                    (A11yService.instance?.dispatchGesture(
                        gestureDescription.build(), null, null
                    ) != null).apply {
                        if (this) {
                            delay(LONG_DURATION)
                        }
                    }
                },
                position = x to y
            )
        }
    }

    data object LongClick : ActionPerformer("longClick") {
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            if (node.isLongClickable) {
                val result = LongClickNode.perform(node, locationProps)
                if (result.result) {
                    return result
                }
            }
            return LongClickCenter.perform(node, locationProps)
        }
    }

    data object Back : ActionPerformer("back") {
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            return ActionResult(
                action = action,
                result = A11yRuleEngine.performActionBack()
            )
        }
    }

    data object None : ActionPerformer("none") {
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            return ActionResult(
                action = action,
                result = true
            )
        }
    }

    data object Swipe : ActionPerformer("swipe") {
        override suspend fun perform(
            node: AccessibilityNodeInfo,
            locationProps: RawSubscription.LocationProps,
        ): ActionResult {
            val rect = node.casted.boundsInScreen
            val swipeArg = locationProps.swipeArg ?: return ActionResult(
                action = action,
                result = false,
            )
            val startP = swipeArg.start.calc(rect)
            val endP = swipeArg.end?.calc(rect) ?: startP
            if (startP == null || endP == null) {
                return ActionResult(
                    action = action,
                    result = false,
                )
            }
            val startX = startP.first
            val startY = startP.second
            val endX = endP.first
            val endY = endP.second
            if (!(ScreenUtils.inScreen(startX, startY) && ScreenUtils.inScreen(endX, endY))) {
                return ActionResult(
                    action = action,
                    result = false,
                    position = endX to endY,
                )
            }
            TrackService.addSwipePosition(startX, startY, endX, endY, swipeArg.duration)
            return if (shizukuContextFlow.value.swipe(
                    startX,
                    startY,
                    endX,
                    endY,
                    swipeArg.duration
                )
            ) {
                ActionResult(
                    action = action,
                    result = true,
                    shell = true,
                    position = endX to endY,
                )
            } else {
                val gestureDescription = GestureDescription.Builder()
                val path = Path()
                path.moveTo(startX, startY)
                path.lineTo(endX, endY)
                gestureDescription.addStroke(
                    GestureDescription.StrokeDescription(
                        path, 0, swipeArg.duration
                    )
                )
                ActionResult(
                    action = action,
                    result = (A11yService.instance?.dispatchGesture(
                        gestureDescription.build(), null, null
                    ) != null).apply {
                        if (this) {
                            delay(swipeArg.duration)
                        }
                    },
                    position = endX to endY,
                )
            }
        }
    }

    companion object {
        private val allSubObjects by lazy {
            arrayOf(
                ClickNode,
                ClickCenter,
                Click,
                LongClickNode,
                LongClickCenter,
                LongClick,
                Back,
                None,
                Swipe,
            )
        }

        fun getAction(action: String?): ActionPerformer {
            return allSubObjects.find { it.action == action } ?: Click
        }
    }
}
