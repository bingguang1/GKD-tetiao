package li.songe.gkd

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import li.songe.gkd.a11y.initA11yFeat
import li.songe.gkd.data.CrashData
import li.songe.gkd.data.selfAppInfo
import li.songe.gkd.notif.initChannel
import li.songe.gkd.service.A11yAutoGuard
import li.songe.gkd.service.A11yService
import li.songe.gkd.service.AssocAppGuard
import li.songe.gkd.service.clearHttpSubs
import li.songe.gkd.service.initA11yWhiteAppList
import li.songe.gkd.shizuku.initShizuku
import li.songe.gkd.shizuku.uiAutomationFlow
import li.songe.gkd.store.initStore
import li.songe.gkd.store.storeFlow
import li.songe.gkd.widget.WGkdWidget
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.BackupUtils
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.PKG_FLAGS
import li.songe.gkd.util.deviceInfoDesc
import li.songe.gkd.util.initAppState
import li.songe.gkd.util.initSubsState
import li.songe.gkd.util.initToast
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.toast
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import kotlin.system.exitProcess


val appScope by lazy { MainScope() }

private lateinit var innerApp: App
val app: App
    get() = innerApp

private val applicationInfo by lazy {
    app.packageManager.getApplicationInfo(
        app.packageName,
        PackageManager.GET_META_DATA
    )
}

private fun getMetaString(key: String): String {
    return applicationInfo.metaData.getString(key) ?: error("Missing meta-data: $key")
}

// https://github.com/android-cs/16/blob/main/packages/SettingsLib/src/com/android/settingslib/accessibility/AccessibilityUtils.java#L41
private const val ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR = ':'

@Serializable
data class AppMeta(
    val channel: String = getMetaString("channel"),
    val commitId: String = getMetaString("commitId"),
    val commitTime: Long = getMetaString("commitTime").toLong(),
    val tagName: String? = getMetaString("tagName").takeIf { it.isNotEmpty() },
    val debuggable: Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    val versionCode: Int = selfAppInfo.versionCode,
    val versionName: String = selfAppInfo.versionName!!,
    val appId: String = app.packageName!!,
    val appName: String = app.getString(R.string.app_name)
) {
    val commitUrl = "https://github.com/bingguang1/gkd-tejiao/".run {
        plus(if (tagName != null) "tree/$tagName" else "commit/$commitId")
    }
    val isGkdChannel get() = channel == "gkd"
    // 本 fork 关闭应用内检测更新: 上游更新源(registry.npmmirror.com/@gkd-kit/app)发布的是官方 GKD,
    // 而本 fork 与官方同包名(li.songe.gkd)但签名不同, 装了会因签名冲突失败或把用户的 fork 覆盖成官方版。
    // 因此更新请关注本仓库 Releases: 见 util/Constants.kt 的 RELEASES_URL
    val updateEnabled get() = false
    val isBeta get() = versionName.contains("beta")
}

val META by lazy { AppMeta() }

fun contentObserver(listener: () -> Unit) = object : ContentObserver(null) {
    override fun onChange(selfChange: Boolean) = listener()
}

class App : Application() {
    companion object {
        const val START_WAIT_TIME = 3000L
    }

    init {
        innerApp = this
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (AndroidTarget.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    fun registerObserver(
        uri: Uri,
        observer: ContentObserver
    ) {
        contentResolver.registerContentObserver(uri, false, observer)
    }

    fun unregisterObserver(observer: ContentObserver) {
        contentResolver.unregisterContentObserver(observer)
    }

    fun getSecureString(name: String): String? = Settings.Secure.getString(contentResolver, name)
    fun putSecureString(name: String, value: String?): Boolean {
        return Settings.Secure.putString(contentResolver, name, value)
    }

    fun putSecureInt(name: String, value: Int): Boolean {
        return Settings.Secure.putInt(contentResolver, name, value)
    }

    fun getSecureA11yServices(): MutableSet<ComponentName> {
        val value = getSecureString(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (value.isNullOrEmpty()) return mutableSetOf()
        return value.split(
            ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR
        ).mapNotNull { ComponentName.unflattenFromString(it) }.toHashSet()
    }

    fun putSecureA11yServices(services: Set<ComponentName>) {
        putSecureString(
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.joinToString(ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR.toString()) { it.flattenToShortString() }
        )
    }

    fun resolveAppId(intent: Intent): String? {
        return intent.resolveActivity(packageManager)?.packageName
    }

    fun getPkgInfo(appId: String): PackageInfo? = try {
        packageManager.getPackageInfo(appId, PKG_FLAGS)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun resolveAppId(action: String, category: String? = null): String? {
        val intent = Intent(action)
        if (category != null) {
            intent.addCategory(category)
        }
        return resolveAppId(intent)
    }

    fun startLaunchActivity() {
        val intent = packageManager.getLaunchIntentForPackage(META.appId)!!
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        startActivity(intent)
    }

    fun checkGrantedPermission(permission: String) = ContextCompat.checkSelfPermission(
        this,
        permission,
    ) == PackageManager.PERMISSION_GRANTED

    val startTime = System.currentTimeMillis()
    var justStarted: Boolean = true
        get() {
            if (field) {
                field = System.currentTimeMillis() - startTime < START_WAIT_TIME
            }
            return field
        }

    val activityManager by lazy { app.getSystemService(ACTIVITY_SERVICE) as ActivityManager }
    val appOpsManager by lazy { app.getSystemService(APP_OPS_SERVICE) as AppOpsManager }
    val inputMethodManager by lazy { app.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager }
    val inputManager by lazy { app.getSystemService(INPUT_SERVICE) as InputManager }
    val windowManager by lazy { app.getSystemService(WINDOW_SERVICE) as WindowManager }
    val displayManager by lazy { app.getSystemService(DISPLAY_SERVICE) as DisplayManager }
    val keyguardManager by lazy { app.getSystemService(KEYGUARD_SERVICE) as KeyguardManager }
    val clipboardManager by lazy { app.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    val a11yManager by lazy { getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager }
    val launcherApps by lazy { getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps }

    val compatDisplay: Display
        get() = if (AndroidTarget.R) {
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

    override fun onCreate() {
        super.onCreate()
        LogUtils.d()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            toast(e.message ?: e.toString())
            LogUtils.d("UncaughtExceptionHandler", t, e)
            val mtime = System.currentTimeMillis()
            appScope.launchTry(Dispatchers.IO) {
                CrashData(
                    id = mtime,
                    mtime = mtime,
                    device = deviceInfoDesc,
                    androidVersionCode = android.os.Build.VERSION.SDK_INT,
                    androidVersionName = android.os.Build.VERSION.RELEASE,
                    versionCode = META.versionCode,
                    versionName = META.versionName,
                    name = e::class.java.name,
                    message = e.message,
                    thread = t.name,
                    stackTrace = Log.getStackTraceString(e),
                ).save()
                delay(1500)
                if (isActivityVisible) {
                    startLaunchActivity()
                }
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            }
        }
        initToast()
        initStore()
        initChannel()
        initAppState()
        initA11yFeat()
        initShizuku()
        initSubsState()
        initA11yWhiteAppList()
        clearHttpSubs()
        syncFixState()
        initFirstRunConfig()
        initA11yAutoGuard()
        initWidgetUpdate()
    }

    /** fork(v97): 桌面小组件——无障碍/自动化运行状态变化时刷新小组件文案 */
    private fun initWidgetUpdate() {
        appScope.launch(Dispatchers.Main) {
            combine(
                A11yService.isRunning,
                uiAutomationFlow,
                storeFlow,
            ) { _, _, _ -> Unit }
                .debounce(300)
                .collect { WGkdWidget.refreshAll(this@App) }
        }
    }

    /** fork: 无障碍自动守护——监听系统禁用 + 开机/周期自检 */
    private fun initA11yAutoGuard() {
        runCatching { A11yAutoGuard.scheduleAlarm(this) }
        // 服务被移除(系统/厂商取消)时自动恢复
        registerObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            contentObserver {
                appScope.launchTry {
                    delay(3_000)
                    A11yAutoGuard.autoEnsure()
                }
            }
        )
        // v97: 部分 ROM 会把整体 accessibility_enabled 置 0(而非移除单个服务), 一并监听
        registerObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            contentObserver {
                appScope.launchTry {
                    delay(3_000)
                    A11yAutoGuard.autoEnsure()
                }
            }
        )
        // v99: 亮屏/解锁即快检(进程活着时, 用户一拿起手机就恢复, 不必等闹钟)
        initScreenOnTriggers()
        // v99: 关联应用守护(打开关联 App 若无障碍被清除则立即恢复)
        AssocAppGuard.start()
        // 启动/被杀后重启进程时的自检
        appScope.launchTry(Dispatchers.IO) {
            delay(10_000)
            A11yAutoGuard.autoEnsure()
        }
    }

    /**
     * fork(v99): SCREEN_ON / USER_PRESENT 动态接收器。
     * 这两类广播只能动态注册(manifest 收不到); 进程由 开机接收器/周期闹钟 拉起后,
     * 用户下一次亮屏/解锁即可立刻触发恢复, 大幅缩短"被系统清除后要等 10 分钟"的窗口。
     */
    private fun initScreenOnTriggers() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> appScope.launchTry {
                        delay(1_500)
                        A11yAutoGuard.autoEnsure()
                    }

                    Intent.ACTION_USER_PRESENT -> appScope.launchTry {
                        delay(800)
                        A11yAutoGuard.autoEnsure()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    /**
     * 首次启动时自动导入内置备份(fork 定制: 安装即完成订阅/规则/设置配置)。
     * 通过 filesDir 下的标记文件保证只执行一次。
     */
    private fun initFirstRunConfig() {
        appScope.launchTry(Dispatchers.IO) {
            val marker = File(filesDir, "firstrun_config_v1.marker")
            if (marker.exists()) return@launchTry
            val assetPath = "firstrun/gkd-backup.zip"
            try {
                val out = File(cacheDir, "firstrun-config.zip")
                assets.open(assetPath).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                BackupUtils.importBackUpData(out.readBytes())
                marker.createNewFile()
                LogUtils.d("initFirstRunConfig ok")
            } catch (e: Exception) {
                LogUtils.d("initFirstRunConfig", e)
            }
        }
    }
}
