package com.example.stagerecorder

import android.os.SystemClock
import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableStateFlow

/** 録音・録画の今の状態。常駐（RecorderService）と画面とで共有する。 */
object RecState {
    enum class Phase { IDLE, STARTING, RECORDING, PAUSED, FINISHING }

    val phase = MutableStateFlow(Phase.IDLE)
    /** 録画のときの映像（録画中の画面に映すため） */
    val preview = MutableStateFlow<Preview?>(null)
    /** 保存するたびに増える（一覧の読み直しの合図） */
    val savedCount = MutableStateFlow(0)
    /** 始められなかった等の理由（画面に出したら null に戻す） */
    val lastError = MutableStateFlow<String?>(null)

    var isVideo = false
        internal set
    /** 録り始めたときの持ち時間（分） */
    var targetMin = 0
        internal set

    private var accumulated = 0L
    private var runningSince = 0L

    val active get() = phase.value != Phase.IDLE

    /** 経過時間（一時停止していた時間は数えない） */
    fun elapsedMs(): Long =
        accumulated + if (phase.value == Phase.RECORDING) SystemClock.elapsedRealtime() - runningSince else 0L

    fun starting(video: Boolean, target: Int) {
        isVideo = video
        targetMin = target
        accumulated = 0
        phase.value = Phase.STARTING
    }

    internal fun started() {
        accumulated = 0
        runningSince = SystemClock.elapsedRealtime()
        phase.value = Phase.RECORDING
    }

    internal fun paused() {
        if (phase.value != Phase.RECORDING) return
        accumulated += SystemClock.elapsedRealtime() - runningSince
        phase.value = Phase.PAUSED
    }

    internal fun resumed() {
        if (phase.value != Phase.PAUSED) return
        runningSince = SystemClock.elapsedRealtime()
        phase.value = Phase.RECORDING
    }

    internal fun finishing() {
        if (phase.value == Phase.RECORDING) accumulated += SystemClock.elapsedRealtime() - runningSince
        phase.value = Phase.FINISHING
    }

    internal fun idle() {
        preview.value = null
        phase.value = Phase.IDLE
    }

    /** 持ち時間に対する色。0: ふつう 1: 残りわずか 2: 超過 */
    fun level(elapsed: Long): Int {
        if (targetMin <= 0) return 0
        val target = targetMin * 60_000L
        val warn = minOf(2 * 60_000L, target / 5)
        return when {
            elapsed >= target -> 2
            elapsed >= target - warn -> 1
            else -> 0
        }
    }
}

/** 12:34 / 1:02:03 */
fun fmtClock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = s / 60 % 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** 12:34.5（編集用に0.1秒まで） */
fun fmtPrecise(ms: Long): String {
    val t = ms / 100
    val tenth = t % 10
    val s = t / 10
    val h = s / 3600
    val m = s / 60 % 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d.%d".format(h, m, sec, tenth) else "%d:%02d.%d".format(m, sec, tenth)
}
