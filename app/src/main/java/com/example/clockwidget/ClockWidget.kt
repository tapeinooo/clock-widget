package com.example.clockwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

/**
 * 時刻と日付を絵（濃さだけの絵）に描いて出し、文字色で塗る。1分ごとに描き直す。
 * ホーム画面はアプリに入れたフォントを文字に使えない（制限付きで見た目を読み込むため、フォントの指定が無視される）ので、
 * どのフォントも絵にする。絵なら字の上下の空きも詰めて、ぎりぎりまで大きくできる。
 */
object ClockWidget {
    private const val TIME_SP = 36f
    private const val DATE_SP = 14f
    /** 自動調整のときの、日付の大きさ ÷ 時刻の大きさ（小・中・大）。 */
    private val DATE_RATIOS = floatArrayOf(0.3f, 0.4f, 0.55f)
    /** 手動のときの、日付の大きさの倍率（小・中・大）。 */
    private val DATE_MANUAL = floatArrayOf(0.75f, 1f, 1.4f)
    /** 時刻と日付の間（時刻の数字の高さに対する割合）。 */
    private const val GAP = 0.08f
    /** 端で欠けないよう、ほんの少しだけ小さくする。 */
    private const val SAFETY = 0.98f
    /** 絵が大きすぎると送れないので、点の数を抑える（足りない分は拡大して出す）。 */
    private const val MAX_PIXELS = 500_000f

    /** 日本語の字があるフォントは「11月16日(月)」、ないフォントは「11.16 MON」。 */
    fun dateText(font: FontDef, date: LocalDate = LocalDate.now()): String =
        if (font.japanese) DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPAN).format(date)
        else DateTimeFormatter.ofPattern("M.d EEE", Locale.US).format(date).uppercase(Locale.US)

    fun build(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
        val o = manager.getAppWidgetOptions(id)
        // 縦向きの大きさ（幅は最小、高さは最大）で描き、ほかの向きでは縦横比を保って拡大・縮小させる
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 180
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 80
        return views(context, Settings(context), w.toFloat(), h.toFloat())
    }

    /** wDp・hDp はウィジェットの大きさ（dp）。 */
    private fun views(context: Context, s: Settings, wDp: Float, hDp: Float): RemoteViews {
        val font = Fonts.all[s.font]
        val metrics = context.resources.displayMetrics
        var density = metrics.density
        val area = wDp * hDp * density * density
        if (area > MAX_PIXELS) density *= sqrt(MAX_PIXELS / area)
        val bw = (wDp * density).toInt().coerceAtLeast(1)
        val bh = (hDp * density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val pad = if (s.autoFit) 0f else 8f * density
        val availW = bw - 2 * pad
        val availH = bh - 2 * pad

        val is24 = DateFormat.is24HourFormat(context)
        val now = LocalTime.now()
        val hour = if (is24) now.hour else (now.hour + 11) % 12 + 1
        val time = "$hour:%02d".format(now.minute)
        val date = dateText(font)
        val ratio = if (s.autoFit) DATE_RATIOS[s.dateSize] else DATE_SP / TIME_SP * DATE_MANUAL[s.dateSize]

        // 大きさ 100 で計って、収まる倍率 k を求める
        val base = 100f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.time.typeface(context); textSize = base }
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.date.typeface(context); textSize = base * ratio }
        // 時刻の幅：時の桁数が同じうちでいちばん幅を取る時刻（毎分大きさが変わらないように。変わるのは桁数が変わるときだけ）
        val hours = (if (is24) 0..23 else 1..12).filter { it.toString().length == hour.toString().length }
        val tw = hours.maxOf { tp.measureText("$it:") } + (0..59).maxOf { tp.measureText("%02d".format(it)) }
        val digits = "0123456789:"
        val tb = Rect().also { tp.getTextBounds(digits, 0, digits.length, it) }
        val dw = dp.measureText(date)
        val db = Rect().also { dp.getTextBounds(date, 0, date.length, it) }
        val gap = (tb.bottom - tb.top) * GAP
        val height = (tb.bottom - tb.top) + gap + (db.bottom - db.top)
        val fitK = minOf(availW / maxOf(tw, dw), availH / height)
        val k = if (s.autoFit) fitK * SAFETY else {
            // 指定の大きさ（sp）を絵の点に直す。入りきらなければ縮める
            val want = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TIME_SP * s.textScale / 100f, metrics) /
                metrics.density * density / base
            minOf(want, fitK)
        }
        tp.textSize = base * k
        dp.textSize = base * ratio * k
        val top = (bh - height * k) / 2
        c.drawText(time, (bw - tp.measureText(time)) / 2, top - tb.top * k, tp)
        c.drawText(date, (bw - dp.measureText(date)) / 2, top + (tb.bottom - tb.top + gap - db.top) * k, dp)

        val v = RemoteViews(context.packageName, R.layout.widget)
        v.setImageViewBitmap(R.id.clock, bmp)
        v.setInt(R.id.clock, "setColorFilter", s.textColor)
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
        for (id in ids) m.updateAppWidget(id, build(context, m, id))
        scheduleAlarms(context, ids.isNotEmpty())
    }

    /** 毎分の目覚まし（と念のため0時の目覚まし）を掛ける。ウィジェットがなければ外す。 */
    fun scheduleAlarms(context: Context, any: Boolean) {
        if (any) {
            TickAlarm.schedule(context)
            MidnightAlarm.schedule(context)
        } else {
            TickAlarm.cancel(context)
            MidnightAlarm.cancel(context)
        }
    }
}

class ClockWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 毎分・0時・時刻やタイムゾーンや言語が変わった・再起動・更新
            TickAlarm.ACTION, MidnightAlarm.ACTION, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ClockWidget.updateAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, ClockWidget.build(context, manager, id))
        ClockWidget.scheduleAlarms(context, true)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        manager.updateAppWidget(id, ClockWidget.build(context, manager, id))
    }

    override fun onDisabled(context: Context) {
        ClockWidget.scheduleAlarms(context, false)
    }
}
