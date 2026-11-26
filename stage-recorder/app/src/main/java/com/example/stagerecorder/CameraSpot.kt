package com.example.stagerecorder

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

/** 画面上部のカメラ（パンチホール／ノッチ）の位置。 */
object CameraSpot {
    /** 今の向きでの画面の幅と高さ */
    fun screenSize(c: Context): Pair<Int, Int> {
        val wm = c.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = c.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /** 画面上端にかかっているカメラの枠。無ければ null */
    fun topCutout(c: Context): Rect? {
        val wm = c.getSystemService(WindowManager::class.java)
        val cutout = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.windowInsets.displayCutout
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.cutout
        } ?: return null
        return cutout.boundingRects.filter { it.top <= 0 && it.width() < screenSize(c).first }
            .minByOrNull { it.top }
    }

    fun statusBarHeight(c: Context): Int {
        val id = c.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) c.resources.getDimensionPixelSize(id) else 0
    }

    /** 時間表示の既定の置き場所：カメラの真下（中心のx, 上端のy） */
    fun defaultAnchor(c: Context): Pair<Int, Int> {
        val r = topCutout(c)
        val w = screenSize(c).first
        val cx = r?.centerX() ?: (w / 2)
        val y = maxOf(r?.bottom ?: 0, statusBarHeight(c))
        return cx to y
    }
}
