package com.example.clockwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 設定画面。ウィジェットの追加時・長押しの「編集」・アプリのアイコンから開く。 */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private val samples = mutableListOf<LinearLayout>()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // 設定は今の値のままでも使えるので、戻るボタンで閉じてもウィジェットは置かれるようにする
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16f).toInt()
            setPadding(p, p, p, p)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 16f
            setPadding(0, dp(12f).toInt(), 0, 0)
        }.also { root.addView(it) }

        // 文字の大きさ
        val auto = Switch(this).apply {
            text = "大きさに合わせて文字を最大にする（余白なし）"
            textSize = 16f
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
        scaleLabel.text = "文字の大きさ: ${settings.textScale}%"
        fun showAuto(on: Boolean) {
            scaleBar.isEnabled = !on
            scaleLabel.alpha = if (on) 0.4f else 1f
        }
        auto.setOnCheckedChangeListener { _, on ->
            settings.autoFit = on
            showAuto(on)
        }
        showAuto(settings.autoFit)

        label("日付の大きさ")
        root.addView(RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("小", "中", "大", "無").forEachIndexed { i, t ->
                addView(RadioButton(this@MainActivity).apply { text = t; id = 100 + i },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            check(100 + settings.dateSize)
            setOnCheckedChangeListener { _, id -> settings.dateSize = id - 100 }
        })

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
                setOnClickListener {
                    settings.textColor = c
                    refreshSamples()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        // フォント：見本をタップして選ぶ
        label("フォント（タップで選ぶ）")
        Fonts.all.forEachIndexed { i, font ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val p = dp(10f).toInt()
                setPadding(p, p, p, p)
                setOnClickListener {
                    settings.font = i
                    refreshSamples()
                    // 画面が見えているうちに常駐を始める（裏からは始められないことがある）
                    ClockWidget.updateAll(this@MainActivity)
                }
            }
            box.addView(TextView(this).apply {
                text = font.name
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            box.addView(TextView(this).apply {
                text = "12:34"
                textSize = 40f
                typeface = font.time.typeface(this@MainActivity)
                includeFontPadding = false
            })
            box.addView(TextView(this).apply {
                text = ClockWidget.dateText(font)
                textSize = 16f
                typeface = font.date.typeface(this@MainActivity)
                includeFontPadding = false
            })
            root.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8f).toInt() })
            samples += box
        }
        refreshSamples()

        // アプリの更新
        label("アプリの更新")
        root.addView(TextView(this).apply {
            text = "今の版：${BuildConfig.VERSION_NAME}（${BuildConfig.GIT_SHA}）"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        val status = TextView(this).apply { textSize = 14f; text = settings.updateStatus }
        root.addView(status)
        val updateButton = Button(this).apply { text = "今すぐ更新を確認" }
        updateButton.setOnClickListener {
            if (!packageManager.canRequestPackageInstalls()) {
                // 「このアプリからのインストールを許可」の画面を開く
                status.text = "この画面で許可してから、もう一度押してください"
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            updateButton.isEnabled = false
            status.text = "確認しています…"
            Thread {
                val result = try {
                    val r = Updater.fetchLatest()
                    if (Updater.isNew(r)) {
                        runOnUiThread { status.text = "新しい版を取ってきています…" }
                        Updater.install(this, r)
                        "新しい版を入れています"
                    } else "最新です"
                } catch (e: Exception) {
                    "確認できませんでした：${e.message}"
                }
                settings.updateStatus = result
                runOnUiThread { status.text = result; updateButton.isEnabled = true }
            }.start()
        }
        root.addView(updateButton)

        root.addView(Button(this).apply {
            text = "完了"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16f).toInt() })

        // ノッチ・ステータスバー・ナビゲーションバーにかからないよう、上下左右に余白を足す
        val base = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(base + b.left, base + b.top, base + b.right, base + b.bottom)
            insets
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** 見本の文字色と、選んでいるフォントの枠を今の設定に合わせる。 */
    private fun refreshSamples() {
        val color = settings.textColor
        // 黒い文字のときは明るい背景にする
        val bg = if (Color.luminance(color) < 0.3f) 0xFFDDDDDD.toInt() else 0xFF202124.toInt()
        samples.forEachIndexed { i, box ->
            box.background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(12f)
                if (i == settings.font) setStroke(dp(3f).toInt(), 0xFF4FC3F7.toInt())
            }
            (box.getChildAt(1) as TextView).setTextColor(color)
            (box.getChildAt(2) as TextView).setTextColor(color)
        }
    }

    private fun listener(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = f(p)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    override fun onPause() {
        super.onPause()
        ClockWidget.updateAll(this)
    }
}
