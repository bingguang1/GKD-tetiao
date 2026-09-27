package li.songe.gkd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import li.songe.gkd.widget.WGkdWidget

/**
 * fork 定制: 周期闹钟接收器。
 * 进程被系统杀掉后由闹钟唤醒, 再次确保无障碍服务开启, 抵御"熄屏后被关闭"。
 * v98: 唤醒即同步刷新桌面小组件(进程被杀期间状态变化也能在周期内被矫正)。
 * v99: 闹钟改为自适应一次性触发, 每次唤醒后按运行状态重排下一发(未运行 2 分钟/稳定 10 分钟)。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "li.songe.gkd.action.AUTO_A11Y_CHECK") return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                A11yAutoGuard.ensureFromReceiver()
                // 无论结果如何都重排下一发自适应闹钟, 保证"进程被杀后"的兜底链不断
                A11yAutoGuard.scheduleAlarm(context)
                WGkdWidget.refreshAll(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
