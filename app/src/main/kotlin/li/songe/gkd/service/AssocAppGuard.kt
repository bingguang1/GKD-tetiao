package li.songe.gkd.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import li.songe.gkd.app
import li.songe.gkd.appScope
import li.songe.gkd.store.actualGuardAssocAppList
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * fork(v99) 定制: 「关联应用守护」。
 *
 * 用户可在 设置→关联应用守护 中自定义一批"关键 App"(如微信/网易云)。当其中任意 App
 * 回到前台、而 GKD 的无障碍被系统清除/停用时, 本守护会在数秒内**立即**把无障碍恢复,
 * 而不是等周期闹钟(最长 ~10 分钟)或必须手动打开 GKD。
 *
 * 触发通道:
 * 1. [queryForegroundPkg] 通过 UsageStatsManager 轮询前台应用 —— 不需要无障碍在位即可感知
 *    "用户打开了哪个 App"(依赖 adb 授予的 PACKAGE_USAGE_STATS 使用情况访问权限);
 * 2. 无障碍在位时由 A11yService/A11yAutoGuard 的既有观察者链路兜底。
 *
 * 限制(已尽力但无法逾越系统边界, 见交接文档):
 * - 进程被系统彻底杀死后无法"感知"应用打开; 此时靠 自适应闹钟(未运行 2 分钟一发)+
 *   开机/亮屏/解锁触发 把进程拉起来再恢复 —— 本守护一旦随进程运行即恢复实时性。
 * - 进程被用户"强行停止/一键清理"(stopped 状态)后, 系统禁止任何广播/闹钟, 只能等下次手动打开 GKD。
 */
object AssocAppGuard {
    private val started = AtomicBoolean(false)

    /** 在 Application.onCreate 调用一次; 幂等 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.Default) {
            watcherLoop()
        }
    }

    private suspend fun watcherLoop() {
        while (true) {
            try {
                if (!app.powerManager.isInteractive) {
                    // 熄屏: 不轮询, 等 SCREEN_ON / 闹钟唤醒
                    delay(10_000)
                    continue
                }
                val store = storeFlow.value
                val guardExpected = store.enableAutomator && store.useA11y &&
                        store.autoRestoreA11y && !store.manualA11yOff &&
                        currentAppUseA11y && !currentAppBlocked
                if (guardExpected && !A11yService.isRunning.value) {
                    // 通道1: 关联 App 前台 → 立即恢复(带日志便于排障)
                    val assocSet = actualGuardAssocAppList
                    if (store.enableGuardAssoc && assocSet.isNotEmpty() && hasUsageAccess()) {
                        val fg = queryForegroundPkg()
                        if (fg != null && fg in assocSet) {
                            LogUtils.d("GuardAssoc trigger pkg=$fg")
                        }
                    }
                    // 通用快检: 进程存活时的"被系统清除立即恢复"(autoEnsure 自带 5s 节流 + manualOff/开关尊重)
                    A11yAutoGuard.autoEnsure()
                    delay(4_500)
                    continue
                }
                delay(2_000)
            } catch (t: Throwable) {
                LogUtils.d("GuardAssoc", t)
                delay(10_000)
            }
        }
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                app.appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                app.appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    /** 查询最近 3 秒内回到前台的 App(无权限时返回 null) */
    private fun queryForegroundPkg(): String? {
        return try {
            val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - 3_000
            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var fg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    fg = event.packageName
                }
            }
            fg
        } catch (t: Throwable) {
            null
        }
    }
}
