package li.songe.gkd.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * fork(v97): 桌面小组件 Provider(由桌面长按添加)。
 * 状态文案在 [WGkdWidget.refreshAll] 中随运行状态实时刷新。
 */
class WGkdWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WGkdWidget.refresh(context, appWidgetIds.toList())
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WGkdWidget.refreshAll(context)
    }
}
