package com.example.stagerecorder

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.RangeSlider
import java.io.File
import kotlin.concurrent.thread

/** 切り出し編集：始めと終わりを決めて、その部分だけを新しいファイルとして保存する。 */
@OptIn(UnstableApi::class)
class EditActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_VIDEO = "video"
    }

    private lateinit var uri: Uri
    private var isVideo = false
    private lateinit var player: ExoPlayer
    private lateinit var slider: RangeSlider
    private lateinit var info: TextView
    private lateinit var deleteOriginal: CheckBox
    private var durationMs = 0L
    private var ready = false
    /** 「範囲を再生」中は終わりで止める */
    private var previewing = false
    private var transformer: Transformer? = null
    private var outFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val tick = object : Runnable {
        override fun run() {
            if (previewing && player.isPlaying && player.currentPosition >= endMs()) {
                player.pause()
                previewing = false
            }
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        uri = intent.data ?: run { finish(); return }
        isVideo = intent.getBooleanExtra(EXTRA_VIDEO, contentResolver.getType(uri)?.startsWith("video") == true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12f).toInt()
            setPadding(p, p, p, p)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val p = dp(12f).toInt()
            v.setPadding(b.left + p, b.top + p, b.right + p, b.bottom + p)
            insets
        }

        root.addView(TextView(this).apply {
            text = Library.displayName(this@EditActivity, uri) ?: "切り出し編集"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
        })

        player = ExoPlayer.Builder(this).build()
        val playerView = PlayerView(this).apply {
            this.player = this@EditActivity.player
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 0 // 操作ボタンは出しっぱなし
            controllerHideOnTouch = false
            if (!isVideo) defaultArtwork = null
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8f).toInt() })
        player.setMediaItem(MediaItem.fromUri(uri))
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !ready) onReady()
            }
        })
        player.prepare()

        info = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8f).toInt(), 0, 0)
            text = "読み込み中…"
        }
        root.addView(info)

        slider = RangeSlider(this).apply {
            valueFrom = 0f
            valueTo = 1f
            values = listOf(0f, 1f)
            isEnabled = false
            setLabelFormatter { fmtPrecise((it * 1000).toLong()) }
        }
        var last = listOf(0f, 1f)
        slider.addOnChangeListener { s, _, fromUser ->
            val v = s.values
            if (fromUser) {
                // 動かした方のつまみの位置を映す
                val moved = if (v[0] != last[0]) v[0] else v[1]
                player.seekTo((moved * 1000).toLong())
                previewing = false
            }
            last = v
            showInfo()
        }
        root.addView(slider)

        fun btn(t: String, f: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t
            textSize = 13f
            setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
            setOnClickListener { if (ready) f() }
        }
        fun row(vararg b: MaterialButton) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEachIndexed { i, x ->
                addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6f).toInt() })
            }
        }.also { root.addView(it) }

        row(
            btn("始め −1秒") { setStart(startMs() - 1000) },
            btn("始め +1秒") { setStart(startMs() + 1000) },
            btn("終わり −1秒") { setEnd(endMs() - 1000) },
            btn("終わり +1秒") { setEnd(endMs() + 1000) },
        )
        row(
            btn("今の位置を始めに") { setStart(player.currentPosition) },
            btn("今の位置を終わりに") { setEnd(player.currentPosition) },
        )
        row(
            btn("始めから再生") { player.seekTo(startMs()); player.play(); previewing = true },
            btn("終わりの3秒前から") { player.seekTo(maxOf(startMs(), endMs() - 3000)); player.play(); previewing = true },
        )

        deleteOriginal = CheckBox(this).apply { text = "保存したら元のファイルを削除する" }
        root.addView(deleteOriginal)

        root.addView(MaterialButton(this).apply {
            text = "この範囲を切り出して保存"
            textSize = 17f
            setOnClickListener { if (ready) export() }
        }, LinearLayout.LayoutParams(-1, dp(56f).toInt()))

        setContentView(root)
        handler.post(tick)
    }

    private fun onReady() {
        ready = true
        durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 1000L
        val sec = durationMs / 1000f
        slider.valueTo = sec
        slider.values = listOf(0f, sec)
        slider.isEnabled = true
        showInfo()
    }

    private fun startMs() = (slider.values[0] * 1000).toLong()
    private fun endMs() = (slider.values[1] * 1000).toLong()

    private fun setStart(ms: Long) {
        val v = (ms.coerceIn(0, endMs() - 100) / 1000f).coerceIn(0f, slider.valueTo)
        slider.values = listOf(v, slider.values[1])
        player.seekTo((v * 1000).toLong())
        previewing = false
    }

    private fun setEnd(ms: Long) {
        val v = (ms.coerceIn(startMs() + 100, durationMs) / 1000f).coerceIn(0f, slider.valueTo)
        slider.values = listOf(slider.values[0], v)
        player.seekTo((v * 1000).toLong())
        previewing = false
    }

    private fun showInfo() {
        if (!ready) return
        info.text = "始め ${fmtPrecise(startMs())}　終わり ${fmtPrecise(endMs())}\n" +
            "長さ ${fmtPrecise(endMs() - startMs())}（元は ${fmtPrecise(durationMs)}）"
    }

    // ---- 保存 ----

    private fun export() {
        player.pause()
        val start = startMs()
        val end = endMs()
        if (end - start < 500) {
            Toast.makeText(this, "範囲が短すぎます", Toast.LENGTH_SHORT).show()
            return
        }
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(start)
            .apply { if (end < durationMs - 50) setEndPositionMs(end) }
            .build()
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()
        val out = File(cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        outFile = out

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(this).apply {
            val p = dp(24f).toInt()
            setPadding(p, p, p, 0)
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("切り出しています…")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("やめる") { _, _ -> cancelExport() }
            .show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val holder = ProgressHolder()
        val progress = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) bar.progress = holder.progress
                handler.postDelayed(this, 300)
            }
        }

        val builder = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(progress)
                    transformer = null
                    dialog.setTitle("保存しています…")
                    bar.isIndeterminate = true
                    saveResult(out) { ok ->
                        dialog.dismiss()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        onSaved(ok)
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    handler.removeCallbacks(progress)
                    transformer = null
                    out.delete()
                    dialog.dismiss()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    AlertDialog.Builder(this@EditActivity)
                        .setTitle("切り出せませんでした")
                        .setMessage(exportException.message ?: exportException.errorCodeName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            })
        if (isVideo) {
            builder.setVideoMimeType(MimeTypes.VIDEO_H264)
            // 切り口の近くだけ作り直し、残りはそのまま写す（速い）
            builder.experimentalSetTrimOptimizationEnabled(true)
        }
        val t = builder.build()
        transformer = t
        t.start(item, out.absolutePath)
        handler.post(progress)
    }

    private fun cancelExport() {
        transformer?.cancel()
        transformer = null
        outFile?.delete()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun saveResult(out: File, then: (Boolean) -> Unit) {
        val orig = Library.displayName(this, uri) ?: Library.newName()
        val ext = if (isVideo) ".mp4" else ".m4a"
        val name = Library.baseName(orig) + "_切り出し" + ext
        thread {
            val saved = Library.saveFile(this, out, name, isVideo)
            out.delete()
            if (saved != null && deleteOriginal.isChecked) {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            runOnUiThread {
                if (saved != null) RecState.savedCount.value++
                then(saved != null)
            }
        }
    }

    private fun onSaved(ok: Boolean) {
        if (!ok) {
            Toast.makeText(this, "保存できませんでした", Toast.LENGTH_LONG).show()
            return
        }
        val where = if (isVideo) "「動画」の StageRecorder" else "「音楽」の StageRecorder"
        val removed = deleteOriginal.isChecked
        AlertDialog.Builder(this)
            .setTitle("保存しました")
            .setMessage("${where}フォルダに、新しいファイルとして入れました。" + if (removed) "\n元のファイルは削除しました。" else "")
            .setPositiveButton("一覧へ戻る") { _, _ -> finish() }
            .apply { if (!removed) setNegativeButton("続けて編集", null) }
            .setCancelable(false)
            .show()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        outFile?.takeIf { transformer != null }?.delete()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
