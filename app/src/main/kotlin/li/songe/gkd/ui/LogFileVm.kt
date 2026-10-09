package li.songe.gkd.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.songe.gkd.store.storeFlow
import li.songe.gkd.ui.share.BaseViewModel
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.format
import li.songe.gkd.util.logFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * fork(v122): 「运行日志」页 VM —— 把 `files/log/gkd-YYYYMMDD.log` 直接读出来显示。
 *
 * ## 为什么需要它
 *
 * GKD 的运行日志([LogUtils])一直**只写文件**: 守卫的每一条决策(`ShakeGuard handled` /
 * `JumpGuard jump … send BACK` / `QuickApp block …` / `ClickGuard reject`)、异常、订阅异常等等
 * 全在里面, 但用户**在 App 里看不到** —— 以前只能靠电脑上 `adb pull` + `grep` 才能读。
 * 结果就是每次排障都要"连数据线 → 跑命令 → 贴日志", 用户自己完全无法自查。
 *
 * 这个页面把同一份文件**只读地**显示出来(带关键字筛选), 并提供一键清空, 让"刚才那一枪是谁打的"
 * 在手机上就能一眼看到。
 *
 * ## 读法(重要)
 *
 * 日志按 `\n\n` 分块(见 [LogUtils.logToFile] 的写法), 每块 = 一条日志:
 * 首行是 `HH:mm:ss.SSS tag, 线程, 位置`, 之后是该条的正文(可能多行)。
 * 所以这里按"块"分组, 而不是按行 —— 4 万行的文件按行渲染会又慢又碎。
 *
 * 文件很大时**只读尾部**(见 [MAX_READ_BYTES]): 排障要的永远是最近的一段,
 * 而真机一天能有 1~2 MB / 上万条, 全量读进内存再渲染毫无意义。
 */
class LogFileVm : BaseViewModel() {

    companion object {
        /** 单次最多读取的尾部字节数(超出就只显示最后这一段) */
        private const val MAX_READ_BYTES = 2 * 1024 * 1024

        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** 命中这些词的日志按"异常"标红 */
        private val errorWords =
            listOf("Exception", "FATAL", "failed", "崩溃", "失败", "error", "Error")

        /** 命中这些词的日志按"守卫决策"高亮 —— 排障时最常看的就是它们 */
        private val guardWords = listOf(
            "ShakeGuard", "JumpGuard", "FakeSkipGuard", "QuickApp", "ClickGuard",
            "GuardAssoc", "A11yAutoGuard", "DeviceOrientationGuard", "send BACK",
            "misclick", "veto", "no-ad-evidence", "not-guarded",
        )

        fun isErrorEntry(entry: String): Boolean = errorWords.any { entry.contains(it) }

        fun isGuardEntry(entry: String): Boolean = guardWords.any { entry.contains(it) }

        /**
         * fork(fok0030): 顶栏那排**守卫筛选标签** —— 排障时最常问的是"刚才那一枪是哪个守卫打的",
         * 以前只能在搜索框里手打 `ShakeGuard`; 现在点一下就行。
         *
         * 每个标签的 `words` 是"命中任意一个就算"的关键字(日志行的 tag 段就在里面)。
         * 加新守卫时**只改这一处**。
         */
        data class GuardChip(val label: String, val words: List<String>)

        val guardChips: List<GuardChip> = listOf(
            GuardChip("ShakeGuard", listOf("ShakeGuard")),
            GuardChip("JumpGuard", listOf("JumpGuard")),
            GuardChip("假跳过", listOf("FakeSkipGuard")),
            GuardChip("坐标守卫", listOf("ClickGuard")),
            GuardChip("快应用", listOf("QuickApp")),
            GuardChip("关联守护", listOf("GuardAssoc")),
            GuardChip("无障碍", listOf("A11yAutoGuard", "A11yService")),
            GuardChip("异常", errorWords),
        )

        /** chip 与搜索框是**叠加**关系: 两个都满足才显示 */
        fun matchesChip(entry: String, chip: GuardChip?): Boolean =
            chip == null || chip.words.any { entry.contains(it) }
    }

    /** 一个日志文件(日期 + 大小 + 修改时间) */
    data class LogItem(
        val name: String,
        val size: Long,
        val mtime: Long,
    )

    /** 现在的日志文件列表(按时间倒序, 最新在最前) */
    val filesFlow = MutableStateFlow<List<LogItem>>(emptyList())

    /** 当前正在看的文件名; null = 还没有日志 */
    val selectedFlow = MutableStateFlow<String?>(null)

    /** 当前文件的全部日志条目(**新的在前**, 与文件里的顺序相反) */
    val entriesFlow = MutableStateFlow<List<String>>(emptyList())

    /** 只读了文件尾部(内容被截断) */
    val truncatedFlow = MutableStateFlow(false)

    val loadingFlow = MutableStateFlow(false)

    /** 关键字筛选(空 = 不过滤) */
    val filterFlow = MutableStateFlow("")

    /** 顶栏是否处于"搜索/筛选"输入态 */
    val showSearchBarFlow = MutableStateFlow(false)

    /** fork(fok0030): 当前选中的守卫标签(null = 全部) */
    val chipFlow = MutableStateFlow<GuardChip?>(null)

    /** fork(fok0030): 日志保留天数(设置项, 默认 7 天) */
    fun retainDays(): Int = runCatching { storeFlow.value.logRetainDays }.getOrDefault(7).coerceIn(1, 30)

    /**
     * fork(fok0030): 按保留天数算出"将被清除"的文件(只算, 不删) —— 给页面弹确认框用。
     *
     * 口径: 文件名里的日期(`gkd-YYYYMMDD.log`)**早于**「今天 - (保留天数 - 1)」的才算过期;
     * 也就是"保留最近 N 天(含今天)"。名字不合规范的用文件修改时间兜底。
     */
    fun expiredPlan(days: Int = retainDays(), now: Long = System.currentTimeMillis()): List<LogItem> {
        val cutoff = (now - (days - 1).coerceAtLeast(0) * DAY_MS).format("yyyyMMdd")
        return filesFlow.value.filter { it.dayKey() < cutoff }
    }

    /** 按保留天数删除过期日志文件(在 IO 线程删, 删完自动刷新列表) */
    fun clearExpired(days: Int = retainDays(), onDone: (Int, Long) -> Unit) {
        viewModelScope.launch {
            val targets = expiredPlan(days)
            val res = withContext(Dispatchers.IO) {
                var count = 0
                var bytes = 0L
                targets.forEach { item ->
                    val f = File(logFolder, item.name)
                    bytes += item.size
                    if (runCatching { f.delete() }.getOrDefault(false)) count++
                }
                count to bytes
            }
            LogUtils.d("LogFile clear-expired days=$days deleted=${res.first} bytes=${res.second}")
            reload()
            onDone(res.first, res.second)
        }
    }

    private fun LogItem.dayKey(): String {
        val n = name.removePrefix("gkd-").removeSuffix(".log")
        return if (n.length == 8 && n.all { it.isDigit() }) n else mtime.format("yyyyMMdd")
    }

    init {
        reload()
    }

    /** 重新扫描文件列表并读取当前选中项 */
    fun reload() {
        viewModelScope.launch {
            loadingFlow.value = true
            val items = withContext(Dispatchers.IO) { listLogFiles() }
            filesFlow.value = items
            val keep = selectedFlow.value?.takeIf { name -> items.any { it.name == name } }
            val target = keep ?: items.firstOrNull()?.name
            selectedFlow.value = target
            loadEntries(target)
            loadingFlow.value = false
        }
    }

    /** 切换要看的日志文件(点当前那个 = 重新读一次) */
    fun select(name: String) {
        selectedFlow.value = name
        viewModelScope.launch {
            loadingFlow.value = true
            loadEntries(name)
            loadingFlow.value = false
        }
    }

    /**
     * 清空**全部**日志文件。
     *
     * 注意: 日志是**持续写入**的, 所以清空之后只要 App 还在跑, 新日志会重新建一个当天的文件 ——
     * 这是预期行为(清的是历史, 不是把记录功能关掉)。
     */
    fun clearAll(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                var count = 0
                runCatching {
                    logFolder.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".log") && f.delete()) {
                            count++
                        }
                    }
                }.onFailure { LogUtils.d("LogFile clear error", it) }
                count
            }
            LogUtils.d("LogFile clear deleted=$deleted")
            entriesFlow.value = emptyList()
            filesFlow.value = emptyList()
            selectedFlow.value = null
            truncatedFlow.value = false
            onDone(deleted)
        }
    }

    // ---------------- 内部 ----------------

    private fun listLogFiles(): List<LogItem> = runCatching {
        logFolder.listFiles()
            ?.filter { it.isFile && it.name.startsWith("gkd-") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { LogItem(name = it.name, size = it.length(), mtime = it.lastModified()) }
            ?: emptyList()
    }.getOrElse {
        LogUtils.d("LogFile list error", it)
        emptyList()
    }

    private fun loadEntries(name: String?) {
        val file = name?.let { File(logFolder, it) }
        if (file == null || !file.isFile) {
            entriesFlow.value = emptyList()
            truncatedFlow.value = false
            return
        }
        val text = runCatching { readTail(file) }.getOrElse {
            LogUtils.d("LogFile read error ${file.name}", it)
            null
        }
        if (text == null) {
            entriesFlow.value = emptyList()
            truncatedFlow.value = false
            return
        }
        // 一条日志 = 一个空行分隔的块; 文件里是旧→新, 显示时反过来(最新的在最上面)
        entriesFlow.value = text.split("\n\n")
            .map { it.trim('\n', '\r') }
            .filter { it.isNotBlank() }
            .asReversed()
        truncatedFlow.value = file.length() > MAX_READ_BYTES
    }

    /** 读文件(超长时只读尾部 [MAX_READ_BYTES] 字节, 并丢掉被截断的那半行) */
    private fun readTail(file: File): String {
        val length = file.length()
        if (length <= MAX_READ_BYTES) return file.readText()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - MAX_READ_BYTES)
            raf.readLine() // 丢掉可能被从中间截断的半行
            val sb = StringBuilder()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            return sb.toString()
        }
    }
}
