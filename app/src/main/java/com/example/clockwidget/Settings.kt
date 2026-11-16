package com.example.clockwidget

import android.content.Context

/** 設定（文字の大きさ％・文字色・背景の濃さ％）。 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 文字の大きさ（60〜200、5刻み）。 */
    var textScale: Int
        get() = prefs.getInt("text_scale", 100)
        set(v) = prefs.edit().putInt("text_scale", (Math.round(v / 5f) * 5).coerceIn(60, 200)).apply()

    var textColor: Int
        get() = prefs.getInt("text_color", 0xFFFFFFFF.toInt())
        set(v) = prefs.edit().putInt("text_color", v).apply()

    /** 背景の濃さ（0〜100％）。 */
    var bgAlpha: Int
        get() = prefs.getInt("bg_alpha", 90)
        set(v) = prefs.edit().putInt("bg_alpha", v.coerceIn(0, 100)).apply()
}
