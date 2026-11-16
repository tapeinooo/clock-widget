package com.example.clockwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.TypedValue
import android.widget.RemoteViews

object ClockWidget {
    private const val TIME_SP = 36f
    private const val DATE_SP = 14f

    fun build(context: Context): RemoteViews {
        val s = Settings(context)
        val v = RemoteViews(context.packageName, R.layout.widget)
        val k = s.textScale / 100f
        v.setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_SP, TIME_SP * k)
        v.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_SP, DATE_SP * k)
        v.setTextColor(R.id.time, s.textColor)
        v.setTextColor(R.id.date, s.textColor)
        v.setInt(R.id.bg, "setImageAlpha", s.bgAlpha * 255 / 100)
        // タップで時計アプリ（アラーム一覧）を開く
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.root, pi)
        return v
    }

    fun updateAll(context: Context) {
        val m = AppWidgetManager.getInstance(context)
        val ids = m.getAppWidgetIds(ComponentName(context, ClockWidgetReceiver::class.java))
        for (id in ids) m.updateAppWidget(id, build(context))
    }
}

class ClockWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, ClockWidget.build(context))
    }
}
