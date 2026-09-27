package li.songe.gkd.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.songe.gkd.app
import li.songe.gkd.appScope
import li.songe.gkd.permission.writeSecureSettingsState
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry
import li.songe.gkd.widget.WGkdWidget

/**
 * fork 定制: 无障碍自动守护 + 统一"确保无障碍运行"入口。
 *
 * v97 重构(修复"无障碍被系统取消后不再恢复 / 通知栏只能关不能开"):
 * 1. 所有"写回无障碍启用列表"的操作统一走 [writeMutex] 串行化;
 * 2. [ensureEnabled] 带最多 [MAX_ENSURE_ATTEMPT] 轮"写入→等待→校验/摘除重启"重试;
 * 3. [ignoreManualOff]/[ignoreAutoRestore] 区分调用方语义。
 *
 * v99 强化(修复 vivo 等 ROM "重启/后台清理后无障碍被清除, 恢复慢或不再恢复"):
 * 1. 周期闹钟从"固定 10 分钟重复"改为**自适应一次性闹钟**: 每次触发后按当前运行状态
 *    重排下一发 —— 未运行(需要抢救)时 2 分钟一发, 稳定运行时 10 分钟一发;
 *    闹钟跨进程死亡可唤醒, 是"进程被杀后"的唯一兜底链路;
 * 2. [ensureEnabledLocked] **非破坏性恢复**: 服务名已在启用列表但尚未绑定成功时,
 *    第一轮只补写整体开关并等待(大多数情况是系统重启后尚未绑定, 摘除再写反而打断前台应用);
 *    只有持续失败才做"摘除→重写"的重启, 且 45 秒内至多摘除一次 —— 既避免服务反复横跳
 *    (系统反复断开/拉起引起部分应用重建), 也减少"无障碍已启动/已关闭"的提示骚扰;
 * 3. 观察者/闹钟/开机/亮屏/解锁/关联应用 全部汇入 [autoEnsure] 快路径(5 秒节流)。
 */
object A11yAutoGuard {
    private const val CHECK_FAST = 2 * 60_000L        // 无障碍未运行时: 快速自检间隔
    private const val CHECK_IDLE = 10 * 60_000L       // 无障碍稳定运行时: 低频巡检间隔
    private const val BOOT_RETRY_DELAY = 60_000L      // 开机首检失败后的早期重试
    private const val MIN_RETRY_INTERVAL = 5_000L
    private const val A11Y_REMOVE_WAIT = 1_200L
    private const val A11Y_START_WAIT = 2_500L
    private const val FLIP_MIN_INTERVAL = 45_000L     // 两次"摘除重启"的最小间隔(防服务反复横跳)
    private const val MAX_ENSURE_ATTEMPT = 3
    private const val ACTION_CHECK = "li.songe.gkd.action.AUTO_A11Y_CHECK"

    private val writeMutex = Mutex()

    @Volatile
    private var lastTryTime = 0L

    @Volatile
    private var lastFlipTime = 0L

    private fun alarmDelay(): Long {
        return if (A11yService.isRunning.value) CHECK_IDLE else CHECK_FAST
    }

    /**
     * 注册(或重排)下一发周期闹钟。进程存活时调用; 闹钟唤醒可跨进程死亡。
     * 默认按当前运行状态自适应; 可传入 [delayMillis] 指定(如开机后的早期重试)。
     */
    fun scheduleAlarm(context: Context, delayMillis: Long = -1L) {
        runCatching {
            val delay = if (delayMillis >= 0L) delayMillis else alarmDelay()
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + delay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // allowWhileIdle: 即使进入 Doze/熄屏休眠, 周期自检仍能唤醒(进程被杀后的兜底)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    /** 观察者/周期自检入口(带最小间隔节流) */
    fun autoEnsure() {
        val now = System.currentTimeMillis()
        if (now - lastTryTime < MIN_RETRY_INTERVAL) return
        lastTryTime = now
        appScope.launchTry(Dispatchers.IO) {
            ensureAuto()
        }
    }

    /** 供 BroadcastReceiver 在 goAsync 里调用(开机/闹钟, 不节流), 返回是否最终处于运行态 */
    suspend fun ensureFromReceiver(): Boolean {
        return ensureAuto()
    }

    /**
     * 统一"确保本服务运行"入口(写 secure settings 全程互斥 + 多轮重试)。
     * @param ignoreManualOff  true=用户主动开启, 不理会手动关闭标记;
     * @param ignoreAutoRestore true=不依赖「无障碍自动守护」开关(如应用级修复/用户主动开启)。
     * @return 是否最终处于运行态
     */
    suspend fun ensureEnabled(
        ignoreManualOff: Boolean = false,
        ignoreAutoRestore: Boolean = false,
    ): Boolean {
        if (!ignoreManualOff && storeFlow.value.manualA11yOff) {
            return A11yService.isRunning.value
        }
        if (!ignoreAutoRestore && !storeFlow.value.autoRestoreA11y) {
            return A11yService.isRunning.value
        }
        if (!writeSecureSettingsState.updateAndGet()) return false
        if (!currentAppUseA11y) return A11yService.isRunning.value
        if (A11yService.isRunning.value) return true
        // 尝试结束即刷新小组件(含失败路径, 避免状态没变化时文案陈旧)
        return writeMutex.withLock {
            ensureEnabledLocked()
        }.also { WGkdWidget.refreshAll(app) }
    }

    /** 轻量路径: 仅确保本服务在启用列表中(不等待绑定), 供自动模式/应用切换等快速恢复用 */
    suspend fun addToEnabledList() {
        if (A11yService.isRunning.value) return
        writeMutex.withLock {
            val names = app.getSecureA11yServices()
            names.add(A11yService.a11yCn)
            app.putSecureA11yServices(names)
            app.putSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        WGkdWidget.refreshAll(app)
    }

    /**
     * 自动守护路径: 尊重 manualA11yOff / autoRestoreA11y / 运行态。
     * @return 是否处于运行态(供开机/闹钟接收器决定是否安排早期重试)
     */
    private suspend fun ensureAuto(): Boolean {
        if (storeFlow.value.manualA11yOff) return A11yService.isRunning.value
        if (!storeFlow.value.autoRestoreA11y) return A11yService.isRunning.value
        if (!writeSecureSettingsState.value) {
            LogUtils.d("A11yAutoGuard skip: no WRITE_SECURE_SETTINGS")
            return A11yService.isRunning.value
        }
        if (!currentAppUseA11y) return A11yService.isRunning.value
        if (A11yService.isRunning.value) return true
        val ok = writeMutex.withLock {
            ensureEnabledLocked()
        }
        LogUtils.d("A11yAutoGuard ensure result=$ok")
        // 每次守护自检后刷新小组件
        WGkdWidget.refreshAll(app)
        return ok
    }

    /**
     * 持锁写回并重试, 直到服务运行或达到最大尝试次数。
     * v99 非破坏化: 列表里已有本服务时先给系统时间完成绑定(只补写整体开关),
     * 持续失败才摘除重启, 且 45s 内至多摘除一次, 避免服务反复横跳打断前台应用。
     */
    private suspend fun ensureEnabledLocked(): Boolean {
        val cn = A11yService.a11yCn
        for (attempt in 0 until MAX_ENSURE_ATTEMPT) {
            if (A11yService.isRunning.value) return true
            val names = app.getSecureA11yServices()
            if (names.contains(cn)) {
                val now = System.currentTimeMillis()
                if (attempt == 0 || now - lastFlipTime < FLIP_MIN_INTERVAL) {
                    // 已在启用列表但服务未运行: 第一轮(或摘除冷却期内)只补整体开关并等待,
                    // 多数情况是系统重启/刚解除停用尚未绑定, 强行摘除再写反而打断当前前台应用
                    app.putSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    LogUtils.d("A11yAutoGuard ensure attempt=$attempt wait-only")
                    delay(A11Y_START_WAIT)
                    continue
                }
                // 持续失败 → 摘除强制系统断开, 再写回触发重启绑定(45s 冷却)
                lastFlipTime = now
                names.remove(cn)
                app.putSecureA11yServices(names)
                delay(A11Y_REMOVE_WAIT)
            }
            names.add(cn)
            app.putSecureA11yServices(names)
            app.putSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            LogUtils.d("A11yAutoGuard ensure attempt=$attempt write-back")
            delay(A11Y_START_WAIT)
        }
        return A11yService.isRunning.value
    }

    /** 用户手动关闭标记(守护暂停拉起的依据), 由通知栏按钮/控制页/快捷磁贴关闭时写入 */
    fun setManualOff(value: Boolean) {
        if (storeFlow.value.manualA11yOff == value) return
        storeFlow.update { it.copy(manualA11yOff = value) }
        LogUtils.d("A11yAutoGuard manualOff=$value")
    }

    /** 开机早期重试延迟(供 BootReceiver 使用) */
    const val BOOT_RETRY_MS = BOOT_RETRY_DELAY
}
