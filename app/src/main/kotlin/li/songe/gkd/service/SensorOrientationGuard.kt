package li.songe.gkd.service

import android.app.AppOpsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import li.songe.gkd.META
import li.songe.gkd.appScope
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry

/**
 * fork 定制: 「获取设备方向」传感器权限开屏撤销 —— 防摇一摇跳转的根因式拦截。
 *
 * 背景: vivo OriginOS / MIUI / ColorOS 等国产 ROM 都有一个「获取设备方向」权限(门控加速度计/陀螺仪),
 *   摇一摇开屏广告正是靠这个传感器在开屏那 1~2 秒内触发"跳转"。老方案(ShakeGuard)是"看到提示+按钮再点关闭",
 *   属于事后补救; 而如果广告一摇就跳、根本不给你按钮, 老方案就失效了 —— 这正是"疑似无效"的原因。
 *
 * 本方案: 应用刚打开(进入新的前台包)时, 通过 Shizuku(shell 权限)把该应用的「获取设备方向」appop 置为
 *   ignore, 让它读不到传感器 → 摇一摇无法触发跳转; 开屏窗口(2 秒)结束、或切换到其它应用后, 恢复原权限。
 *
 * 前提与降级:
 *   1. 修改其它应用的 appop 需要 shell 权限(MANAGE_APP_OPS_MODES), 只能走 Shizuku。
 *      未连接 Shizuku 时本模块直接跳过并记日志(不影响其它功能)。
 *   2. 原生 AOSP(含 MuMu 模拟器)没有「获取设备方向」这个 appop(只有心率 BODY_SENSORS),
 *      所以本模块在原生系统上会解析不到目标 op 而跳过 —— 这属于预期, 真正生效需要 vivo/小米等 ROM。
 *   3. op 名是厂商自定义的, 这里用「反射枚举 AppOpsManager 全部 op 名 + 关键词过滤」来自动适配,
 *      解析结果会打日志, 便于在真机上核对。
 */
object SensorOrientationGuard {

    private const val WINDOW_MS = 2000L

    // 用于在"全部 op 名"里筛选"获取设备方向"类 op 的关键词(命中即候选, 排除 body/heart/skin 心率类)
    private val opKeywords = listOf(
        "orientation", "orient", "direction", "rotation", "rotate",
        "gyro", "acceler", "motion", "shake", "sensor",
    )
    // 反射枚举失败/未命中时的兜底候选(OPSTR 形如 android:xxx)
    private val candidateNames = listOf(
        "android:sensors",
        "android:get_device_orientation",
        "android:device_orientation",
        "android:orientation",
        "android:access_device_orientation",
        "android:read_sensors",
    )

    @Volatile private var resolvedCode = Int.MIN_VALUE
    @Volatile private var resolvedName: String? = null
    @Volatile private var resolvedAttempted = false

    @Volatile private var targetPkg: String? = null
    @Volatile private var targetUid = -1

    /** 应用刚打开时调用(无障碍线程): 异步撤销传感器权限, 窗口结束后恢复 */
    fun onAppOpen(pkg: String) {
        if (pkg == META.appId) return
        if (pkg == targetPkg) return
        appScope.launchTry(Dispatchers.IO) {
            restorePrevious()
            val code = resolveCode() ?: run {
                LogUtils.d("SensorGuard skip pkg=$pkg reason=no-orientation-op")
                return@launchTry
            }
            val ctx = shizukuContextFlow.value
            if (!ctx.ok) {
                LogUtils.d("SensorGuard skip pkg=$pkg reason=no-shizuku op=$resolvedName")
                return@launchTry
            }
            val uid = ctx.packageManager?.getUid(pkg) ?: run {
                LogUtils.d("SensorGuard skip pkg=$pkg reason=no-uid")
                return@launchTry
            }
            targetPkg = pkg
            targetUid = uid
            val before = ctx.appOpsService?.checkMode(code, uid, pkg) ?: AppOpsManager.MODE_ALLOWED
            if (before != AppOpsManager.MODE_IGNORED) {
                ctx.appOpsService?.setModeForPackage(code, uid, pkg, AppOpsManager.MODE_IGNORED)
                LogUtils.d("SensorGuard revoked pkg=$pkg op=$resolvedName uid=$uid before=$before")
            } else {
                LogUtils.d("SensorGuard alreadyIgnored pkg=$pkg op=$resolvedName")
            }
            delay(WINDOW_MS)
            if (targetPkg == pkg) {
                ctx.appOpsService?.setModeForPackage(code, uid, pkg, before)
                targetPkg = null
                targetUid = -1
                LogUtils.d("SensorGuard restored pkg=$pkg op=$resolvedName mode=$before")
            }
        }
    }

    private fun restorePrevious() {
        val p = targetPkg ?: return
        val u = targetUid
        targetPkg = null
        targetUid = -1
        if (u <= 0) return
        val ctx = shizukuContextFlow.value
        val code = resolvedCode.takeIf { it >= 0 } ?: return
        if (ctx.ok) {
            ctx.appOpsService?.setModeForPackage(code, u, p, AppOpsManager.MODE_ALLOWED)
            LogUtils.d("SensorGuard restoredPrev pkg=$p op=$resolvedName")
        }
    }

    /** 解析当前 ROM 的「获取设备方向」appop code(带缓存); 找不到返回 null */
    private fun resolveCode(): Int? {
        if (resolvedAttempted) return resolvedCode.takeIf { it >= 0 }
        resolvedAttempted = true
        val names = enumerateNames()
        LogUtils.d("SensorGuard op candidates=$names")
        for (name in names) {
            val code = strOpToOp(name)
            if (code >= 0) {
                resolvedCode = code
                resolvedName = name
                LogUtils.d("SensorGuard op resolved name=$name code=$code")
                return code
            }
        }
        resolvedCode = -1
        return null
    }

    private fun enumerateNames(): List<String> {
        val all = allOpNames()
        val filtered = all.filter { n ->
            val lower = n.lowercase()
            opKeywords.any { lower.contains(it) } &&
                !lower.contains("body") && !lower.contains("heart") && !lower.contains("skin")
        }
        return (filtered + candidateNames).distinct()
    }

    /** 反射 AppOpsManager 的静态 op 表, 拿到 ROM 已知的全部 op 名(OPSTR 形如 android:xxx); 兼容不同 Android 版本 */
    private fun allOpNames(): List<String> = runCatching {
        val result = LinkedHashSet<String>()
        // 1) sOpToSwitch: Map<opStr, code>, key 即 OPSTR
        runCatching {
            val field = AppOpsManager::class.java.getDeclaredField("sOpToSwitch")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as? Map<*, *>)?.keys?.filterIsInstance<String>()?.let { result.addAll(it) }
        }
        // 2) sOpToString: String[](下标=op code), 元素即 OPSTR
        runCatching {
            val field = AppOpsManager::class.java.getDeclaredField("sOpToString")
            field.isAccessible = true
            (field.get(null) as? Array<*>)?.filterIsInstance<String>()?.let { result.addAll(it) }
        }
        // 3) sOpNames: String[](显示名, 如 BODY_SENSORS)—— 无 android: 前缀, 作为兜底候选
        runCatching {
            val field = AppOpsManager::class.java.getDeclaredField("sOpNames")
            field.isAccessible = true
            (field.get(null) as? Array<*>)?.filterIsInstance<String>()?.let { names ->
                names.forEach { result.add("android:" + it.lowercase()) }
            }
        }
        result.toList()
    }.getOrDefault(emptyList())

    /** 反射 AppOpsManager.strOpToOp(String)(@hide public static) 把 op 名转 code */
    private fun strOpToOp(name: String): Int = runCatching {
        val method = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
        method.invoke(null, name) as Int
    }.getOrDefault(-1)
}
