package com.example.stagerecorder

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 他のアプリの上、画面上部のカメラのあたりに出す経過時間。
 * 指で動かせる（位置は覚える）。軽く触ると録音中の画面を開く。表示中は画面を消さない。
 */
class TimerOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var cx = 0
    private val bg = GradientDrawable().apply { cornerRadius = dp(100f) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 250)
        }
    }

    val shown get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val tv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.overlaySp.toFloat())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum" // 数字の幅をそろえて揺れないように
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val h = dp(12f).toInt()
            val v = dp(3f).toInt()
            setPadding(h, v, h, v)
            background = bg
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        view = tv
        params = lp
        placeAt(savedOrDefault())
        update()
        // 幅が変わっても（1時間を超えた等）中心がずれないように
        tv.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ ->
            if (r - l != or_ - ol) applyX()
        }
        tv.setOnTouchListener(DragListener())
        try {
            wm.addView(tv, lp)
        } catch (_: Exception) {
            view = null
            return
        }
        handler.post(tick)
    }

    fun hide() {
        handler.removeCallbacks(tick)
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    /** 画面の向きが変わったとき：自分で動かしていなければカメラの真下へ置き直す */
    fun onRotated() {
        val v = view ?: return
        placeAt(savedOrDefault())
        params?.let { try { wm.updateViewLayout(v, it) } catch (_: Exception) {} }
    }

    private fun savedOrDefault(): Pair<Int, Int> {
        val (w, h) = CameraSpot.screenSize(ctx)
        val sx = prefs.overlayCx
        val sy = prefs.overlayY
        return if (sx in 0..w && sy in 0..h) sx to sy else CameraSpot.defaultAnchor(ctx)
    }

    private fun placeAt(p: Pair<Int, Int>) {
        cx = p.first
        params?.y = p.second
        applyX()
    }

    private fun applyX() {
        val v = view ?: return
        val lp = params ?: return
        val w = if (v.width > 0) v.width else {
            v.measure(0, 0)
            v.measuredWidth
        }
        lp.x = cx - w / 2
        if (v.isAttachedToWindow) try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun update() {
        val v = view ?: return
        val ms = RecState.elapsedMs()
        val paused = RecState.phase.value == RecState.Phase.PAUSED
        val mark = when (RecState.phase.value) {
            RecState.Phase.RECORDING -> "● "
            RecState.Phase.PAUSED -> "❚❚ "
            else -> ""
        }
        v.text = mark + fmtClock(ms)
        bg.setColor(
            when (RecState.level(ms)) {
                2 -> 0xE6D32F2F.toInt()
                1 -> 0xE6E08600.toInt()
                else -> 0xB3000000.toInt()
            }
        )
        // 一時停止中は点滅させて気づけるように
        v.alpha = if (paused && (System.currentTimeMillis() / 500) % 2 == 0L) 0.35f else 1f
    }

    private inner class DragListener : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startCx = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            val lp = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startCx = cx; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                    if (moved) {
                        cx = (startCx + dx).toInt()
                        lp.y = maxOf(0, (startY + dy).toInt())
                        applyX()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.overlayCx = cx
                        prefs.overlayY = lp.y
                    } else {
                        ctx.startActivity(
                            Intent(ctx, RecordActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                }
            }
            return true
        }
    }
}
