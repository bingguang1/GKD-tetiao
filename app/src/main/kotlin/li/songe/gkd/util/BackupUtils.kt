package li.songe.gkd.util

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.songe.gkd.data.AppConfig
import li.songe.gkd.data.CategoryConfig
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsConfig
import li.songe.gkd.data.SubsItem
import li.songe.gkd.db.DbSet
import li.songe.gkd.store.a11yScopeAppListFlow
import li.songe.gkd.store.actionCountFlow
import li.songe.gkd.store.blockA11yAppListFlow
import li.songe.gkd.store.blockMatchAppListFlow
import li.songe.gkd.store.storeFlow
import java.io.File

@Serializable
private data class DbData(
    val subsItems: List<SubsItem>?,
    val subsConfigs: List<SubsConfig>?,
    val categoryConfigs: List<CategoryConfig>?,
    val appConfigs: List<AppConfig>?,
)

object BackupUtils {
    private val backupStoreFlowList
        get() = listOf(
            storeFlow,
            actionCountFlow,
            blockMatchAppListFlow,
            blockA11yAppListFlow,
            a11yScopeAppListFlow,
        )

    suspend fun exportBackUpData(): File {
        val tempDir = createGkdTempDir()
        tempDir.resolve("store").run {
            mkdir()
            backupStoreFlowList.forEach { storeFlow ->
                resolve(storeFlow.filename).writeText(storeFlow.encodeSelf())
            }
        }
        tempDir.resolve("db.json").writeText(
            json.encodeToString(
                DbData(
                    subsItems = DbSet.subsItemDao.queryAll(),
                    subsConfigs = DbSet.subsConfigDao.queryAll(),
                    categoryConfigs = DbSet.categoryConfigDao.queryAll(),
                    appConfigs = DbSet.appConfigDao.queryAll(),
                )
            )
        )
        tempDir.resolve("subscription").run {
            mkdir()
            subsMapFlow.value.values.forEach { subs ->
                resolve("${subs.id}.json").writeText(json.encodeToString(subs))
            }
        }
        val file = sharedDir.resolve("gkd-backup-${System.currentTimeMillis()}.zip")
        ZipUtils.zipFiles(tempDir.listFiles()!!.filterNotNull(), file)
        tempDir.deleteRecursively()
        return file
    }

    suspend fun importBackUpData(uri: Uri) {
        val bytes = UriUtils.uri2Bytes(uri)
        importBackUpData(bytes)
    }

    // 字节级导入, 供"内置备份首启自动导入"复用
    suspend fun importBackUpData(bytes: ByteArray) {
        toast("导入备份中...")
        val tempDir = createGkdTempDir()
        val zipFile = tempDir.resolve("file.zip").apply {
            writeBytes(bytes)
        }
        val unzipDir = tempDir.resolve("unzip")
        try {
            ZipUtils.unzipFile(zipFile, unzipDir)
            zipFile.delete()
        } catch (e: Exception) {
            LogUtils.d("importBackUpData.unzipFile", e)
            toast("解压失败，非法备份文件")
            tempDir.deleteRecursively()
            return
        }
        backupStoreFlowList.forEach { storeFlow ->
            val file = unzipDir.resolve("store/${storeFlow.filename}")
            if (file.exists() && file.isFile) {
                try {
                    storeFlow.updateByDecode(file.readText())
                } catch (e: Exception) {
                    LogUtils.d("importBackUpData.updateByDecode", storeFlow.filename, e)
                }
            }
        }
        var importedSubsIds: List<Long> = emptyList()
        val dbFile = unzipDir.resolve("db.json")
        if (dbFile.exists() && dbFile.isFile) {
            val dbData = withContext(Dispatchers.Default) {
                json.decodeFromString<DbData>(dbFile.readText())
            }
            importedSubsIds = dbData.subsItems.orEmpty().map { it.id }
            if (!dbData.subsItems.isNullOrEmpty()) {
                DbSet.subsItemDao.insertOrIgnore(*dbData.subsItems.toTypedArray())
            }
            if (!dbData.subsConfigs.isNullOrEmpty()) {
                DbSet.subsConfigDao.insertOrIgnore(*dbData.subsConfigs.toTypedArray())
            }
            if (!dbData.categoryConfigs.isNullOrEmpty()) {
                DbSet.categoryConfigDao.insertOrIgnore(*dbData.categoryConfigs.toTypedArray())
            }
            if (!dbData.appConfigs.isNullOrEmpty()) {
                DbSet.appConfigDao.insertOrIgnore(*dbData.appConfigs.toTypedArray())
            }
        }
        val subsDir = unzipDir.resolve("subscription")
        if (subsDir.exists() && subsDir.isDirectory) {
            (subsDir.listFiles {
                it.isFile && it.name.endsWith(".json")
            } ?: emptyArray()).filterNotNull().forEach { file ->
                try {
                    val subs = withContext(Dispatchers.Default) {
                        // 不要用 json.decodeFromString<RawSubscription>: 严格反序列化要求 matches 必须是数组,
                        // 而 RawSubscription.parse 会先走 jsonToRuleRaw 归一化(见 RawSubscription.kt:842),
                        // 把 "matches": "选择器" 这种简写包装成数组。
                        // 网络更新路径(SubsState.kt:487)与本地文件重载(SubsState.kt:399)都用的是 parse,
                        // 这里原本用严格解码, 导致内置订阅里含字符串型 matches 的规则文件被静默丢弃
                        // (异常被下面的 catch 吞掉, 界面仍提示"导入成功")。
                        RawSubscription.parse(file.readText())
                    }
                    updateSubscription(subs)
                } catch (e: Exception) {
                    LogUtils.d("importBackUpData.saveSubs", file.name, e)
                }
            }
        }
        toast("导入成功")
        tempDir.deleteRecursively()
        delay(1000)
        // 必须等新导入的订阅项真正进入 subsEntriesFlow 再检测更新。
        // subsEntriesFlow 是由 Room 派生、初值为 emptyList 的 StateFlow(见 SubsState.kt:102),
        // 数据库刚写完时它的 .value 可能仍是导入前的快照; 这时 checkSubsUpdate 在第 523 行
        // 遍历不到任何"非本地订阅", 会直接跳过整轮更新 ——
        // 实测表现为首次启动不去拉取内置订阅(日志里 开始检测更新 与 结束检测更新 只隔 1 毫秒),
        // 用户看到的就是订阅列表空白。这里做有上限的等待, 最多 5 秒。
        if (importedSubsIds.isNotEmpty()) {
            var waited = 0L
            while (waited < 5000L &&
                !importedSubsIds.all { id -> subsEntriesFlow.value.any { it.subsItem.id == id } }
            ) {
                delay(100)
                waited += 100
            }
        }
        checkSubsUpdate(false)
    }
}