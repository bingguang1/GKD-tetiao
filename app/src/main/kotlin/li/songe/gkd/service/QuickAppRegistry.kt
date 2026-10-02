package li.songe.gkd.service

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import li.songe.gkd.app
import li.songe.gkd.appScope
import li.songe.gkd.store.quickAppEngineListFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry

/**
 * fork(v107): 一个"快应用引擎"(厂商预装的快应用运行环境)的描述。
 *
 * @param enabledState [PackageManager.getApplicationEnabledSetting] 的结果,
 *   被 [QuickAppController] 停用后会变成 `COMPONENT_ENABLED_STATE_DISABLED_USER`。
 * @param byDeeplink 响应 `hap://app/...` 链接(快应用引擎的必要能力, 最可靠的判据)
 * @param byName 包名带快应用特征词(用于**已被停用后**仍能把它留在列表里 —— 停用后组件不再响应 deeplink)
 * @param byUser 用户在「快应用引擎」页里手动添加/取消
 */
data class QuickAppEngine(
    val pkg: String,
    val label: String,
    val enabledState: Int,
    val isSystem: Boolean,
    val byDeeplink: Boolean,
    val byName: Boolean,
    val byUser: Boolean,
) {
    val disabled: Boolean
        get() = enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    val sourceText: String
        get() = buildList {
            if (byDeeplink) add("响应hap链接")
            if (byName) add("包名特征")
            if (byUser) add("手动添加")
        }.joinToString("+").ifEmpty { "未知" }
}

/**
 * fork(v107): 快应用引擎识别(「关闭快应用」功能的第一层)。
 *
 * 背景: 快应用是华为/荣耀/小米/OPPO/vivo 等厂商预装的"免安装小程序"运行环境(原生渲染, 不是 WebView),
 *   流氓广告会通过 `hap://app/<包名>` 这类 deeplink 把用户从开屏广告直接拉进快应用广告页,
 *   并在快应用里自动下载 APK。因为每个厂商的引擎包名各不相同且随版本变化, **写死包名表必然过时**。
 *
 * 因此识别用三条互补的通道:
 *   ① **deeplink 探测**(主力): 谁响应 `hap://app/...` 谁就是快应用引擎 —— 与包名/ROM 无关;
 *   ② **包名特征 + 已知厂商包名**: 停用引擎后它的组件不再响应 deeplink(①会失效), 这一条保证它
 *      仍然留在列表里(否则用户再也看不到"恢复"按钮);
 *   ③ **用户手动添加**(`store/quick_app_engine_list.txt`): 覆盖厂商自定义 scheme 等极端情况。
 *
 * 结果缓存在 [enginesFlow] 里, 无障碍热路径只用 [isEngine] 做集合判断(零开销)。
 */
object QuickAppRegistry {

    private const val TAG = "QuickApp"

    /** 快应用标准 deeplink: hap://app/<packageName>(见快应用官方文档 deeplink 一节) */
    private const val PROBE_URI = "hap://app/com.gkd.quickapp.probe"

    /** 「深度扫描」额外试探的 scheme: 厂商/衍生用法的兜底(探不到就是没有, 无副作用) */
    private val probeSchemes = listOf(
        "hap", "hapjs", "hwfastapp", "fastapp", "quickapp", "quickappcenter",
        "oaps", "instantapp", "vivoquickapp", "nearmequickapp",
    )

    /** 包名特征词 —— 按 `.` 分段匹配(段等于 hap 或段以 quickapp/fastapp/hybrid 开头), 避免 "chap" 这类误命中 */
    private val nameKeywords = listOf("quickapp", "fastapp", "hybrid")
    private val exactSegments = listOf("hap")

    /** 已知厂商引擎包名(候选, 实测为准; 命中只作为"包名特征"来源之一, 不依赖它做判断) */
    private val builtinIds = listOf(
        "com.miui.hybrid",
        "com.huawei.fastapp",
        "com.hihonor.fastapp",
        "com.nearme.instant.platform",
        "com.nearme.quickapp",
        "com.vivo.hybrid",
        "com.vivo.quickapp",
        "com.zte.quickapp",
    )

    val enginesFlow = MutableStateFlow<List<QuickAppEngine>>(emptyList())

    @Volatile
    private var engineSet: Set<String> = emptySet()

    /** 无障碍热路径用: 这个包是不是快应用引擎(纯集合判断) */
    fun isEngine(pkg: String): Boolean =
        engineSet.contains(pkg) || quickAppEngineListFlow.value.contains(pkg)

    fun refresh() = appScope.launchTry(Dispatchers.IO) {
        refreshNow()
    }

    /** 同步刷新(IO 线程调用, 由 [refresh] 或 UI 动作触发) */
    @Synchronized
    fun refreshNow(): List<QuickAppEngine> {
        // 提前触发名单 flow 的懒初始化, 让它的写回协程尽早开始收集(原因见 rememberEngines)
        quickAppEngineListFlow.value
        val list = runCatching { detect() }.getOrElse {
            LogUtils.d("$TAG detect failed", it)
            enginesFlow.value
        }
        enginesFlow.value = list
        engineSet = list.map { it.pkg }.toSet()
        rememberEngines(list)
        LogUtils.d("$TAG engines=${list.size} ${list.map { "${it.pkg}(${it.sourceText})" }}")
        return list
    }

    /**
     * 把识别到的引擎记进名单文件。
     * 目的: 引擎被停用后组件不再响应 hap://, "deeplink 探测"就找不到它了 —— 记下来才能一直显示
     * "已停用 + 恢复"按钮; 同时这个名单也作为 [isEngine] 的兜底判据。
     * 只在集合真的变化时写文件(避免每次刷新都落盘)。
     */
    private fun rememberEngines(list: List<QuickAppEngine>) {
        val known = quickAppEngineListFlow.value
        val add = list.map { it.pkg }.filterNot { known.contains(it) }
        if (add.isEmpty()) return
        // ⚠️ 实测踩到的坑: `createTextFlow` 内部是 `stateFlow.drop(1)`(丢掉**收集开始时**的那一个值)。
        //    我们这里是"启动后立刻写名单", 如果它的收集协程还没跑起来就 update, 这次新值会被当成初始值丢掉
        //    → 现象是"引擎识别到了、拦截也生效, 但 store/quick_app_engine_list.txt 一直不生成"。
        //    所以这里先读一次(触发懒初始化)再延后写入, 保证被丢掉的是初始值而不是我们的新值。
        appScope.launchTry {
            delay(300)
            quickAppEngineListFlow.update { it + add }
        }
    }

    // ---------------- 内部 ----------------

    private fun detect(): List<QuickAppEngine> {
        val pm = app.packageManager
        val sources = LinkedHashMap<String, MutableSet<String>>()
        val appInfos = HashMap<String, ApplicationInfo>()

        fun mark(pkg: String, src: String) {
            if (pkg.isEmpty()) return
            sources.getOrPut(pkg) { linkedSetOf() }.add(src)
        }

        // ① deeplink 探测: 谁响应 hap:// 链接谁就是快应用引擎
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROBE_URI)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { ri ->
                mark(ri.activityInfo?.packageName ?: "", "deeplink")
            }
        }.onFailure { LogUtils.d("$TAG deeplink probe failed", it) }

        // ② 包名特征 / 已知厂商包名(带 MATCH_DISABLED_COMPONENTS, 已被停用的引擎也要能列出来)
        runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS).forEach { ai ->
                val pkg = ai.packageName ?: return@forEach
                appInfos[pkg] = ai
                if (builtinIds.contains(pkg)) {
                    mark(pkg, "builtin")
                } else if (matchNameKeyword(pkg)) {
                    mark(pkg, "name")
                }
            }
        }.onFailure { LogUtils.d("$TAG scan installed failed", it) }

        // ③ 用户手动添加(含历史上被识别过的引擎)
        quickAppEngineListFlow.value.forEach { mark(it, "user") }

        return sources.mapNotNull { (pkg, set) ->
            toEngine(pkg, appInfos[pkg], set)
        }.sortedWith(
            compareBy<QuickAppEngine> { it.disabled }
                .thenBy { it.pkg }
        )
    }

    private fun matchNameKeyword(pkg: String): Boolean {
        val segments = pkg.lowercase().split('.')
        return segments.any { seg ->
            exactSegments.contains(seg) || nameKeywords.any { seg.startsWith(it) }
        }
    }

    private fun toEngine(
        pkg: String,
        cached: ApplicationInfo?,
        src: Set<String>,
    ): QuickAppEngine? = runCatching {
        val pm = app.packageManager
        val ai = cached ?: pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        val state = runCatching { pm.getApplicationEnabledSetting(pkg) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        QuickAppEngine(
            pkg = pkg,
            label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg),
            enabledState = state,
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            byDeeplink = src.contains("deeplink"),
            byName = src.contains("name") || src.contains("builtin"),
            byUser = src.contains("user"),
        )
    }.getOrNull()

    /**
     * 深度扫描(用户在引擎页手动触发): 把 [PROBE_URI] 之外的**候选 scheme** 也挨个探一遍。
     *
     * 为什么不直接枚举各应用的 intent-filter: `ActivityInfo.intentFilter` 是隐藏 API(编译期拿不到,
     * 走 hidden_api 又要动 remap 插件生成链路, 风险大于收益)。用候选 scheme 探测全部走公开 API,
     * 探不到就是没有 —— 代价只是多花几次 queryIntentActivities。
     */
    @Suppress("DEPRECATION")
    fun deepScan(): List<String> {
        val pm = app.packageManager
        val result = linkedSetOf<String>()
        probeSchemes.forEach { scheme ->
            runCatching {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("$scheme://app/com.gkd.quickapp.probe"),
                ).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { ri ->
                    ri.activityInfo?.packageName?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
                }
            }.onFailure { LogUtils.d("$TAG deepScan $scheme failed", it) }
        }
        LogUtils.d("$TAG deepScan found=${result.size} $result")
        return result.toList()
    }
}
