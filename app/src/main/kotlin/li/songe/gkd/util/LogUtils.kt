package li.songe.gkd.util

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.hjq.device.compat.DeviceBrand
import com.hjq.device.compat.DeviceMarketName
import com.hjq.device.compat.DeviceOs
import li.songe.gkd.META
import li.songe.gkd.app
import li.songe.loc.Loc
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.days

object LogUtils {
    fun d(
        vararg args: Any?,
        @Loc loc: String = "",
        @Loc("{fileName}") fileName: String = "",
        tag: String = fileName.substringBeforeLast('.'),
    ) {
        val name = Thread.currentThread().name
        val actualLoc = loc.substring("li.songe.gkd.".length)
        val texts = args.map { stringify(it) }
        if (META.debuggable) {
            val msg = buildString {
                append("$name, $actualLoc")
                texts.forEachIndexed { i, text ->
                    if (texts.size == 1) {
                        append("\n")
                    } else {
                        append("\n[$i]: ")
                    }
                    append(text)
                }
            }
            Log.d(tag, msg)
        }
        val t = System.currentTimeMillis()
        logFileExecutor.run {
            logToFile(tag, name, actualLoc, texts, t)
        }
    }
}

private val logFileExecutor = Executors.newSingleThreadExecutor()

/** 日志文件的默认保留天数(设置项 `logRetainDays` 没读到时的兜底) */
private const val DEFAULT_LOG_KEEP_DAYS = 7

/**
 * fork(fok0030): 保留天数改为**用户可配**(设置 → 高级设置 → 日志 → 日志保留天数, 1/3/7/14/30 天, 默认 7)。
 * 这里在每次"开新文件"时读一次 store —— 用户改完设置不用重启 App 就生效。
 */
private fun logKeepDays(): Int =
    runCatching { li.songe.gkd.store.storeFlow.value.logRetainDays }
        .getOrDefault(DEFAULT_LOG_KEEP_DAYS)
        .coerceIn(1, 30)
val deviceInfoDesc by lazy {
    listOf(
        android.os.Build.MANUFACTURER,
        android.os.Build.MODEL,
        DeviceBrand.getBrandName(),
        DeviceOs.getOsName() + DeviceOs.getOsVersionName() + DeviceOs.getOsBigVersionCode(),
        DeviceMarketName.getMarketName(app)
    ).joinToString("/")
}
private val deviceInfoText by lazy {
    buildString {
        append("Android: ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.SDK_INT})\n")
        append("Device: ${deviceInfoDesc}\n")
        append("App: ${META.versionName} (${META.versionCode})\n")
    }
}

private fun logToFile(tag: String, name: String, loc: String, texts: List<String>, t: Long) {
    val file = logFolder.resolve("gkd-${t.format("yyyyMMdd")}.log")
    val sb = StringBuilder()
    if (!file.exists()) {
        val keepDays = logKeepDays()
        val files = logFolder.listFiles()
        if (files != null && files.size >= keepDays) {
            files.forEach {
                if (t - it.lastModified() > keepDays.days.inWholeMilliseconds) {
                    it.delete()
                }
            }
        }
        sb.append("=== Log ===\n")
        sb.append("Date: ${t.format("yyyy-MM-dd HH:mm:ss.SSS")}\n")
        sb.append(deviceInfoText)
        sb.append("=== Log ===\n\n")
    }
    sb.append(t.format("HH:mm:ss.SSS"))
    sb.append(" $tag, $name, $loc")
    if (texts.size == 1) {
        sb.append('\n')
        sb.append(texts[0])
    } else {
        texts.forEachIndexed { i, text ->
            sb.append("\n[$i]: ")
            sb.append(text)
        }
    }
    sb.append("\n\n")
    file.appendText(sb.toString())
}

private fun stringify(arg: Any?): String = when (arg) {
    is Bundle -> {
        val sb = StringBuilder()
        sb.append("Bundle{")
        val keys = arg.keySet()
        keys.forEachIndexed { index, key ->
            @Suppress("DEPRECATION")
            val value = arg.get(key)
            sb.append("$key=${stringify(value)}")
            if (index < keys.size - 1) {
                sb.append(",")
            }
        }
        sb.append("}")
        sb.toString()
    }

    is Intent -> {
        val sb = StringBuilder()
        sb.append("Intent{")
        arg.action?.let { sb.append("action=$it,") }
        arg.data?.let { sb.append("data=$it,") }
        arg.type?.let { sb.append("type=$it,") }
        arg.component?.let { sb.append("component=$it,") }
        arg.categories?.let { sb.append("categories=$it,") }
        arg.extras?.let { sb.append("extras=${stringify(it)}") }
        sb.append("}")
        sb.toString()
    }

    is Throwable -> Log.getStackTraceString(arg)

    else -> arg.toString()
}
