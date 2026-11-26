package com.example.stagerecorder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import kotlin.concurrent.thread

/**
 * 録音・録画の本体。通知を出して常駐するので、他のアプリを開いても画面を消しても続く。
 * 録っている間は、カメラのあたりに経過時間を出す（TimerOverlay）。
 */
class RecorderService : LifecycleService() {
    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1

        /** 動いている常駐（同じアプリの中から呼ぶため） */
        var instance: RecorderService? = null
            private set

        fun start(c: Context) =
            ContextCompat.startForegroundService(c, Intent(c, RecorderService::class.java).setAction(ACTION_START))

        fun stop(c: Context) = send(c, ACTION_STOP)
        fun pause(c: Context) = send(c, ACTION_PAUSE)
        fun resume(c: Context) = send(c, ACTION_RESUME)

        private fun send(c: Context, action: String) {
            if (instance != null) c.startService(Intent(c, RecorderService::class.java).setAction(action))
        }
    }

    private var overlay: TimerOverlay? = null
    private var overlaySuppressed = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var audioName = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "録音・録画中", NotificationManager.IMPORTANCE_LOW))
        overlay = TimerOverlay(this)
    }

    override fun onDestroy() {
        overlay?.hide()
        instance = null
        if (RecState.phase.value != RecState.Phase.IDLE) {
            // 途中で止められた
            releaseAudio(null)
            recording?.stop()
            cameraProvider?.unbindAll()
            RecState.idle()
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_STOP -> finish()
            ACTION_PAUSE -> pauseRec()
            ACTION_RESUME -> resumeRec()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onRotated()
    }

    /** 録音中の画面が見えている間は、重ねて出す時間表示を隠す */
    fun setOverlaySuppressed(suppressed: Boolean) {
        overlaySuppressed = suppressed
        refreshOverlay()
    }

    fun refreshOverlay() {
        val want = !overlaySuppressed && RecState.phase.value.let {
            it == RecState.Phase.RECORDING || it == RecState.Phase.PAUSED
        }
        if (want) overlay?.show() else overlay?.hide()
    }

    private fun begin() {
        // STARTING は押した画面の側で先に立てている
        if (RecState.active && RecState.phase.value != RecState.Phase.STARTING) return
        val prefs = Prefs(this)
        val video = prefs.video
        RecState.starting(video, prefs.targetMin)

        val type = if (video)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            fail("常駐を始められませんでした", e)
            return
        }
        if (video) beginVideo(prefs) else beginAudio()
    }

    // ---- 録音（音声のみ）----
    // アプリ内のファイルに録り、止めたら「音楽」フォルダへ写す
    // （端末の保存場所へ直接録ると、MP4 の書き終わりで失敗する端末があるため）

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

    private fun beginAudio() {
        val dir = File(filesDir, "recording")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // 前に途中で終わった残り（中身は壊れている）
        val name = Library.newName()
        val file = File(dir, "$name.m4a")
        var lastError: Exception? = null
        // 48kHz がだめな端末もあるので順に試す
        for (rate in listOf(48_000, 44_100, 16_000)) {
            val mr = newRecorder()
            try {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(if (rate >= 44_100) 128_000 else 64_000)
                mr.setAudioSamplingRate(rate)
                mr.setAudioChannels(1)
                mr.setOutputFile(file.absolutePath)
                mr.prepare()
                mr.start()
                mediaRecorder = mr
                audioFile = file
                audioName = name
                onStarted()
                return
            } catch (e: Exception) {
                lastError = e
                mr.release()
                file.delete()
            }
        }
        fail("録音を始められませんでした", lastError)
    }

    /** 録音を止める。保存（写すこと）は別の流れで行い、終わったら then(保存できたか) */
    private fun releaseAudio(then: ((Boolean) -> Unit)?) {
        val mr = mediaRecorder ?: return
        val file = audioFile
        val name = audioName
        var ok = true
        try {
            mr.stop()
        } catch (_: Exception) {
            ok = false // 短すぎる等で中身がない
        }
        mr.release()
        mediaRecorder = null
        audioFile = null
        if (file == null) return
        if (!ok || file.length() == 0L) {
            file.delete()
            then?.invoke(false)
            return
        }
        thread {
            val uri = Library.saveFile(this, file, "$name.m4a", false)
            if (uri != null) file.delete() // 写せなかったときは消さずに残す
            mainHandler.post { then?.invoke(uri != null) }
        }
    }

    // ---- 録画（映像＋音声）----

    @SuppressLint("MissingPermission")
    private fun beginVideo(prefs: Prefs) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (RecState.phase.value != RecState.Phase.STARTING) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val quality = if (prefs.fullHd) Quality.FHD else Quality.HD
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                    .build()
                val rotation = getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
                val videoCapture = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
                val preview = Preview.Builder().setTargetRotation(rotation).build()
                val selector = if (prefs.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, videoCapture)
                RecState.preview.value = preview

                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, Library.newName())
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Library.VIDEO_DIR)
                }
                val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(cv)
                    .build()
                recording = recorder.prepareRecording(this, output)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this)) { e -> onVideoEvent(e) }
            } catch (e: Exception) {
                cameraProvider?.unbindAll()
                fail("録画を始められませんでした", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onVideoEvent(e: VideoRecordEvent) {
        when (e) {
            is VideoRecordEvent.Start -> onStarted()
            is VideoRecordEvent.Finalize -> {
                recording = null
                cameraProvider?.unbindAll()
                val saved = e.outputResults.outputUri != Uri.EMPTY
                if (e.hasError()) {
                    val msg = if (saved) "録画は保存しましたが、途中で問題がありました（${e.error}）"
                    else "録画を保存できませんでした（${e.error}）"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "録画を保存しました", Toast.LENGTH_SHORT).show()
                }
                if (saved) RecState.savedCount.value++
                done()
            }
        }
    }

    // ---- 共通 ----

    private fun onStarted() {
        RecState.started()
        updateNotification()
        refreshOverlay()
    }

    private fun pauseRec() {
        if (RecState.phase.value != RecState.Phase.RECORDING) return
        try {
            mediaRecorder?.pause()
            recording?.pause()
        } catch (_: Exception) {
            return
        }
        RecState.paused()
        updateNotification()
    }

    private fun resumeRec() {
        if (RecState.phase.value != RecState.Phase.PAUSED) return
        try {
            mediaRecorder?.resume()
            recording?.resume()
        } catch (_: Exception) {
            return
        }
        RecState.resumed()
        updateNotification()
    }

    private fun finish() {
        when (RecState.phase.value) {
            RecState.Phase.RECORDING, RecState.Phase.PAUSED -> {}
            RecState.Phase.STARTING -> { // 始まる前に止めた
                cameraProvider?.unbindAll()
                done()
                return
            }
            else -> return
        }
        RecState.finishing()
        overlay?.hide()
        if (mediaRecorder != null) {
            releaseAudio { ok ->
                Toast.makeText(this, if (ok) "録音を保存しました" else "録音を保存できませんでした", Toast.LENGTH_SHORT).show()
                if (ok) RecState.savedCount.value++
                done()
            }
        } else {
            val r = recording
            if (r != null) r.stop() else done() // 書き終わりは onVideoEvent(Finalize) で
        }
    }

    private fun fail(msg: String, e: Exception? = null) {
        // 画面に理由ごと出す（トーストだとすぐ消えるので）
        RecState.lastError.value = msg + (e?.let { "\n\n${it.javaClass.simpleName}: ${it.message}" } ?: "")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        done()
    }

    private fun done() {
        overlay?.hide()
        RecState.idle()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val phase = RecState.phase.value
        val what = if (RecState.isVideo) "録画" else "録音"
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        when (phase) {
            RecState.Phase.RECORDING -> b.setContentTitle("${what}中")
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - RecState.elapsedMs())
                .addAction(0, "一時停止", pi(ACTION_PAUSE, 1))
                .addAction(0, "停止して保存", pi(ACTION_STOP, 2))
            RecState.Phase.PAUSED -> b.setContentTitle("${what}を一時停止中 ${fmtClock(RecState.elapsedMs())}")
                .setShowWhen(false)
                .addAction(0, "再開", pi(ACTION_RESUME, 3))
                .addAction(0, "停止して保存", pi(ACTION_STOP, 2))
            else -> b.setContentTitle("${what}の準備中…").setShowWhen(false)
        }
        return b.build()
    }
}
