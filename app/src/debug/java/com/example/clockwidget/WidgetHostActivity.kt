package com.example.clockwidget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout

/**
 * 確認用の画面。先に `adb shell appwidget grantbind --package com.example.clockwidget` で許可しておく。
 * extra：--ei w 250 --ei h 110（大きさ dp）、--ei font 8（フォントの番号）、--ei auto 1（自動調整）
 */
class WidgetHostActivity : Activity() {
    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        if (intent.hasExtra("font")) s.font = intent.getIntExtra("font", 0)
        if (intent.hasExtra("auto")) s.autoFit = intent.getIntExtra("auto", 0) == 1
        val manager = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, 1)
        host.startListening()
        val provider = ComponentName(this, ClockWidgetReceiver::class.java)
        val prefs = getSharedPreferences("host", MODE_PRIVATE)
        var id = prefs.getInt("id", -1)
        if (id == -1 || manager.getAppWidgetInfo(id) == null) {
            id = host.allocateAppWidgetId()
            check(manager.bindAppWidgetIdIfAllowed(id, provider)) { "grantbind が必要" }
            prefs.edit().putInt("id", id).apply()
        }
        val w = intent.getIntExtra("w", 250)
        val h = intent.getIntExtra("h", 110)
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(android.util.SizeF(w.toFloat(), h.toFloat())))
        })
        val view = host.createView(this, id, manager.getAppWidgetInfo(id))
        // 既定の余白をなくし、知らせた大きさと実際の大きさを同じにする
        view.setPadding(0, 0, 0, 0)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#3C6E91")) }
        frame.addView(view, FrameLayout.LayoutParams(dp(w), dp(h), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(80) })
        setContentView(frame)
        ClockWidget.updateAll(this)
    }

    override fun onDestroy() {
        host.stopListening()
        super.onDestroy()
    }
}
