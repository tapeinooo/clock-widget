package com.example.clockwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

        val auto = Switch(this).apply {
            text = "大きさに合わせて文字を最大にする（余白なし）"
            isChecked = settings.autoFit
        }
        root.addView(auto)

        val scaleLabel = label("")
        val scaleBar = SeekBar(this).apply {
            max = (200 - 60) / 5
            progress = (settings.textScale - 60) / 5
            setOnSeekBarChangeListener(listener { p ->
                settings.textScale = 60 + p * 5
                scaleLabel.text = "文字の大きさ: ${settings.textScale}%"
            })
        }
        root.addView(scaleBar)
        auto.setOnCheckedChangeListener { _, on ->
            settings.autoFit = on
            scaleBar.isEnabled = !on
            scaleLabel.alpha = if (on) 0.4f else 1f
        }
        scaleBar.isEnabled = !settings.autoFit
        scaleLabel.alpha = if (settings.autoFit) 0.4f else 1f
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

        label("日付の大きさ")
        root.addView(RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("小", "中", "大").forEachIndexed { i, t ->
                addView(RadioButton(this@MainActivity).apply { text = t; id = 100 + i }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            check(100 + settings.dateSize)
            setOnCheckedChangeListener { _, id -> settings.dateSize = id - 100 }
        })

        label("フォント")
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Fonts.all.map { it.name })
            setSelection(settings.font)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { settings.font = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

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
        // ノッチ・ステータスバーにかからないよう、上下に余白を足す
        val base = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(v.paddingLeft, base + b.top, v.paddingRight, base + b.bottom)
            insets
        }
        setContentView(ScrollView(this).apply { addView(root) })
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
