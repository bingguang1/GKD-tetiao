package li.songe.gkd.service

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import li.songe.gkd.app
import li.songe.gkd.util.LogUtils

/**
 * fork(v119): 「设备动作与方向」= 防摇一摇广告的**根因防护**(只读自检 + 系统设置引导)。
 *
 * ## 背景: 为什么「防摇一摇」改成这一项
 * 摇一摇广告能跳转的**根因**是应用在**开屏那几秒**读到了加速度计/陀螺仪(系统里那一项叫「获取设备动作与方向」)。
 * 事后点掉"跳过/关闭"([ShakeGuard])、或者把已经发生的跳转退回来([JumpGuard]), 都只是补救 ——
 * 广告不给按钮、或者一摇就跳时, 这两条都会失效。
 * 2024 年底起国产 ROM 直接在系统层给了这一项, 并且专门加了**"仅开屏"语义**:
 *   - vivo OriginOS 5: 权限管理 →「获取设备动作与方向」→ 新增 **「仅开屏禁止」**
 *   - 小米 HyperOS 3: 应用权限管理 →「获取设备动作与方向」→ 新增 **「仅开屏时拒绝」**
 * 这正是我们要的效果: **只在应用开屏那几秒拒绝传感器**(开屏广告晃不动手机), 应用内的正常摇一摇照常可用。
 *
 * ## GKD 为什么只能"引导", 不能替用户设置
 * 2026-09-26 的真机取证(交接文档 §10.5)证明: vivo 上**不存在**「获取设备方向」这个 AppOps op
 * (设备 78 个 op 里只有 `BODY_SENSORS` 心率), `pm list permissions -f -g` 里也没有对应权限
 * ⇒ 第三方应用(哪怕拿到 Shizuku shell)**没有**任何可写入口把它设成"仅开屏禁止"。
 * v104 曾试图用 Shizuku 改 appop 从根上掐传感器, 因此被判死(模块也在 v115 删除)。
 *
 * 所以本模块的定位是:
 *   ① **只读自检** [probe]: 在本机把**全部** AppOps op 名与候选权限名枚举一遍, 把证据(找到什么/没找到什么)
 *      呈现给用户 —— 让"本机到底有没有可编程入口"这件事**可核对**, 而不是靠猜;
 *   ② **一键跳转** [openPermissionPage]: 逐应用跳到系统「获取设备动作与方向」权限页
 *      (小米/澎湃、vivo/iQOO、OPPO/一加、华为/荣耀, 最后兜底到通用"应用详情页"), 由用户自己选「仅开屏禁止」;
 *   ③ **清单**: 记录哪些应用已经设过(系统状态读不到, 只能用户自报), 免得设了一半忘了。
 *
 * ⚠️ 两条纪律(踩过的坑, 别违反):
 *   - 本模块**不做任何写入**(不改 appop、不改权限), 所以不会误伤应用的正常传感器使用;
 *   - **不在前台切换时打日志** —— v104 的 `SensorOrientationGuard` 就是每次切应用刷一行
 *     `SensorGuard skip ...` 日志噪音而被删的; 本模块只在**用户打开页面/点自检**时跑一次并缓存。
 */
object DeviceOrientationGuard {

    /**
     * "设备动作与方向"类 op 的**强关键词**(命中才是它; 注意排除心率类)。
     * 只匹配 sensor 的算弱候选(如 `high_sampling_rate_sensors`), 单独展示供人工核对。
     */
    private val strongOpKeywords = listOf(
        "orientation", "orient", "direction", "gyro", "acceler", "motion", "shake",
        "rotate", "rotation",
    )
    private val weakOpKeywords = listOf("sensor")
    private val opExcludes = listOf("body", "heart", "skin")

    /** 反射枚举失败/未命中时的兜底候选 op 名(OPSTR 形如 android:xxx) */
    private val candidateOpNames = listOf(
        "android:sensors",
        "android:get_device_orientation",
        "android:device_orientation",
        "android:orientation",
        "android:access_device_orientation",
        "android:read_sensors",
    )

    /** 候选权限名(只有本机 PackageManager 能解析到才算"存在") */
    private val candidatePermissionNames = listOf(
        "android.permission.ACCESS_DEVICE_ORIENTATION",
        "android.permission.ACCESS_DEVICE_ORIENTATION_PREVIEW",
        "miui.permission.ACCESS_DEVICE_ORIENTATION",
        "vivo.permission.ACCESS_DEVICE_ORIENTATION",
        "oplus.permission.ACCESS_DEVICE_ORIENTATION",
    )

    /** 系统权限页的候选入口(按 ROM 顺序尝试, 谁先能起来就用谁) */
    private data class PageTarget(
        val label: String,
        val pkg: String,
        val activity: String,
        val extraKey: String,
    )

    private val pageTargets = listOf(
        // 小米 / 澎湃
        PageTarget(
            "小米/澎湃", "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity", "extra_package_name",
        ),
        PageTarget(
            "小米/澎湃", "com.miui.securitycenter",
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity", "extra_package_name",
        ),
        // vivo / iQOO
        PageTarget(
            "vivo/iQOO", "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity", "packagename",
        ),
        PageTarget(
            "vivo/iQOO", "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.PurviewTabActivity", "packagename",
        ),
        // OPPO / 一加 / realme
        PageTarget(
            "OPPO/一加", "com.oplus.securitypermission",
            "com.oplus.securitypermission.permission.PermissionGroupsActivity", "pkgName",
        ),
        PageTarget(
            "OPPO/一加", "com.coloros.securitypermission",
            "com.coloros.securitypermission.permission.PermissionGroupsActivity", "pkgName",
        ),
        // 华为 / 荣耀
        PageTarget(
            "华为/荣耀", "com.huawei.systemmanager",
            "com.huawei.systemmanager.permissionmanager.PermissionManagerActivity", "packageName",
        ),
    )

    /**
     * 自检结果(纯只读证据)。
     *
     * @param opNames            全部 op 里命中**强关键词**的(即"设备动作与方向"类的可编程入口)
     * @param sensorOpNames      只命中 sensor 的弱候选(参考, 不是本功能要的那一项)
     * @param permissionNames    本机 PackageManager 能解析到的候选权限名
     * @param opEnumSource       枚举来源(反射到哪些内部字段/方法, 便于判断"没找到"是真没有还是枚举失败)
     * @param opCount            本机枚举到的 op 总数(0 = 反射被挡, 结论不可信)
     */
    data class ProbeResult(
        val opNames: List<String>,
        val sensorOpNames: List<String>,
        val permissionNames: List<String>,
        val opEnumSource: String,
        val opCount: Int,
        val at: Long,
    ) {
        /** 本机是否存在可编程控制的「设备动作与方向」入口 */
        val hasProgrammaticHandle: Boolean get() = opNames.isNotEmpty() || permissionNames.isNotEmpty()

        /** 枚举是否可信(枚举到 0 个 op 说明反射受限, 不能据此下结论) */
        val enumerationTrusted: Boolean get() = opCount > 0
    }

    @Volatile
    private var cached: ProbeResult? = null

    /** 自检(结果缓存; [force] = 重新枚举)。只在用户主动进入页面/点按钮时调用 */
    fun probe(force: Boolean = false): ProbeResult {
        cached?.let { if (!force) return it }
        val result = runCatching { doProbe() }.getOrElse { e ->
            LogUtils.d("DeviceOrientationGuard", e)
            ProbeResult(emptyList(), emptyList(), emptyList(), "枚举异常: ${e.message}", 0, now())
        }
        cached = result
        LogUtils.d(
            "DeviceOrientationGuard probe op=${result.opNames} sensorOp=${result.sensorOpNames} " +
                "perm=${result.permissionNames} opCount=${result.opCount} source=${result.opEnumSource}"
        )
        return result
    }

    /** 本机品牌串(小写), 用于按 ROM 给说法 */
    private fun brand(): String =
        (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).lowercase()

    private fun isVivo() = brand().contains("vivo") || brand().contains("iqoo")

    private fun isXiaomi() =
        brand().contains("xiaomi") || brand().contains("redmi") || brand().contains("poco")

    /**
     * 本机 ROM 对那一项的**原文叫法**。
     * ★ 真机实测(2026-10-02, vivo V2238A / OriginOS 16): vivo 在「传感器」分组里叫 **「访问设备动作与方向」**,
     *   而小米/多数说法是「获取设备动作与方向」—— 名字对不上用户就找不到, 所以按 ROM 给。
     */
    fun itemName(): String = if (isVivo()) "访问设备动作与方向" else "获取设备动作与方向"

    /**
     * 本机 ROM 里"仅开屏"那个可选值的**原文**。
     * ★ 真机实测: vivo 的弹窗三选项是 `允许 / **仅开屏时禁止** / 禁止`; 小米 HyperOS 3 是「仅开屏时拒绝」。
     */
    fun targetOptionName(): String = when {
        isVivo() -> "仅开屏时禁止"
        isXiaomi() -> "仅开屏时拒绝"
        else -> "仅开屏时禁止（部分 ROM 叫「仅开屏时拒绝」）"
    }

    /** 本机对应的菜单路径提示(按厂商给具体说法, 减少用户找不到) */
    fun menuHint(): String {
        return when {
            isVivo() ->
                "设置 → 权限管理 → 选该应用 → 传感器 → 「访问设备动作与方向」→ 选「仅开屏时禁止」"

            isXiaomi() ->
                "设置 → 应用设置 → 应用管理 → 权限管理 → 「获取设备动作与方向」→ 选「仅开屏时拒绝」"

            brand().contains("oppo") || brand().contains("oneplus") || brand().contains("realme") ->
                "设置 → 权限与隐私 → 权限管理 → 「获取设备动作与方向」→ 选「仅开屏时禁止」"

            brand().contains("huawei") || brand().contains("honor") ->
                "设置 → 应用 → 应用管理 → 权限 → 「获取设备动作与方向」→ 选「仅开屏时禁止」"

            else ->
                "设置 → 应用 → 权限管理 → 找「获取设备动作与方向」(vivo 叫「访问设备动作与方向」)→ 选「仅开屏时禁止」"
        }
    }

    /**
     * 跳到该应用的**系统权限页**(逐候选尝试, 成功即返回走了哪个入口)。
     * 全部失败时兜底到通用"应用详情页", 并让调用方提示用户手动往下点。
     *
     * 注意: 这里用 `runCatching` 静默试错(不能弹 toast), 因为候选入口里有多个是本机不存在的。
     */
    fun openPermissionPage(appId: String): String {
        for (target in pageTargets) {
            val intent = Intent().apply {
                component = ComponentName(target.pkg, target.activity)
                putExtra(target.extraKey, appId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { app.startActivity(intent) }.isSuccess) {
                LogUtils.d("DeviceOrientationGuard open pkg=$appId via=${target.label}/${target.activity}")
                return target.label
            }
        }
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$appId")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { app.startActivity(details) }
        LogUtils.d("DeviceOrientationGuard open pkg=$appId via=app-details(兜底)")
        return "app-details"
    }

    private fun now() = System.currentTimeMillis()

    private fun doProbe(): ProbeResult {
        val (allNames, source) = enumerateOpNames()
        val filtered = allNames.filter { name ->
            val lower = name.lowercase()
            opExcludes.none { lower.contains(it) }
        }
        val strong = filtered.filter { name ->
            val lower = name.lowercase()
            strongOpKeywords.any { lower.contains(it) }
        }
        val weak = filtered.filter { name ->
            val lower = name.lowercase()
            weakOpKeywords.any { lower.contains(it) } && !strong.contains(name)
        }
        val permissions = candidatePermissionNames.filter { existsPermission(it) }
        return ProbeResult(
            opNames = (strong + candidateOpNames.filter { strOpToOp(it) >= 0 }).distinct().sorted(),
            sensorOpNames = weak.distinct().sorted(),
            permissionNames = permissions,
            opEnumSource = source,
            opCount = allNames.size,
            at = now(),
        )
    }

    /** 候选权限名在本机是否真的存在 */
    private fun existsPermission(name: String): Boolean = runCatching {
        app.packageManager.getPermissionInfo(name, 0)
        true
    }.getOrDefault(false)

    /**
     * 反射枚举本机全部 AppOps op 名。
     *
     * Android 14+ 把 op 表重构成了 `AppOpInfo`(旧的 `sOpToString/sOpToSwitch/sOpNames` 可能为空),
     * 所以这里**多路尝试**并记录来源 —— 交接文档 §10.5 那次"枚举为空"就是因为只试了旧字段。
     */
    private fun enumerateOpNames(): Pair<List<String>, String> {
        val result = LinkedHashSet<String>()
        val sources = ArrayList<String>()
        // 1) 旧字段 sOpToString: String[](下标=code, 元素=OPSTR)
        runCatching {
            val field = AppOpsManager::class.java.getDeclaredField("sOpToString")
            field.isAccessible = true
            (field.get(null) as? Array<*>)?.filterIsInstance<String>()?.let {
                if (it.isNotEmpty()) {
                    result.addAll(it)
                    sources.add("sOpToString(${it.size})")
                }
            }
        }
        // 2) 旧字段 sOpToSwitch: Map<opStr, code>, key 即 OPSTR
        runCatching {
            val field = AppOpsManager::class.java.getDeclaredField("sOpToSwitch")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as? Map<*, *>)?.keys?.filterIsInstance<String>()?.let {
                if (it.isNotEmpty()) {
                    result.addAll(it)
                    sources.add("sOpToSwitch(${it.size})")
                }
            }
        }
        // 3) Android 14+ 的 sAppOpInfos: Array<AppOpInfo>, 元素里有 opStr/name 字段
        runCatching {
            val field = AppOpsManager::class.java.getDeclaredField("sAppOpInfos")
            field.isAccessible = true
            val array = field.get(null) as? Array<*> ?: return@runCatching
            val names = array.mapNotNull { info ->
                info ?: return@mapNotNull null
                val opStr = runCatching {
                    info.javaClass.getDeclaredField("opStr").apply { isAccessible = true }.get(info)
                        as? String
                }.getOrNull()
                opStr?.takeIf { it.isNotEmpty() }
            }
            if (names.isNotEmpty()) {
                result.addAll(names)
                sources.add("sAppOpInfos(${names.size})")
            }
        }
        // 4) opToPublicName(int) 逐 code 反查(最通用: code 是连续小整数, 公开 @hide 静态方法)
        runCatching {
            val method = AppOpsManager::class.java.getMethod(
                "opToPublicName", Int::class.javaPrimitiveType
            )
            val found = ArrayList<String>()
            for (code in 0..MAX_OP_CODE) {
                val name = runCatching { method.invoke(null, code) as? String }.getOrNull()
                if (!name.isNullOrEmpty() && name != "null") found.add(name)
            }
            if (found.isNotEmpty()) {
                result.addAll(found)
                sources.add("opToPublicName(${found.size})")
            }
        }
        return result.toList() to if (sources.isEmpty()) "无(反射被挡或字段已改名)" else sources.joinToString("+")
    }

    /** 反射 `AppOpsManager.strOpToOp(String)`(@hide public static)把 op 名转 code; 不存在返回 -1 */
    private fun strOpToOp(name: String): Int = runCatching {
        val method = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
        method.invoke(null, name) as Int
    }.getOrDefault(-1)

    private const val MAX_OP_CODE = 512
}
