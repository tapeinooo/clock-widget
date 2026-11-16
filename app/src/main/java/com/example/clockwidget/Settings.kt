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

    /** フォントの番号（Fonts.all の位置）。 */
    var font: Int
        get() = prefs.getInt("font", 0).coerceIn(0, Fonts.all.size - 1)
        set(v) = prefs.edit().putInt("font", v).apply()

    /** ウィジェットの大きさに合わせて、余白なしで文字をいっぱいまで大きくする。 */
    var autoFit: Boolean
        get() = prefs.getBoolean("auto_fit", false)
        set(v) = prefs.edit().putBoolean("auto_fit", v).apply()

    /** 日付の大きさ：0＝小、1＝中、2＝大、3＝無（日付を出さない）。 */
    var dateSize: Int
        get() = prefs.getInt("date_size", 1).coerceIn(0, 3)
        set(v) = prefs.edit().putInt("date_size", v.coerceIn(0, 3)).apply()
}
