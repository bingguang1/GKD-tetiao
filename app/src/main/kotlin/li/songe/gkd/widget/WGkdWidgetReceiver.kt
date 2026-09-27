package li.songe.gkd.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import li.songe.gkd.service.switchAutomatorService

/**
 * fork(v97): 桌面小组件点按接收器。
 * 复用快捷磁贴/通知栏同一套开关逻辑([switchAutomatorService])。
 */
class WGkdWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WGkdWidget.ACTION_TOGGLE) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // v98: 点按先按当前真实状态刷新一次(进程可能刚被拉起, 文案可能陈旧)
                WGkdWidget.refreshAll(context)
                switchAutomatorService()
                // 开关是异步生效的, 稍等再刷新状态文案
                delay(1200)
                WGkdWidget.refreshAll(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
