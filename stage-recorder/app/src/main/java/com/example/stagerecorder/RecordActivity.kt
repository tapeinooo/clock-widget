package com.example.stagerecorder

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 録っている間の画面。カメラの真下に大きく経過時間を出す。画面は消えない。 */
class RecordActivity : AppCompatActivity() {
    private lateinit var timer: TextView
    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var pauseBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private var cutoutCx = -1

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val tick = object : Runnable {
        override fun run() {
            updateTimer()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = Color.BLACK

        val prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        timer = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(timer, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        val middle = FrameLayout(this)
        middle.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        middle.addView(TextView(this).apply {
            text = "他のアプリを開いても録り続けます。\n時間は画面上部のカメラのあたりに出ます。"
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            textSize = 14f
            visibility = if (RecState.isVideo && prefs.showPreview) View.GONE else View.VISIBLE
        }, FrameLayout.LayoutParams(-1, -1))
        if (!(RecState.isVideo && prefs.showPreview)) previewView.visibility = View.GONE
        root.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = dp(16f).toInt()
            setPadding(p, p, p, p)
        }
        pauseBtn = MaterialButton(this).apply {
            textSize = 18f
            setOnClickListener {
                if (RecState.phase.value == RecState.Phase.PAUSED) RecorderService.resume(this@RecordActivity)
                else RecorderService.pause(this@RecordActivity)
            }
        }
        stopBtn = MaterialButton(this).apply {
            text = "停止して保存"
            textSize = 18f
            setBackgroundColor(0xFFD32F2F.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { RecorderService.stop(this@RecordActivity) }
        }
        buttons.addView(pauseBtn, LinearLayout.LayoutParams(0, dp(64f).toInt(), 1f).apply { marginEnd = dp(8f).toInt() })
        buttons.addView(stopBtn, LinearLayout.LayoutParams(0, dp(64f).toInt(), 1f).apply { marginStart = dp(8f).toInt() })
        root.addView(buttons)
        setContentView(root)

        // カメラの真下に時間が来るように、上の余白と左右の寄せを合わせる
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            val rect = insets.displayCutout?.boundingRects?.filter { it.top <= 0 }?.minByOrNull { it.top }
            cutoutCx = rect?.centerX() ?: -1
            centerTimer()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> centerTimer() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecState.phase.combine(RecState.preview) { ph, pv -> ph to pv }.collect { (ph, pv) ->
                    if (ph == RecState.Phase.IDLE) {
                        finish()
                        return@collect
                    }
                    if (RecState.isVideo && prefs.showPreview) pv?.setSurfaceProvider(previewView.surfaceProvider)
                    updateButtons()
                }
            }
        }
    }

    /** 時間の文字の中心を、カメラの中心の真下にそろえる */
    private fun centerTimer() {
        val parent = timer.parent as? View ?: return
        val w = timer.width
        if (w == 0 || cutoutCx < 0) {
            timer.setPadding(0, 0, 0, 0)
            return
        }
        val cx = cutoutCx - parent.paddingLeft
        val l = maxOf(0, 2 * cx - w)
        val r = maxOf(0, w - 2 * cx)
        if (timer.paddingLeft != l || timer.paddingRight != r) timer.setPadding(l, 0, r, 0)
    }

    override fun onStart() {
        super.onStart()
        if (!RecState.active) {
            finish()
            return
        }
        RecorderService.instance?.setOverlaySuppressed(true)
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        RecState.preview.value?.setSurfaceProvider(null)
        RecorderService.instance?.setOverlaySuppressed(false)
        super.onStop()
    }

    private fun updateButtons() {
        val ph = RecState.phase.value
        pauseBtn.text = if (ph == RecState.Phase.PAUSED) "再開" else "一時停止"
        pauseBtn.isEnabled = ph == RecState.Phase.RECORDING || ph == RecState.Phase.PAUSED
        stopBtn.isEnabled = ph != RecState.Phase.FINISHING
    }

    private fun updateTimer() {
        val ms = RecState.elapsedMs()
        timer.text = fmtClock(ms)
        timer.setTextColor(
            when (RecState.level(ms)) {
                2 -> 0xFFFF5252.toInt()
                1 -> 0xFFFFB300.toInt()
                else -> Color.WHITE
            }
        )
        val ph = RecState.phase.value
        timer.alpha = if (ph == RecState.Phase.PAUSED && (System.currentTimeMillis() / 500) % 2 == 0L) 0.35f else 1f
        val what = if (RecState.isVideo) "録画" else "録音"
        val head = when (ph) {
            RecState.Phase.STARTING -> "準備中…"
            RecState.Phase.RECORDING -> "● ${what}中"
            RecState.Phase.PAUSED -> "一時停止中"
            RecState.Phase.FINISHING -> "保存中…"
            RecState.Phase.IDLE -> ""
        }
        val t = RecState.targetMin
        status.text = if (t > 0) {
            val left = t * 60_000L - ms
            val rest = if (left >= 0) "残り ${fmtClock(left + 999)}" else "超過 ${fmtClock(-left)}"
            "$head　持ち時間 ${t}分・$rest"
        } else head
        updateButtons()
    }
}
