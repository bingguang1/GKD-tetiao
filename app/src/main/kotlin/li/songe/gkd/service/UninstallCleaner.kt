package li.songe.gkd.service

import android.Manifest
import android.app.AppOpsManagerHidden
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.songe.gkd.META
import li.songe.gkd.app
import li.songe.gkd.shizuku.UserServiceWrapper
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.store.createAnyFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.widget.WGkdWidgetProvider

/**
 * fork(fok0030): **卸载残留清理** —— 把"卸载 GKD特调版之后还会留在系统里的痕迹"列出来并清掉。
 *
 * ## 为什么必须由 App 自己在"卸载之前"做
 * 应用一旦被卸载, 它就**再也执行不了任何代码** —— 之后没有任何机会自清。所以唯一可行的方案是:
 * 在设置里给一个「卸载清理」页, 用户在卸载前点一下, 由 App 自己(它持有 `WRITE_SECURE_SETTINGS`
 * 与可选的 Shizuku)把痕迹清干净。
 *
 * ## 清什么(每一项都有真机取证, 见 `平板幽灵触控排查\GKD卸载残留清单.md`)
 * | 痕迹 | 真机实测 | 清理方式 |
 * |---|---|---|
 * | `secure/sysui_qs_tiles` 里的 `custom(li.songe.gkd/…)` 磁贴 | 卸载后**还在**(控制中心留一个点不动的空格子) | 剔除后写回 |
 * | `global` 里 `li.songe.gkd\|<op>` 权限键(真机 7 条, 值 -1) | 卸载后**还在** | 删除该键 |
 * | `secure/enabled_accessibility_services` / `accessibility_enabled` | 本 App 自己写的 | 摘掉自己的条目 |
 * | **快应用引擎被 `pm disable-user` / `appops … REQUEST_INSTALL_PACKAGES deny`** | 卸载后**还在**, 且**用户在系统里也找不回来**(最严重) | 按台账里的原值还原(需 Shizuku) |
 * | 桌面小组件 | 卸载后变成点不动的空白框 | **没有公开 API 可程序化移除** → 只能提示(见 [scan] 的只读项) |
 * | `dumpsys package` 的安装历史(`seq=…`) | 系统内部表, **没有公开 API** | 只做说明 |
 * | 电池白名单 / `GET_USAGE_STATS` / `WRITE_SECURE_SETTINGS` / appops / 应用数据目录 | 随卸载自动消失(真机实测干净) | 无需处理(页面写明) |
 *
 * ## 台账(可逆)
 * 只要本 App **改动过系统状态**, 就先往 [uninstallLedgerFlow] 记一条 `原值 → 新值`。
 * 目前接入的地方: [QuickAppController](停用引擎/禁止安装应用)、[UninstallCleaner.clean] 自己。
 * 「卸载清理」页上的「恢复改动」按台账倒序还原 —— 于是"停用引擎"这类破坏性操作永远可逆。
 */
object UninstallCleaner {

    private const val TAG = "UninstallCleaner"

    /** 真机上 ROM 会以 `<包名>|<op>` 为键写进 global 的那批权限操作(见残留清单: 实测 7 条) */
    private val knownOpKeys = listOf(
        "camera", "location", "read_media_aural", "read_media_images",
        "read_media_video", "record_audio", "wifi",
    )

    /**
     * 这些键**有专门的处理项**(磁贴 / 无障碍), 不能在"扫含包名的键"那一步被整条删掉 ——
     * 否则会把 `enabled_accessibility_services` 里**别的应用**的条目一起抹了(真踩过的坑)。
     */
    private val dedicatedKeys = setOf(
        "sysui_qs_tiles",
        "enabled_accessibility_services",
        "accessibility_enabled",
        "accessibility_button_targets",
        "accessibility_shortcut_target_service",
        "touch_exploration_enabled",
    )

    // ---------------- 台账 ----------------

    @Serializable
    data class LedgerEntry(
        val at: Long,
        /** qs_tiles / global_key / secure_key / system_key / a11y_services / a11y_enabled / engine_disabled / engine_appop */
        val kind: String,
        /** 键名或包名 */
        val target: String,
        /** 改动前的原值("" = 原本不存在) */
        val before: String,
        /** 我们写进去的值("" = 删除) */
        val after: String,
        /** 谁改的: quickapp / uninstall-clean / a11y-guard */
        val by: String,
        val restored: Boolean = false,
    )

    @Serializable
    data class LedgerData(val entries: List<LedgerEntry> = emptyList())

    /** 台账(JSON 文件 `store/uninstall_ledger.json`, 随 App 存续, 卸载即消失) */
    val uninstallLedgerFlow by lazy {
        createAnyFlow(key = "uninstall_ledger", default = { LedgerData() })
    }

    fun ledger(): List<LedgerEntry> = uninstallLedgerFlow.value.entries

    /** 记一条"改动前 → 改动后"(同一 target/kind 只更新最后一条未还原记录, 避免刷屏) */
    fun record(kind: String, target: String, before: String, after: String, by: String) {
        val list = ledger().toMutableList()
        val idx = list.indexOfLast { it.kind == kind && it.target == target && !it.restored }
        val entry = LedgerEntry(
            at = System.currentTimeMillis(),
            kind = kind,
            target = target,
            before = before,
            after = after,
            by = by,
        )
        if (idx >= 0) {
            list[idx] = entry
        } else {
            list.add(entry)
        }
        // 只留最近 200 条
        uninstallLedgerFlow.value = LedgerData(list.takeLast(200))
        LogUtils.d("$TAG ledger record kind=$kind target=$target before=${before.take(80)} after=${after.take(80)} by=$by")
    }

    /** 还没还原的改动(给页面显示"有哪些东西被我改过") */
    fun pendingRestore(): List<LedgerEntry> = ledger().filter { !it.restored }

    private fun markRestored(kind: String, target: String) {
        val list = ledger().map {
            if (it.kind == kind && it.target == target) it.copy(restored = true) else it
        }
        uninstallLedgerFlow.value = LedgerData(list)
    }

    /**
     * 按台账**倒序**还原所有未还原的改动。
     * 返回逐条的人类可读结果(成功/失败原因原样带上, 不静默)。
     */
    suspend fun restoreAll(): List<String> = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        val cr = app.contentResolver
        val entries = ledger().filter { !it.restored }.sortedByDescending { it.at }
        if (entries.isEmpty()) return@withContext listOf("没有需要恢复的改动")
        entries.forEach { e ->
            val r = runCatching {
                when (e.kind) {
                    "qs_tiles" -> {
                        val (cur, _) = readKey("secure", "sysui_qs_tiles")
                        val merged = if (e.before.isBlank()) cur else e.before
                        writeKey("secure", "sysui_qs_tiles", merged)?.let { throw IllegalStateException(it) }
                        "恢复 sysui_qs_tiles"
                    }

                    "global_key" -> {
                        writeKey("global", e.target, e.before.ifEmpty { null })
                            ?.let { throw IllegalStateException(it) }
                        "恢复 global ${e.target}=${e.before}"
                    }

                    "secure_key" -> {
                        writeKey("secure", e.target, e.before.ifEmpty { null })
                            ?.let { throw IllegalStateException(it) }
                        "恢复 secure ${e.target}=${e.before}"
                    }

                    // system 命名空间需要 WRITE_SETTINGS(不是 WRITE_SECURE_SETTINGS), 只能走 Shizuku
                    "system_key" -> {
                        writeKey("system", e.target, e.before.ifEmpty { null })
                            ?.let { throw IllegalStateException(it) }
                        "恢复 system ${e.target}=${e.before}"
                    }

                    "a11y_services" -> {
                        writeKey(
                            "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, e.before.ifEmpty { null }
                        )?.let { throw IllegalStateException(it) }
                        // 恢复无障碍启用列表 = 用户还想要 GKD 的无障碍, 于是把清理时置的"手动关闭"标记清掉,
                        // 让 A11yAutoGuard 重新接管(见 clean() 里那段说明)
                        storeFlow.value = storeFlow.value.copy(manualA11yOff = false)
                        "恢复无障碍服务列表(并恢复自动守护)"
                    }

                    "a11y_enabled" -> {
                        writeKey("secure", Settings.Secure.ACCESSIBILITY_ENABLED, e.before.ifEmpty { "0" })
                            ?.let { throw IllegalStateException(it) }
                        "恢复无障碍总开关=${e.before}"
                    }

                    "engine_disabled" -> {
                        if (e.before == "disabled") {
                            execPrivileged("pm disable-user --user 0 ${e.target}")?.let { throw IllegalStateException(it) }
                            "重新停用 ${e.target}"
                        } else {
                            execPrivileged("pm enable ${e.target}")?.let { throw IllegalStateException(it) }
                            "恢复启用 ${e.target}"
                        }
                    }

                    "engine_appop" -> {
                        if (e.before == "deny" || e.before.isBlank() || e.before == "unknown") {
                            "跳过(${e.target} 的原值未知或本来就是 deny)"
                        } else {
                            execPrivileged("cmd appops set ${e.target} REQUEST_INSTALL_PACKAGES ${e.before}")
                                ?.let { throw IllegalStateException(it) }
                            "恢复 ${e.target} 安装权限=${e.before}"
                        }
                    }

                    else -> "未知类型 ${e.kind}(跳过)"
                }
            }
            r.onSuccess {
                markRestored(e.kind, e.target)
                results.add("✓ ${e.kind} ${e.target}: $it")
                LogUtils.d("$TAG restore ok kind=${e.kind} target=${e.target} -> $it")
            }.onFailure {
                results.add("✗ ${e.kind} ${e.target}: ${it.message}")
                LogUtils.d("$TAG restore fail kind=${e.kind} target=${e.target}", it)
            }
        }
        results
    }

    // ---------------- 扫描 ----------------

    /** 一条残留(或一条"这项不用清/清不掉, 只是告诉你") */
    data class ResidueItem(
        val id: String,
        val title: String,
        val detail: String,
        /** 能不能清; false = 只读说明项 */
        val cleanable: Boolean,
        /** 缺什么才能清: "" / "WRITE_SECURE_SETTINGS" / "Shizuku" / "桌面" */
        val need: String = "",
        /** 多行细节(键值原文, 便于复制/核对) */
        val extra: String = "",
    )

    fun hasWriteSecureSettings(): Boolean = ContextCompat.checkSelfPermission(
        app, Manifest.permission.WRITE_SECURE_SETTINGS
    ) == PackageManager.PERMISSION_GRANTED

    fun shizukuReady(): Boolean =
        storeFlow.value.enableShizuku && shizukuContextFlow.value.serviceWrapper != null

    private fun wrapper(): UserServiceWrapper? =
        if (storeFlow.value.enableShizuku) shizukuContextFlow.value.serviceWrapper else null

    /** 用 Shizuku 跑一条命令; 失败返回错误文本, 成功返回 null */
    private fun execPrivileged(cmd: String): String? {
        val w = wrapper() ?: return "未连接 Shizuku"
        val r = runCatching { w.execCommandForResult(cmd) }.getOrElse { e ->
            return "命令异常: ${e.message}"
        }
        LogUtils.d("$TAG cmd=[$cmd] ok=${r.ok} code=${r.code} err=${r.error?.trim()?.take(160)}")
        return if (r.ok) null else (r.error?.trim()?.takeIf { it.isNotEmpty() } ?: "命令失败 code=${r.code}")
    }

    suspend fun scan(): List<ResidueItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<ResidueItem>()
        val cr = app.contentResolver
        val appId = META.appId
        val writeOk = hasWriteSecureSettings()

        // ① 控制中心磁贴
        runCatching {
            val (tilesRaw, tilesErr) = readKey("secure", "sysui_qs_tiles")
            val tiles = tilesRaw ?: ""
            val bad = tiles.split(',').map { it.trim() }.filter { it.isNotEmpty() && it.contains(appId) }
            items.add(
                ResidueItem(
                    id = "qs_tile",
                    title = "控制中心的 GKD 磁贴",
                    detail = when {
                        tilesErr != null ->
                            "读不到这个键(${tilesErr.take(120)}) —— Android 14+ 起应用被禁止读取 sysui_qs_tiles; " +
                                "连了 Shizuku 才能自动清, 否则请用下面的 adb 命令"
                        bad.isEmpty() -> "未发现(控制中心里没有本应用的磁贴)"
                        else ->
                            "发现 ${bad.size} 个: 卸载后会留下永远点不动的空格子, 并让开关页变长需要滚动"
                    },
                    cleanable = bad.isNotEmpty(),
                    need = if (!bad.isEmpty() && tilesErr != null && !shizukuReady()) "Shizuku"
                    else if (!bad.isEmpty() && !writeOk) "WRITE_SECURE_SETTINGS" else "",
                    extra = bad.joinToString("\n"),
                )
            )
        }.onFailure { LogUtils.d("$TAG scan qs_tiles error", it) }

        // ② global 权限键(`<包名>|<op>`)
        runCatching {
            val hits = scanNamespace("global", appId)
            items.add(
                ResidueItem(
                    id = "global_keys",
                    title = "global 里的权限记录",
                    detail = if (hits.isEmpty()) "未发现" else "发现 ${hits.size} 条(值多为 -1, 用户不可见但确实是残留)",
                    cleanable = hits.isNotEmpty(),
                    need = if (writeOk || shizukuReady()) "" else "WRITE_SECURE_SETTINGS",
                    extra = hits.joinToString("\n"),
                )
            )
        }.onFailure { LogUtils.d("$TAG scan global error", it) }

        // ③ secure / system 里其它含包名的键(磁贴与无障碍有专门项, 已在 scanNamespace 里排除)
        listOf("secure", "system").forEach { ns ->
            runCatching {
                val hits = scanNamespace(ns, appId)
                items.add(
                    ResidueItem(
                        id = "${ns}_keys",
                        title = "$ns 里其它含包名的键",
                        detail = if (hits.isEmpty()) "未发现" else "发现 ${hits.size} 条",
                        cleanable = hits.isNotEmpty(),
                        need = when {
                            ns == "secure" && writeOk -> ""
                            ns == "system" -> "Shizuku"
                            else -> "WRITE_SECURE_SETTINGS"
                        },
                        extra = hits.joinToString("\n"),
                    )
                )
            }.onFailure { LogUtils.d("$TAG scan $ns error", it) }
        }

        // ④ 无障碍
        runCatching {
            val (listRaw, listErr) = readKey("secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val list = listRaw ?: ""
            val mine = list.split(':').map { it.trim() }.filter { it.contains(appId) }
            val enabled = readKey("secure", Settings.Secure.ACCESSIBILITY_ENABLED).first?.toIntOrNull() ?: 0
            items.add(
                ResidueItem(
                    id = "a11y",
                    title = "无障碍服务启用列表",
                    detail = when {
                        listErr != null -> "读不到启用列表(${listErr.take(80)})"
                        mine.isEmpty() -> "本应用未在启用列表里(总开关=$enabled)"
                        else -> "本应用有 ${mine.size} 个服务在启用列表里(总开关=$enabled); " +
                            "清理会把它摘掉并关闭总开关(清理期间「无障碍自动守护」不会再把它拉回来)"
                    },
                    cleanable = mine.isNotEmpty(),
                    need = if (writeOk || shizukuReady()) "" else "WRITE_SECURE_SETTINGS",
                    extra = mine.joinToString("\n"),
                )
            )
        }.onFailure { LogUtils.d("$TAG scan a11y error", it) }

        // ⑤ 快应用引擎(台账里记过的改动 —— 这是卸载后**最严重**的一项)
        runCatching {
            val pend = pendingRestore().filter { it.kind == "engine_disabled" || it.kind == "engine_appop" }
            items.add(
                ResidueItem(
                    id = "engine",
                    title = "快应用引擎的系统改动",
                    detail = if (pend.isEmpty()) "台账里没有未恢复的引擎改动" else
                        "有 ${pend.size} 条未恢复: 卸载后引擎会**一直保持停用/不能安装应用**, 系统里也找不回来",
                    cleanable = pend.isNotEmpty(),
                    need = if (shizukuReady()) "" else "Shizuku",
                    extra = pend.joinToString("\n") { "${it.kind} ${it.target} 原值=${it.before.ifEmpty { "(不存在)" }}" },
                )
            )
        }.onFailure { LogUtils.d("$TAG scan engine error", it) }

        // ⑥ 只读项: 桌面小组件
        runCatching {
            val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(
                ComponentName(app, WGkdWidgetProvider::class.java)
            )
            items.add(
                ResidueItem(
                    id = "info_widget",
                    title = "桌面小组件(${ids?.size ?: 0} 个)",
                    detail = if (ids.isEmpty()) "桌面上没有本应用的小组件" else
                        "桌面上有 ${ids.size} 个: 卸载后会变成点不动的空白框 —— " +
                            "系统没有给应用「移除自己小组件」的接口, 只能卸载后在桌面上长按那个空白框删掉",
                    cleanable = false,
                    need = "桌面",
                )
            )
        }.onFailure { LogUtils.d("$TAG scan widget error", it) }

        // ⑦ 只读项: 不需要处理的(避免用户反复怀疑)
        items.add(
            ResidueItem(
                id = "info_auto",
                title = "随卸载自动消失的痕迹",
                detail = "电池优化白名单 / 使用情况访问权限 / WRITE_SECURE_SETTINGS 授权 / appops 记录 / " +
                    "应用数据目录(/sdcard/Android/data/${META.appId}) —— 这些系统会随卸载自动清理, 无需处理",
                cleanable = false,
            )
        )
        // ⑧ 只读项: 清不掉的安装历史
        items.add(
            ResidueItem(
                id = "info_history",
                title = "系统安装历史(清不掉, 无害)",
                detail = "`dumpsys package` 里的安装序号表与安装会话历史会留一条包名 —— " +
                    "这是系统内部表, 没有公开 API 可删, 删了反而破坏表完整性; 它不产生任何行为影响",
                cleanable = false,
            )
        )
        items
    }

    /**
     * 枚举某个命名空间里含包名的键。
     *
     * 两条路:
     *   1. **有 Shizuku** → `settings list <ns>` 全量扫描(**唯一能保证不漏的方式**);
     *   2. 没有 Shizuku → 用 `AppOpsManagerHidden.opToName(code)` 把 op 名枚举出来, 逐条探测
     *      `<包名>|<op>`(真机上残留键名就是 op 名, 如 `li.songe.gkd|camera`), 再叠加几个已知键名。
     */
    private fun scanNamespace(ns: String, appId: String): List<String> {
        fun keep(line: String): Boolean {
            if (line.isBlank() || !line.contains(appId)) return false
            val key = line.substringBefore('=')
            return key !in dedicatedKeys
        }
        wrapper()?.let { w ->
            val r = runCatching { w.execCommandForResult("settings list $ns") }.getOrNull()
            if (r != null && r.ok) {
                return r.result.lineSequence().map { it.trim() }.filter { keep(it) }.toList()
            }
        }
        val cr = app.contentResolver
        fun get(key: String): String? = when (ns) {
            "global" -> Settings.Global.getString(cr, key)
            "secure" -> Settings.Secure.getString(cr, key)
            else -> runCatching { Settings.System.getString(cr, key) }.getOrNull()
        }
        val keys = LinkedHashSet<String>()
        knownOpKeys.forEach { keys.add("$appId|$it") }
        for (code in 0..MAX_OP_CODE) {
            val name = runCatching { AppOpsManagerHidden.opToName(code) }.getOrNull() ?: continue
            if (name.isBlank()) continue
            keys.add("$appId|$name")
        }
        return keys.mapNotNull { k -> get(k)?.let { "$k=$it" } }
    }

    private const val MAX_OP_CODE = 200

    /**
     * 读一个系统设置键。
     *
     * ★★ fok0030 MuMu 实测(Android 15)踩到的真问题:
     * `Settings.Secure.getString(cr, "sysui_qs_tiles")` 会抛
     * `SecurityException: Settings key: <sysui_qs_tiles> is only readable to apps with targetSdkVersion <= 33`
     * —— 也就是说 **Android 14+ 上应用连读都读不到这个键**, 拿 WRITE_SECURE_SETTINGS 也没用。
     * 所以这里统一走三级: 直接读 → Shizuku(`settings get`) → 如实返回错误(页面上明说"要 Shizuku/电脑 adb")。
     */
    private fun readKey(ns: String, key: String): Pair<String?, String?> {
        val cr = app.contentResolver
        val direct = runCatching {
            when (ns) {
                "global" -> Settings.Global.getString(cr, key)
                "secure" -> Settings.Secure.getString(cr, key)
                else -> Settings.System.getString(cr, key)
            }
        }
        direct.getOrNull()?.let { return it to null }
        if (direct.isSuccess) return null to null // 键不存在(不是错误)
        val directErr = direct.exceptionOrNull()?.message ?: "读取失败"
        if (shizukuReady()) {
            val w = wrapper()
            val r = w?.let { runCatching { it.execCommandForResult("settings get $ns '$key'") }.getOrNull() }
            if (r != null && r.ok) {
                val v = r.result.trim()
                return (if (v.isEmpty() || v == "null") null else v) to null
            }
        }
        return null to directErr
    }

    /**
     * 写/删一个系统设置键(value = null 表示删除)。
     * 返回 null 表示成功, 否则返回失败原因(原样带出, 不静默)。同样三级: 直接写 → Shizuku → 失败原因。
     */
    private fun writeKey(ns: String, key: String, value: String?): String? {
        val cr = app.contentResolver
        // ★★ 删键的顺序是"真删优先"(2026-10-09 在联想平板 Android16/ZUXOS 上实测):
        //   ① `putString(key, null)` **只把值写成 null, 行还留在表里** —— `settings list global | grep 包名`
        //      仍能看到 `li.songe.gkd|camera=null`, 而残留清单的验收口径是"这一类应为 0 条"。
        //   ② `ContentResolver.delete(CONTENT_URI, "name=?", …)` 才是**删行**(真机实测: 删完 `settings list`
        //      里该行消失, `settings get` 为 null)。这就是 `adb shell content delete --uri …` 的同一条路。
        //   ③ 有 Shizuku 时还可以退回 `settings delete`(同样真删)。
        if (value == null) {
            val deleteOk = runCatching {
                when (ns) {
                    "global" -> cr.delete(Settings.Global.CONTENT_URI, "name=?", arrayOf(key))
                    "secure" -> cr.delete(Settings.Secure.CONTENT_URI, "name=?", arrayOf(key))
                    else -> cr.delete(Settings.System.CONTENT_URI, "name=?", arrayOf(key))
                }
            }.getOrDefault(0)
            if (deleteOk > 0 && readKey(ns, key).first == null) return null
            if (shizukuReady()) {
                val err = execPrivileged("settings delete $ns '$key'")
                if (err == null && readKey(ns, key).first == null) return null
            }
        }
        val direct = runCatching {
            when (ns) {
                "global" -> Settings.Global.putString(cr, key, value)
                "secure" -> Settings.Secure.putString(cr, key, value)
                else -> Settings.System.putString(cr, key, value)
            }
        }
        if (direct.getOrDefault(false)) {
            val now = readKey(ns, key).first
            if (now == value) return null
        }
        // 直接写失败(权限/门禁)或没生效 ⇒ 用 Shizuku 的 settings 命令
        val cmd = if (value == null) {
            "settings delete $ns '$key'"
        } else {
            "settings put $ns '$key' '$value'"
        }
        val err = execPrivileged(cmd)
        if (err != null) return err
        val now = readKey(ns, key).first
        return if (now == value) null else "写入后校验不一致(现在=$now)"
    }

    // ---------------- 清理 ----------------

    /**
     * 清理选中的项(逐项如实回显成功/失败) —— 每个成功项都会先往台账记原值。
     * 顺序有意固定: 先外围(磁贴/权限键) 后无障碍(摘掉无障碍之后本应用就不再是无障碍服务, 不影响用
     * WRITE_SECURE_SETTINGS 继续写 secure)。
     */
    suspend fun clean(ids: Set<String>): List<String> = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        val cr = app.contentResolver
        val appId = META.appId

        if ("qs_tile" in ids) {
            runCatching {
                val (tilesRaw, tilesErr) = readKey("secure", "sysui_qs_tiles")
                if (tilesRaw == null) {
                    results.add(
                        "✗ 控制中心磁贴: 读不到 sysui_qs_tiles" +
                            (tilesErr?.let { "(${it.take(120)})" } ?: "") +
                            " —— Android 14+ 需要 Shizuku 或电脑 adb 才能清"
                    )
                    return@runCatching
                }
                val tiles = tilesRaw
                val bad = tiles.split(',').map { it.trim() }.filter { it.isNotEmpty() && it.contains(appId) }
                if (bad.isEmpty()) {
                    results.add("· 控制中心磁贴: 无需清理")
                } else {
                    val after = tiles.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.contains(appId) }
                        .joinToString(",")
                    record("qs_tiles", "sysui_qs_tiles", tiles, after, "uninstall-clean")
                    val err = writeKey("secure", "sysui_qs_tiles", after)
                    val left = readKey("secure", "sysui_qs_tiles").first?.contains(appId) == true
                    results.add(
                        if (err == null && !left) "✓ 控制中心磁贴: 已剔除 ${bad.size} 个"
                        else "✗ 控制中心磁贴: 未清掉(${err ?: "校验仍含本应用"})"
                    )
                }
            }.onFailure { results.add("✗ 控制中心磁贴: ${it.message}") }
        }

        listOf("global_keys" to "global", "secure_keys" to "secure", "system_keys" to "system").forEach { (id, ns) ->
            if (id !in ids) return@forEach
            runCatching {
                val hits = scanNamespace(ns, appId)
                if (hits.isEmpty()) {
                    results.add("· $ns 含包名的键: 无需清理")
                    return@runCatching
                }
                var okCount = 0
                val fails = mutableListOf<String>()
                hits.forEach { line ->
                    val key = line.substringBefore('=')
                    val value = line.substringAfter('=', "")
                    val err = deleteKey(ns, key, value)
                    if (err == null) okCount++ else fails.add("$key($err)")
                }
                results.add(
                    if (fails.isEmpty()) "✓ $ns 含包名的键: 已删除 $okCount 条"
                    else "部分成功: $ns 删除 $okCount 条, 失败 ${fails.size} 条 → ${fails.joinToString("; ")}"
                )
            }.onFailure { results.add("✗ $ns 键清理异常: ${it.message}") }
        }

        if ("a11y" in ids) {
            runCatching {
                val (curRaw, curErr) = readKey("secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                if (curRaw == null && curErr != null) {
                    results.add("✗ 无障碍: 读不到启用列表(${curErr.take(100)})")
                    return@runCatching
                }
                val cur = curRaw ?: ""
                val mine = cur.split(':').map { it.trim() }.filter { it.isNotEmpty() && it.contains(appId) }
                if (mine.isEmpty()) {
                    results.add("· 无障碍: 本应用不在启用列表里, 无需清理")
                } else {
                    // ★★ MuMu 实测踩到: 我们刚把无障碍服务从启用列表里摘掉, A11yAutoGuard 几秒内就把它**写回来了**
                    //    (那是守护的本职工作)。所以清理前先置"用户手动关闭"标记(manualA11yOff)让守护放手 ——
                    //    卸载清理期间无障碍本来就该保持关闭; 「恢复改动」时会把这个标记清掉。
                    storeFlow.value = storeFlow.value.copy(manualA11yOff = true)
                    val after = cur.split(':').map { it.trim() }.filter { it.isNotEmpty() && !it.contains(appId) }
                        .joinToString(":")
                    record("a11y_services", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cur, after, "uninstall-clean")
                    val err1 = writeKey(
                        "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, after.ifEmpty { null }
                    )
                    var closed = false
                    val enabledNow = readKey("secure", Settings.Secure.ACCESSIBILITY_ENABLED).first?.toIntOrNull() ?: 0
                    if (after.isEmpty() && enabledNow != 0) {
                        record("a11y_enabled", Settings.Secure.ACCESSIBILITY_ENABLED, enabledNow.toString(), "0", "uninstall-clean")
                        writeKey("secure", Settings.Secure.ACCESSIBILITY_ENABLED, "0")
                        closed = true
                    }
                    val left = readKey("secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                        .first?.contains(appId) == true
                    results.add(
                        if (err1 == null && !left) {
                            "✓ 无障碍: 已从启用列表里摘除 ${mine.size} 个本应用服务" +
                                if (closed) ", 并把总开关置 0" else ""
                        } else {
                            "✗ 无障碍: 未清掉(${err1 ?: "校验仍在列表里"})"
                        }
                    )
                }
            }.onFailure { results.add("✗ 无障碍清理异常: ${it.message}") }
        }

        if ("engine" in ids) {
            val pend = pendingRestore().filter { it.kind == "engine_disabled" || it.kind == "engine_appop" }
            if (pend.isEmpty()) {
                results.add("· 快应用引擎: 没有需要还原的改动")
            } else {
                val lines = mutableListOf<String>()
                pend.forEach { e ->
                    val cmd = when {
                        e.kind == "engine_disabled" && e.before == "disabled" ->
                            "pm disable-user --user 0 ${e.target}" to "保持停用"
                        e.kind == "engine_disabled" -> "pm enable ${e.target}" to "恢复启用"
                        e.before == "deny" || e.before.isBlank() || e.before == "unknown" -> null to "跳过(原值未知)"
                        else -> "cmd appops set ${e.target} REQUEST_INSTALL_PACKAGES ${e.before}" to "恢复安装权限=${e.before}"
                    }
                    if (cmd.first == null) {
                        lines.add("· ${e.target}: ${cmd.second}")
                        if (e.kind == "engine_appop") markRestored(e.kind, e.target)
                    } else {
                        val err = execPrivileged(cmd.first!!)
                        if (err == null) {
                            markRestored(e.kind, e.target)
                            lines.add("✓ ${e.target}: ${cmd.second}")
                        } else {
                            lines.add("✗ ${e.target}: ${cmd.second} 失败 → $err")
                        }
                    }
                }
                results.addAll(lines)
            }
        }
        results
    }

    /**
     * 删除一个设置键。
     * 走统一的 [writeKey](直接写 null → Shizuku `settings delete` → 如实返回失败原因);
     * 成功才记台账(失败还记台账的话,"恢复"会去恢复一个根本没改动过的东西)。
     */
    private fun deleteKey(ns: String, key: String, before: String): String? {
        val err = writeKey(ns, key, null)
        if (err == null) {
            record("${ns}_key", key, before, "", "uninstall-clean")
        }
        return err
    }

    /** 给"没跑清理就卸载了"的场景用: 一页可复制的 adb 命令(不新增脚本文件) */
    fun adbFallbackCommands(): String = buildString {
        val appId = META.appId
        appendLine("# 1. 控制中心磁贴: 先看现值, 手工剔除含 $appId 的项后再写回")
        appendLine("adb shell settings get secure sysui_qs_tiles")
        appendLine()
        appendLine("# 2. global 里的权限键(键名含 |, 必须用单引号包住)")
        knownOpKeys.forEach { appendLine("adb shell settings delete global '$appId|$it'") }
        appendLine()
        appendLine("# 3. 无障碍启用列表(把含 $appId 的条目摘掉)")
        appendLine("adb shell settings get secure enabled_accessibility_services")
        appendLine()
        appendLine("# 4. 快应用引擎(把被停用的引擎恢复; 先看有哪些)")
        appendLine("adb shell pm list packages -d | grep -iE 'hap|quickapp|hybrid|hyperengine'")
        appendLine("adb shell pm enable <引擎包名>")
        appendLine("adb shell cmd appops set <引擎包名> REQUEST_INSTALL_PACKAGES default")
        appendLine()
        appendLine("# 5. 复核(三处都应为 0 条)")
        appendLine("adb shell settings list secure | grep $appId")
        appendLine("adb shell settings list global | grep $appId")
        appendLine("adb shell settings list system | grep $appId")
    }
}
