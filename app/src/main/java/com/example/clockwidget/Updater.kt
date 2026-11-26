package com.example.clockwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * アプリの更新。GitHub の Releases の「最新版」（push のたびに GitHub Actions が作り直す）を見て、
 * 今入っている版（BuildConfig.GIT_SHA）と違えば新しい版として APK を取ってきて入れる。
 */
object Updater {
    private const val API = "https://api.github.com/repos/tapeinooo/clock-widget/releases/tags/latest"
    private const val WORK = "auto_update"
    private const val CHANNEL = "update"

    class Release(val sha: String, val url: String)

    /** 最新版を調べる。通信するので、画面のスレッドでは呼ばないこと。 */
    fun fetchLatest(): Release {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) error("GitHub から最新版を取れませんでした（${c.responseCode}）")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val sha = Regex("[0-9a-f]{7,40}").find(j.optString("body"))?.value ?: error("最新版の情報が読めませんでした")
            val assets = j.getJSONArray("assets")
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                ?: error("最新版に APK がありません")
            return Release(sha, url)
        } finally {
            c.disconnect()
        }
    }

    fun isNew(r: Release) = !BuildConfig.GIT_SHA.startsWith(r.sha) && !r.sha.startsWith(BuildConfig.GIT_SHA)

    /** APK を取ってきて、入れる（今のアプリを置き換える）。通信するので、画面のスレッドでは呼ばないこと。 */
    fun install(context: Context, r: Release) {
        val file = File(context.cacheDir, "update.apk")
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) error("APK を取れませんでした（${c.responseCode}）")
            c.inputStream.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        } finally {
            c.disconnect()
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // 前の版もこのアプリが入れていれば、確認なしで入れ替えられる（Android 12 以降）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { i -> session.openWrite("clock-widget.apk", 0, file.length()).use { o -> i.copyTo(o); session.fsync(o) } }
            val pi = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
        }
    }

    /** 自動更新を、設定に合わせて始める・止める（12時間ごとに確認）。 */
    fun syncAuto(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (Settings(context).autoUpdate) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    fun notify(context: Context, title: String, intent: Intent? = null) {
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CHANNEL, "アプリの更新", NotificationManager.IMPORTANCE_DEFAULT))
        val b = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setAutoCancel(true)
        if (intent != null) b.setContentIntent(PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        try { m.notify(2, b.build()) } catch (_: SecurityException) { }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val s = Settings(applicationContext)
        return try {
            val r = Updater.fetchLatest()
            if (!Updater.isNew(r)) {
                s.updateStatus = "最新です"
            } else if (applicationContext.packageManager.canRequestPackageInstalls()) {
                Updater.install(applicationContext, r)
            } else {
                s.updateStatus = "新しい版があります（この版を入れる許可がありません）"
                Updater.notify(applicationContext, "時計の新しい版があります。アプリを開いて更新してください",
                    Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Result.success()
        } catch (e: Exception) {
            s.updateStatus = "確認できませんでした：${e.message}"
            Result.retry()
        }
    }
}

/** インストールの結果を受ける。確認が要るときは、確認の画面を開く（裏からは開けないので通知にもする）。 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val s = Settings(context)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                s.updateStatus = "更新の確認を待っています"
                Updater.notify(context, "時計の更新を入れます。タップして確認してください", confirm)
                try { context.startActivity(confirm) } catch (_: Exception) { }
            }
            PackageInstaller.STATUS_SUCCESS -> s.updateStatus = "更新しました"
            else -> s.updateStatus = "更新できませんでした：${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}"
        }
    }
}
