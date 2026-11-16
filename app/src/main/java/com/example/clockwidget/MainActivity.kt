package com.example.clockwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** 設定画面。ウィジェットの追加時・長押しの「編集」・アプリのアイコンから開く。 */
class MainActivity : Activity() {
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // 追加時にキャンセルされても追加されないよう、まず CANCELED を返す
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f }.also { root.addView(it) }

        val scaleLabel = label("")
        root.addView(SeekBar(this).apply {
            max = (200 - 60) / 5
            progress = (settings.textScale - 60) / 5
            setOnSeekBarChangeListener(listener { p ->
                settings.textScale = 60 + p * 5
                scaleLabel.text = "文字の大きさ: ${settings.textScale}%"
            })
        })
        scaleLabel.text = "文字の大きさ: ${settings.textScale}%"

        val alphaLabel = label("")
        root.addView(SeekBar(this).apply {
            max = 20
            progress = settings.bgAlpha / 5
            setOnSeekBarChangeListener(listener { p ->
                settings.bgAlpha = p * 5
                alphaLabel.text = "背景の濃さ: ${settings.bgAlpha}%"
            })
        })
        alphaLabel.text = "背景の濃さ: ${settings.bgAlpha}%"

        label("文字の色")
        val colors = listOf("白" to Color.WHITE, "黄" to 0xFFFFEB3B.toInt(), "水色" to 0xFF4FC3F7.toInt(),
            "緑" to 0xFF81C784.toInt(), "桃" to 0xFFF48FB1.toInt(), "黒" to Color.BLACK)
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            for ((name, c) in colors) addView(Button(this@MainActivity).apply {
                text = name
                setOnClickListener { settings.textColor = c }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(Button(this).apply {
            text = "完了"
            setOnClickListener { finishOk(widgetId) }
        })
        setContentView(root)
    }

    private fun listener(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = f(p)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun finishOk(widgetId: Int) {
        ClockWidget.updateAll(this)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        finish()
    }

    override fun onPause() {
        super.onPause()
        ClockWidget.updateAll(this)
    }
}
