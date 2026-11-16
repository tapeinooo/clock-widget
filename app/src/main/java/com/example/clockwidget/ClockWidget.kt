package com.example.clockwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object ClockWidget {
    private const val TIME_SP = 36f
    private const val DATE_SP = 14f
    /** 自動調整のときの、日付の大きさ ÷ 時刻の大きさ（小・中・大）。 */
    private val DATE_RATIOS = floatArrayOf(0.3f, 0.4f, 0.55f)
    /** 手動のときの、日付の大きさの倍率（小・中・大）。 */
    private val DATE_MANUAL = floatArrayOf(0.75f, 1f, 1.4f)
    /** 自動調整のときの、時刻と日付の間（時刻の数字の高さに対する割合）。 */
    private const val GAP = 0.08f
    /** はみ出して欠けないよう、ほんの少しだけ小さくする。 */
    private const val SAFETY = 0.98f

    /** 日本語の字があるフォントは「11月16日(月)」、ないフォントは「11.16 MON」。 */
    fun dateText(font: FontDef, date: LocalDate = LocalDate.now()): String =
        if (font.japanese) DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPAN).format(date)
        else DateTimeFormatter.ofPattern("M.d EEE", Locale.US).format(date).uppercase(Locale.US)

    fun build(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
        val s = Settings(context)
        if (!s.autoFit) return views(context, s, null)
        val o = manager.getAppWidgetOptions(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 縦向き・横向きなど、ホーム画面が使う大きさごとに作っておき、ホーム画面に選ばせる
            @Suppress("DEPRECATION")
            val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                return RemoteViews(sizes.distinct().take(16).associateWith { views(context, s, it) })
            }
        }
        // 古い Android：縦向きの大きさ（幅は最小、高さは最大）
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return views(context, s, if (w > 0 && h > 0) SizeF(w.toFloat(), h.toFloat()) else null)
    }

    /** size はウィジェットの大きさ（dp）。null なら手動の文字の大きさ。 */
    private fun views(context: Context, s: Settings, size: SizeF?): RemoteViews {
        val font = Fonts.all[s.font]
        val v = RemoteViews(context.packageName, font.layout)
        val date = dateText(font)
        v.setTextViewText(R.id.date, date)
        if (s.autoFit) v.setViewPadding(R.id.content, 0, 0, 0, 0)
        if (s.autoFit && size != null) {
            val tight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val f = fit(context, font, date, size.width, size.height, DATE_RATIOS[s.dateSize], tight)
            v.setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_DIP, f.time)
            v.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_DIP, f.date)
            if (tight) {
                // 字の上下の空き（フォントの行の高さのうち、数字や字が描かれない部分）を、負の余白で詰める
                val dp = TypedValue.COMPLEX_UNIT_DIP
                v.setViewLayoutMargin(R.id.time, RemoteViews.MARGIN_TOP, f.timeTop, dp)
                v.setViewLayoutMargin(R.id.time, RemoteViews.MARGIN_BOTTOM, f.timeBottom, dp)
                v.setViewLayoutMargin(R.id.date, RemoteViews.MARGIN_TOP, f.dateTop, dp)
                v.setViewLayoutMargin(R.id.date, RemoteViews.MARGIN_BOTTOM, f.dateBottom, dp)
            }
        } else {
            val k = s.textScale / 100f
            v.setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_SP, TIME_SP * k)
            v.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_SP, DATE_SP * k * DATE_MANUAL[s.dateSize])
        }
        v.setTextColor(R.id.time, s.textColor)
        v.setTextColor(R.id.date, s.textColor)
        v.setInt(R.id.bg, "setImageAlpha", s.bgAlpha * 255 / 100)
        // タップで時計アプリ（アラーム一覧）を開く
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.root, pi)
        return v
    }

    /** 文字の大きさ（dp）と、上下の余白（dp、負なら詰める）。 */
    private class Fit(val time: Float, val date: Float, val timeTop: Float, val timeBottom: Float, val dateTop: Float, val dateBottom: Float)

    /** 文字の行の高さ（上・下）。日本語など別のフォントで描く字の分も含め、TextView と同じ計り方。 */
    private fun lineAscentDescent(p: TextPaint, text: String): Pair<Float, Float> {
        val b = StaticLayout.Builder.obtain(text, 0, text.length, p, 100_000).setIncludePad(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setUseLineSpacingFromFallbacks(true)
        val l = b.build()
        return (l.getLineBaseline(0) - l.getLineTop(0)).toFloat() to (l.getLineBottom(0) - l.getLineBaseline(0)).toFloat()
    }

    /** 幅 w・高さ h（dp）に、左右・上下ともぎりぎり収まる大きさを求める。 */
    private fun fit(context: Context, font: FontDef, date: String, w: Float, h: Float, ratio: Float, tight: Boolean): Fit {
        val base = 100f
        val p = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        // 時刻：いちばん幅を取る時刻（1分ごとに変わっても、はみ出さないように）
        p.typeface = font.time.typeface(context)
        p.textSize = base
        val hours = if (DateFormat.is24HourFormat(context)) 0..23 else 1..12
        val minutesW = (0..59).maxOf { p.measureText("%02d".format(it)) }
        val tw = hours.maxOf { p.measureText("$it:") } + minutesW
        val digits = "0123456789:"
        val (tAsc, tDesc) = lineAscentDescent(p, digits)
        val tb = Rect().also { p.getTextBounds(digits, 0, digits.length, it) }
        // 日付
        p.typeface = font.date.typeface(context)
        p.textSize = base * ratio
        val dw = p.measureText(date)
        val (dAsc, dDesc) = lineAscentDescent(p, date)
        val db = Rect().also { p.getTextBounds(date, 0, date.length, it) }

        // 字が描かれない、行の上下の空き（詰められる分）
        val tAbove = if (tight) (tAsc + tb.top).coerceAtLeast(0f) else 0f
        val tBelow = if (tight) (tDesc - tb.bottom).coerceAtLeast(0f) else 0f
        val dAbove = if (tight) (dAsc + db.top).coerceAtLeast(0f) else 0f
        val dBelow = if (tight) (dDesc - db.bottom).coerceAtLeast(0f) else 0f
        val gap = if (tight) (tb.bottom - tb.top) * GAP else 0f
        val height = (tAsc + tDesc - tAbove - tBelow) + gap + (dAsc + dDesc - dAbove - dBelow)
        val k = minOf(w / maxOf(tw, dw), h / height) * SAFETY
        return Fit(base * k, base * ratio * k, -tAbove * k, (gap - tBelow) * k, -dAbove * k, -dBelow * k)
    }

    fun updateAll(context: Context) {
        val m = AppWidgetManager.getInstance(context)
        val ids = m.getAppWidgetIds(ComponentName(context, ClockWidgetReceiver::class.java))
        for (id in ids) m.updateAppWidget(id, build(context, m, id))
        if (ids.isNotEmpty()) MidnightAlarm.schedule(context)
    }
}

class ClockWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 日付が変わった・時刻やタイムゾーンや言語が変わった・再起動・更新
            MidnightAlarm.ACTION, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ClockWidget.updateAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, ClockWidget.build(context, manager, id))
        MidnightAlarm.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        manager.updateAppWidget(id, ClockWidget.build(context, manager, id))
    }

    override fun onDisabled(context: Context) {
        MidnightAlarm.cancel(context)
    }
}
