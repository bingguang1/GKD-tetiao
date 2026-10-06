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
 *
 * ★ v122 修「关联名单形同虚设」: 原来命中名单的分支**只打了一行日志**, 真正干活的还是紧随其后的
 *   无条件 `autoEnsure()`(**还有 5 秒节流**) —— 于是「关联应用守护」开关与「守护关联应用」名单
 *   对行为**没有任何影响**, 与设置页文案"打开关联的 App 时若无障碍被清除立即恢复"完全对不上。
 *   现在命中名单时**直接** `ensureEnabled()`(不受节流、不等下一轮 4.5 秒), 名单和开关才真正有用。
 */
object AssocAppGuard {
    private val started = AtomicBoolean(false)

    /** 熄屏时的轮询间隔(熄屏时既没有"打开应用"也没有可读的界面, 只需偶尔醒来看看) */
    private const val SCREEN_OFF_INTERVAL_MS = 10_000L

    /** 无障碍**不在运行**时的抢救间隔 */
    private const val RESCUE_INTERVAL_MS = 4_500L

    /** 无障碍正常时的巡检间隔(真正掉线由 ContentObserver / 闹钟 / 亮屏触发立刻兜住) */
    private const val IDLE_INTERVAL_MS = 5_000L

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
                    delay(SCREEN_OFF_INTERVAL_MS)
                    continue
                }
                val store = storeFlow.value
                val guardExpected = store.enableAutomator && store.useA11y &&
                        store.autoRestoreA11y && !store.manualA11yOff &&
                        currentAppUseA11y && !currentAppBlocked
                if (!guardExpected) {
                    delay(IDLE_INTERVAL_MS)
                    continue
                }
                if (A11yService.isRunning.value) {
                    // 无障碍正常 → 没有要抢救的东西(真掉线时 ContentObserver / 闹钟 / 亮屏会立刻拉起)
                    delay(IDLE_INTERVAL_MS)
                    continue
                }
                // ---- 无障碍不在运行, 需要抢救 ----
                // ★ v122: 关联名单**真的**起作用 —— 名单里的 App 回到前台时**立刻**恢复
                //   (直接调 ensureEnabled, 不走 autoEnsure 的 5 秒节流, 也不等这一轮 4.5 秒)。
                //   改之前这里只打了一行日志、真正干活的还是下面那句无条件 autoEnsure() ——
                //   于是"关联应用守护/守护关联应用"这个开关和名单对行为**没有任何影响**,
                //   与设置页写的"打开关联的 App 时若无障碍被清除立即恢复"完全对不上。
                if (store.enableGuardAssoc) {
                    val assocSet = actualGuardAssocAppList
                    if (assocSet.isNotEmpty() && hasUsageAccess()) {
                        val fg = queryForegroundPkg()
                        if (fg != null && fg in assocSet) {
                            LogUtils.d("GuardAssoc trigger pkg=$fg, ensure now")
                            A11yAutoGuard.ensureEnabled()
                            delay(RESCUE_INTERVAL_MS)
                            continue
                        }
                    }
                }
                // 通用快检: 进程存活时的"被系统清除立即恢复"(autoEnsure 自带 5s 节流 + manualOff/开关尊重)
                A11yAutoGuard.autoEnsure()
                delay(RESCUE_INTERVAL_MS)
            } catch (t: Throwable) {
                LogUtils.d("GuardAssoc", t)
                delay(SCREEN_OFF_INTERVAL_MS)
            }
        }
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 用 unsafeCheckOpNoThrow 而不是 noteOpNoThrow: 我们只是**读**权限状态,
                // 后者会把这次查询记成一次"访问", 反而污染使用情况统计(API 29 起被标记 deprecation
                // 只是因为"不记录", 对本用途恰好是对的)
                @Suppress("DEPRECATION")
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
                // MOVE_TO_FOREGROUND 在 API 29 起改名 ACTIVITY_RESUMED(数值相同, 都是 1);
                // 这里保留旧名是为了在低版本上也编译/运行一致(v122 只加注解, 不改行为)
                @Suppress("DEPRECATION")
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
