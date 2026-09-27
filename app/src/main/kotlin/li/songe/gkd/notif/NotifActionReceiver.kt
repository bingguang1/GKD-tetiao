package li.songe.gkd.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import li.songe.gkd.service.toggleA11yByNotifAction

/**
 * fork 定制: 通知(常驻通知)操作按钮的接收器。
 * 通过 manifest(exported=false) 注册, 由 [Notif] 构造的 PendingIntent 显式投递。
 * 目前支持: 无障碍一键开启/关闭(与快捷磁贴/控制页共用同一套开关逻辑)。
 */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NOTIF_ACTION) return
        val key = intent.getStringExtra(EXTRA_ACTION_KEY) ?: return
        if (key != KEY_A11Y_TOGGLE) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                toggleA11yByNotifAction()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val NOTIF_ACTION = "li.songe.gkd.action.NOTIF_ACTION"
        const val EXTRA_ACTION_KEY = "notif_action_key"
        const val KEY_A11Y_TOGGLE = "a11y_toggle"
    }
}
