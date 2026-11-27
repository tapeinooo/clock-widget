package com.example.stagerecorder

import android.content.Context

/** 設定（端末に保存）。 */
class Prefs(c: Context) {
    private val p = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** true: 録画（映像＋音声） / false: 録音（音声のみ） */
    var video: Boolean
        get() = p.getBoolean("video", false)
        set(v) = p.edit().putBoolean("video", v).apply()

    /** true: 前面カメラ（自分を撮る） / false: 背面カメラ */
    var front: Boolean
        get() = p.getBoolean("front", true)
        set(v) = p.edit().putBoolean("front", v).apply()

    /** 画質 0: 480p / 1: 720p / 2: 1080p（前の版の「1080p かどうか」も引き継ぐ） */
    var quality: Int
        get() = p.getInt("quality", if (p.getBoolean("fullHd", true)) 2 else 1)
        set(v) = p.edit().putInt("quality", v).apply()

    /** 容量を節約する（映像のビットレートを下げる） */
    var saving: Boolean
        get() = p.getBoolean("saving", false)
        set(v) = p.edit().putBoolean("saving", v).apply()

    /** 節約のときの映像のビットレート（bps） */
    fun savingBitRate() = when (quality) {
        0 -> 600_000
        1 -> 1_200_000
        else -> 2_500_000
    }

    /** 持ち時間（分）。0 はなし。 */
    var targetMin: Int
        get() = p.getInt("targetMin", 0)
        set(v) = p.edit().putInt("targetMin", v).apply()

    /** カメラのあたりに出す時間の文字の大きさ（sp） */
    var overlaySp: Int
        get() = p.getInt("overlaySp", 22)
        set(v) = p.edit().putInt("overlaySp", v).apply()

    /** 録画中の画面に映像を映すか */
    var showPreview: Boolean
        get() = p.getBoolean("showPreview", true)
        set(v) = p.edit().putBoolean("showPreview", v).apply()

    /** 時間表示を指で動かした位置（中心のx, 上端のy）。未設定は -1 でカメラの真下。 */
    var overlayCx: Int
        get() = p.getInt("overlayCx", -1)
        set(v) = p.edit().putInt("overlayCx", v).apply()
    var overlayY: Int
        get() = p.getInt("overlayY", -1)
        set(v) = p.edit().putInt("overlayY", v).apply()

    fun resetOverlayPos() {
        overlayCx = -1
        overlayY = -1
    }
}
