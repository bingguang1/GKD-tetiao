package li.songe.gkd.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import li.songe.gkd.R
import li.songe.gkd.service.A11yService
import li.songe.gkd.shizuku.uiAutomationFlow
import li.songe.gkd.store.storeFlow

/**
 * fork(v97): 桌面小组件——一键快捷开关 GKD。
 *
 * 与快捷磁贴同一套开关语义([li.songe.gkd.service.switchAutomatorService]):
 * 无障碍模式下开关无障碍服务, 自动化(Shizuku)模式下开关自动化服务;
 * 关闭时会写入手动关闭标记, 自动守护不会强行拉回。
 *
 * 状态文案由 [refreshAll] 更新, 触发时机:
 * - 小组件被添加到桌面(onUpdate);
 * - 无障碍/自动化运行状态变化(App.init 里的流收集);
 * - 点按切换后 1.2s 兜底刷新([WGkdWidgetReceiver])。
 */
object WGkdWidget {
    const val ACTION_TOGGLE = "li.songe.gkd.action.WIDGET_TOGGLE"

    fun providerComponent(context: Context): ComponentName =
        ComponentName(context, WGkdWidgetProvider::class.java)

    fun refreshAll(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            refresh(context, manager.getAppWidgetIds(providerComponent(context)).toList())
        }
    }

    fun refresh(context: Context, widgetIds: List<Int>) {
        if (widgetIds.isEmpty()) return
        val abRunning = A11yService.isRunning.value
        val automationRunning = uiAutomationFlow.value != null
        val store = storeFlow.value
        val active = abRunning || automationRunning
        val status = when {
            active -> if (store.useA11y) "无障碍运行中 · 点按关闭" else "自动化运行中 · 点按关闭"
            store.useA11y && store.manualA11yOff -> "无障碍已手动关闭 · 点按开启"
            store.useA11y -> "无障碍已关闭 · 点按开启"
            else -> "自动化已关闭 · 点按开启"
        }
        val statusColor = if (active) R.color.widget_status_on else R.color.widget_status_off
        widgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_gkd_toggle).apply {
                setTextViewText(R.id.widget_status, status)
                setTextColor(R.id.widget_status, context.getColor(statusColor))
                val pi = PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, WGkdWidgetReceiver::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pi)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
        }
    }
}
