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
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.sqrt

/**
 * 出し方は2つ。
 * - 文字（端末のフォント）：TextClock で出し、時刻も日付もホーム画面が自分で進める。アプリが止められても時刻は止まらない。
 * - 絵（アプリに入れたフォント）：時刻と日付を絵に描いて出す。毎分描き直すので、常駐（TickService）が要る。
 *   ホーム画面はアプリに入れたフォントを文字に使えないため。
 */
object ClockWidget {
    private const val TIME_SP = 36f
    private const val DATE_SP = 14f
    /** 自動調整のときの、日付の大きさ ÷ 時刻の大きさ（小・中・大）。 */
    /** 日付の大きさの「無」（日付を出さない）。 */
    const val DATE_NONE = 3
    private val DATE_RATIOS = floatArrayOf(0.3f, 0.4f, 0.55f)
    /** 手動のときの、日付の大きさの倍率（小・中・大）。 */
    private val DATE_MANUAL = floatArrayOf(0.75f, 1f, 1.4f)
    /** 時刻と日付の間（時刻の数字の高さに対する割合）。 */
    private const val GAP = 0.08f
    /** 端で欠けないよう、ほんの少しだけ小さくする。 */
    private const val SAFETY = 0.96f
    /** 絵が大きすぎると送れないので、点の数を抑える（足りない分は拡大して出す）。 */
    private const val MAX_PIXELS = 500_000f
    /** 計るときの文字の大きさ。 */
    private const val BASE = 100f
    private const val DIGITS = "0123456789:"
    /** 文字で出すときの日付の書き方（res/layout/widget_text_*.xml と同じ）。 */
    private const val DATE_FORMAT = "M月d日(E)"

    /** 日本語の字があるフォントは「11月16日(月)」、ないフォントは「11.16 MON」。 */
    fun dateText(font: FontDef, date: LocalDate = LocalDate.now()): String =
        if (font.japanese) DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPAN).format(date)
        else DateTimeFormatter.ofPattern("M.d EEE", Locale.US).format(date).uppercase(Locale.US)

    /** 文字で出すか。大きさに合わせて字の位置をずらすのは Android 12 以降しかできないので、古い Android の自動調整は絵にする。 */
    private fun textMode(s: Settings): Boolean =
        Fonts.all[s.font].system && (!s.autoFit || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    fun build(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
        val s = Settings(context)
        val o = manager.getAppWidgetOptions(id)
        // 縦向きの大きさ（幅は最小、高さは最大）
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val portrait = if (w > 0 && h > 0) SizeF(w.toFloat(), h.toFloat()) else null
        // 絵は縦向きの大きさで描き、ほかの向きでは縦横比を保って拡大・縮小させる
        if (!textMode(s)) return bitmapViews(context, s, portrait ?: SizeF(180f, 80f))
        if (!s.autoFit) return textViews(context, s, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 縦向き・横向きなど、ホーム画面が使う大きさごとに作っておき、ホーム画面に選ばせる
            @Suppress("DEPRECATION")
            val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                return RemoteViews(sizes.distinct().take(16).associateWith { textViews(context, s, it) })
            }
        }
        return textViews(context, s, portrait)
    }

    private fun hourNow(is24: Boolean): Int = LocalTime.now().hour.let { if (is24) it else (it + 11) % 12 + 1 }

    /**
     * 時刻の幅：時の桁数が同じうちで、いちばん幅を取る時刻。
     * 分が変わっても大きさは変わらず、変わるのは時の桁数が変わるときだけ（そのときは毎時の目覚ましで合わせ直す）。
     */
    private fun timeWidth(p: Paint, is24: Boolean, hour: Int): Float {
        val hours = (if (is24) 0..23 else 1..12).filter { it.toString().length == hour.toString().length }
        return hours.maxOf { p.measureText("$it:") } + (0..59).maxOf { p.measureText("%02d".format(it)) }
    }

    private fun common(context: Context, s: Settings, v: RemoteViews) {
        v.setInt(R.id.bg, "setImageAlpha", s.bgAlpha * 255 / 100)
        // タップで時計アプリ（アラーム一覧）を開く
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.root, pi)
    }

    /** 文字で出す。size（dp）があれば、その大きさにぎりぎり収まる文字の大きさと位置にする。 */
    private fun textViews(context: Context, s: Settings, size: SizeF?): RemoteViews {
        val font = Fonts.all[s.font]
        val v = RemoteViews(context.packageName, font.layout)
        val fit = s.autoFit && size != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        v.setViewVisibility(R.id.manual, if (fit) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.fit, if (fit) View.VISIBLE else View.GONE)
        if (fit && size != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val showDate = s.dateSize != DATE_NONE
            val ratio = DATE_RATIOS[s.dateSize.coerceAtMost(2)]
            val is24 = DateFormat.is24HourFormat(context)
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
            p.typeface = font.time.typeface(context)
            p.textSize = BASE
            val tw = timeWidth(p, is24, hourNow(is24))
            val tb = Rect().also { p.getTextBounds(DIGITS, 0, DIGITS.length, it) }
            val tAbove = lineAscent(p, DIGITS) + tb.top
            // 日付：今日と明日のうち大きいほう（0時を過ぎてから合わせ直すまでの間も、はみ出さないように）
            p.typeface = font.date.typeface(context)
            p.textSize = BASE * ratio
            // ホーム画面と同じ作り方（端末の言語）で文字にする
            val dates = if (!showDate) emptyList() else listOf(0, 1).map { add ->
                val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, add) }
                DateFormat.format(DATE_FORMAT, day).toString()
            }
            val dw = dates.maxOfOrNull { p.measureText(it) } ?: 0f
            val db = Rect()
            for (d in dates) db.union(Rect().also { p.getTextBounds(d, 0, d.length, it) })
            val dAbove = if (showDate) dates.maxOf { lineAscent(p, it) } + db.top else 0f

            val timeH = (tb.bottom - tb.top).toFloat()
            val gap = if (showDate) timeH * GAP else 0f
            val height = timeH + gap + (db.bottom - db.top)
            val k = minOf(size.width / maxOf(tw, dw), size.height / height) * SAFETY
            val dip = TypedValue.COMPLEX_UNIT_DIP
            // 枠の高さを字の高さちょうどにし、その中で、行の上の空き（字が描かれない部分）の分だけ上にずらして置く
            v.setViewLayoutHeight(R.id.fit, height * k, dip)
            v.setTextViewTextSize(R.id.fit_time, dip, BASE * k)
            v.setTextViewTextSize(R.id.fit_date, dip, BASE * ratio * k)
            v.setViewLayoutMargin(R.id.fit_time, RemoteViews.MARGIN_TOP, -tAbove * k, dip)
            v.setViewLayoutMargin(R.id.fit_date, RemoteViews.MARGIN_TOP, (timeH + gap - dAbove) * k, dip)
            v.setViewVisibility(R.id.fit_date, if (showDate) View.VISIBLE else View.GONE)
            v.setTextColor(R.id.fit_time, s.textColor)
            v.setTextColor(R.id.fit_date, s.textColor)
        } else {
            val scale = s.textScale / 100f
            v.setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_SP, TIME_SP * scale)
            v.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_SP, DATE_SP * scale * DATE_MANUAL[s.dateSize.coerceAtMost(2)])
            v.setViewVisibility(R.id.date, if (s.dateSize == DATE_NONE) View.GONE else View.VISIBLE)
            v.setTextColor(R.id.time, s.textColor)
            v.setTextColor(R.id.date, s.textColor)
            if (s.autoFit) v.setViewPadding(R.id.manual, 0, 0, 0, 0)
        }
        common(context, s, v)
        return v
    }

    /** 行の上端から基準線までの高さ。日本語など別のフォントで描く字の分も含め、TextView と同じ計り方。 */
    private fun lineAscent(p: TextPaint, text: String): Float {
        val b = StaticLayout.Builder.obtain(text, 0, text.length, p, 100_000).setIncludePad(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setUseLineSpacingFromFallbacks(true)
        val l = b.build()
        return (l.getLineBaseline(0) - l.getLineTop(0)).toFloat()
    }

    /** 今の時刻と日付を絵（濃さだけの絵）に描き、文字色で塗る。size はウィジェットの大きさ（dp）。 */
    private fun bitmapViews(context: Context, s: Settings, size: SizeF): RemoteViews {
        val font = Fonts.all[s.font]
        val metrics = context.resources.displayMetrics
        var density = metrics.density
        val area = size.width * size.height * density * density
        if (area > MAX_PIXELS) density *= sqrt(MAX_PIXELS / area)
        val bw = (size.width * density).toInt().coerceAtLeast(1)
        val bh = (size.height * density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val pad = if (s.autoFit) 0f else 8f * density
        val availW = bw - 2 * pad
        val availH = bh - 2 * pad

        val is24 = DateFormat.is24HourFormat(context)
        val hour = hourNow(is24)
        val time = "$hour:%02d".format(LocalTime.now().minute)
        val date = dateText(font)
        val showDate = s.dateSize != DATE_NONE
        val di = s.dateSize.coerceAtMost(2)
        val ratio = if (s.autoFit) DATE_RATIOS[di] else DATE_SP / TIME_SP * DATE_MANUAL[di]

        // 大きさ BASE で計って、収まる倍率 k を求める
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.time.typeface(context); textSize = BASE }
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.date.typeface(context); textSize = BASE * ratio }
        val tw = timeWidth(tp, is24, hour)
        val tb = Rect().also { tp.getTextBounds(DIGITS, 0, DIGITS.length, it) }
        val dw = if (showDate) dp.measureText(date) else 0f
        val db = Rect().also { if (showDate) dp.getTextBounds(date, 0, date.length, it) }
        val gap = if (showDate) (tb.bottom - tb.top) * GAP else 0f
        val height = (tb.bottom - tb.top) + gap + (db.bottom - db.top)
        val fitK = minOf(availW / maxOf(tw, dw), availH / height)
        val k = if (s.autoFit) fitK * SAFETY else {
            // 指定の大きさ（sp）を絵の点に直す。入りきらなければ縮める
            val want = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TIME_SP * s.textScale / 100f, metrics) /
                metrics.density * density / BASE
            minOf(want, fitK)
        }
        tp.textSize = BASE * k
        dp.textSize = BASE * ratio * k
        val top = (bh - height * k) / 2
        c.drawText(time, (bw - tp.measureText(time)) / 2, top - tb.top * k, tp)
        if (showDate) c.drawText(date, (bw - dp.measureText(date)) / 2, top + (tb.bottom - tb.top + gap - db.top) * k, dp)

        val v = RemoteViews(context.packageName, R.layout.widget)
        v.setImageViewBitmap(R.id.clock, bmp)
        v.setInt(R.id.clock, "setColorFilter", s.textColor)
        common(context, s, v)
        return v
    }

    fun updateAll(context: Context) {
        val m = AppWidgetManager.getInstance(context)
        val ids = m.getAppWidgetIds(ComponentName(context, ClockWidgetReceiver::class.java))
        for (id in ids) m.updateAppWidget(id, build(context, m, id))
        schedule(context, ids.isNotEmpty())
    }

    /** 目覚ましと常駐を、今の設定に合わせる。ウィジェットがなければ外す。 */
    fun schedule(context: Context, any: Boolean) {
        val ticks = any && !textMode(Settings(context))
        if (any) UpdateAlarm.schedule(context, everyMinute = ticks) else UpdateAlarm.cancel(context)
        TickService.sync(context, ticks)
    }
}

class ClockWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 目覚まし・時刻やタイムゾーンや言語が変わった・再起動・更新
            UpdateAlarm.ACTION, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ClockWidget.updateAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, ClockWidget.build(context, manager, id))
        ClockWidget.schedule(context, true)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        manager.updateAppWidget(id, ClockWidget.build(context, manager, id))
    }

    override fun onDisabled(context: Context) {
        ClockWidget.schedule(context, false)
    }
}
