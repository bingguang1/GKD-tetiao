package li.songe.gkd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import li.songe.gkd.widget.WGkdWidget

/**
 * fork 定制: 开机/解锁/升级后自动确保无障碍服务开启(等效"开机自动启动无障碍")。
 * 仅在应用持有 WRITE_SECURE_SETTINGS 权限时真正生效, 否则静默跳过。
 *
 * v96 加固: USER_UNLOCKED 触发点 / 广播先重挂闹钟 / 只做一次短时自检。
 * v99 强化: 自检失败时安排 +60s 早期重试(开机初期系统未就绪、首次写回被拒的常见场景),
 *          不再被动等下一发 10 分钟闹钟; 周期闹钟本身也改为自适应(见 A11yAutoGuard)。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 先把周期闹钟链挂上(闹钟不跨重启, 不重挂=进程被杀后自检链断裂)
                A11yAutoGuard.scheduleAlarm(context)
                val pendingResult = goAsync()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope.launch {
                    try {
                        // 等系统启动稳定/解锁完成后再处理
                        delay(10_000L)
                        val ok = A11yAutoGuard.ensureFromReceiver()
                        if (!ok) {
                            // 开机首检失败(常见于开机初期系统仍在初始化无障碍服务) → 提前到 +60s 再试
                            A11yAutoGuard.scheduleAlarm(context, A11yAutoGuard.BOOT_RETRY_MS)
                        }
                        // 开机/解锁后同步刷新小组件
                        WGkdWidget.refreshAll(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
