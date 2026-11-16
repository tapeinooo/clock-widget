package com.example.clockwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.provider.AlarmClock
import android.util.TypedValue
import android.widget.RemoteViews

object ClockWidget {
    private const val TIME_SP = 36f
    private const val DATE_SP = 14f
    /** 自動調整のときの、日付の大きさ ÷ 時刻の大きさ。 */
    private val DATE_RATIOS = floatArrayOf(0.3f, 0.4f, 0.55f)
    private val DATE_MANUAL = floatArrayOf(0.75f, 1f, 1.4f)

    fun build(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
        val s = Settings(context)
        val font = Fonts.all[s.font]
        val v = RemoteViews(context.packageName, font.layout)
        var timeSize = TIME_SP * s.textScale / 100f
        var dateSize = DATE_SP * s.textScale / 100f * DATE_MANUAL[s.dateSize]
        var unit = TypedValue.COMPLEX_UNIT_SP
        if (s.autoFit) {
            val o = manager.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            if (w > 0 && h > 0) {
                timeSize = fit(font, w.toFloat(), h.toFloat(), DATE_RATIOS[s.dateSize])
                dateSize = timeSize * DATE_RATIOS[s.dateSize]
                unit = TypedValue.COMPLEX_UNIT_DIP
            }
            v.setViewPadding(R.id.content, 0, 0, 0, 0)
        }
        v.setTextViewTextSize(R.id.time, unit, timeSize)
        v.setTextViewTextSize(R.id.date, unit, dateSize)
        v.setTextColor(R.id.time, s.textColor)
        v.setTextColor(R.id.date, s.textColor)
        v.setInt(R.id.bg, "setImageAlpha", s.bgAlpha * 255 / 100)
        // タップで時計アプリ（アラーム一覧）を開く
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.root, pi)
        return v
    }

    /** 幅 w・高さ h（dp）にぎりぎり収まる時刻の文字の大きさ（dp）。 */
    private fun fit(font: FontDef, w: Float, h: Float, ratio: Float): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.typeface(font); textSize = 100f }
        val tw = p.measureText("00:00")
        val tm = p.fontMetrics
        val th = tm.descent - tm.ascent
        p.textSize = 100f * ratio
        val dw = p.measureText("12月30日(水)")
        val dm = p.fontMetrics
        val dh = dm.descent - dm.ascent
        val byW = w / maxOf(tw, dw) * 100f
        val byH = h / (th + dh) * 100f
        return (minOf(byW, byH) * 0.97f).coerceIn(8f, 400f)
    }

    fun updateAll(context: Context) {
        val m = AppWidgetManager.getInstance(context)
        val ids = m.getAppWidgetIds(ComponentName(context, ClockWidgetReceiver::class.java))
        for (id in ids) m.updateAppWidget(id, build(context, m, id))
    }
}

class ClockWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, ClockWidget.build(context, manager, id))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        manager.updateAppWidget(id, ClockWidget.build(context, manager, id))
    }
}
