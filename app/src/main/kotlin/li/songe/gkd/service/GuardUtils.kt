package li.songe.gkd.service

import android.content.Intent
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.app
import li.songe.gkd.util.LogUtils

/**
 * [FakeSkipGuard] 与 [JumpGuard] 共用的三个动作。
 *
 * 原本两个文件各有一份**逐字节等价**的私有实现, 合并到这里的目的不是"少几行", 而是:
 *   ① 关键坑位说明只有一份 —— "BACK 返回 true 也可能什么都没发生, 且 `topActivityFlow` 是缓存值"
 *      这条真机(vivo/Android16)结论原本只写在 FakeSkipGuard 里, 只读 JumpGuard 的人看不到;
 *   ② 两份各自演进时, 一边修好的坑不会传播到另一边。
 *
 * 三个函数都在"已经判定需要处置"的低频路径上调用(不是每个无障碍事件/每个节点), 合并无额外开销。
 */

/**
 * 新读一次当前前台包名(**不读缓存流**)。
 * 依次尝试: 活动窗口根节点 → 各窗口里 focused/active 的那个 → 都拿不到返回 null(未知, 不做动作)。
 *
 * ⚠️ 必须用它而不是 `topActivityFlow.value`: 后者是**缓存值**(屏幕锁了/没有新事件时会停在旧值),
 * 而真机实测"BACK 返回 true 也可能什么都没发生" —— 用缓存值判断会得出错误结论。
 */
internal fun currentForegroundPkg(): String? {
    runCatching {
        A11yService.instance?.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    return runCatching {
        A11yRuleEngine.compatWindows()
            .firstOrNull { w -> runCatching { w.isFocused || w.isActive }.getOrDefault(false) }
            ?.root?.packageName?.toString()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * 把 [pkg] 拉回前台(误点了落地页 / 被踢到桌面后的兜底)。
 * [logTag] 用于区分调用方(两处日志文案原本就不同), 便于排障时定位。
 */
internal fun relaunchApp(pkg: String, logTag: String): Boolean {
    if (pkg.isEmpty()) return false
    return runCatching {
        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        app.startActivity(intent)
        true
    }.getOrElse {
        LogUtils.d("$logTag relaunch error pkg=$pkg", it)
        false
    }
}

/**
 * 由包名取应用显示名(toast 文案用), 查不到就回退成包名本身。
 *
 * 这里是同步直查 PackageManager: 调用点是"每对 (A→B) 只弹一次的 toast", 不在列表/重组路径上,
 * 所以不需要走 [li.songe.gkd.util.AppInfoState] 的缓存(那里是给 UI 列表用的, 需要异步预热)。
 */
internal fun appLabel(appId: String): String = runCatching {
    val pm = app.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(appId, 0)).toString()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: appId

/**
 * "开屏/广告页"的页面类名特征词(小写匹配)。
 *
 * v121 起从 [JumpGuard] 私有移到共用: [JumpGuard](开屏跳转的兜底时长)与 [ShakeGuard](点击前的"广告证据")
 * 必须用**同一份**判据 —— 否则同一个页面在一个模块里算"开屏页"、在另一个模块里不算, 用户看到的行为就会自相矛盾。
 */
private val splashPageWords =
    arrayOf("splash", "advert", "adactivity", ".ads.", "welcome", "guideactivity")

/** 这个页面类名像不像"开屏/广告页"(见 [splashPageWords]) */
internal fun isSplashLikePage(activity: String?): Boolean {
    if (activity.isNullOrEmpty()) return false
    val lower = activity.lowercase()
    return splashPageWords.any { lower.contains(it) }
}
