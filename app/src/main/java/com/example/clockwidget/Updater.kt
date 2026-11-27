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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * アプリの更新（共通ルール）。GitHub の最新のリリース（タグ v＋versionName、添付の .apk）を見て、
 * 今の版（versionName）より新しければ APK を取ってきて入れる（設定画面のボタンから）。
 */
object Updater {
    private const val API = "https://api.github.com/repos/tapeinooo/clock-widget/releases/latest"
    private const val CHANNEL = "update"

    class Release(val version: String, val url: String) {
        val tag get() = "v$version"
    }

    /** 最新版を調べる。通信するので、画面のスレッドでは呼ばないこと。 */
    fun fetchLatest(): Release {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) error("GitHub から最新版を取れませんでした（${c.responseCode}）")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val version = j.optString("tag_name").removePrefix("v").ifEmpty { error("最新版の情報が読めませんでした") }
            val assets = j.getJSONArray("assets")
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                ?: error("最新版に APK がありません")
            return Release(version, url)
        } finally {
            c.disconnect()
        }
    }

    fun isNew(r: Release) = isNewer(r.version, BuildConfig.VERSION_NAME)

    /** "2.0.3" と "1.10" を数としてくらべる（a が新しければ true） */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

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

    fun notify(context: Context, title: String, intent: Intent? = null) {
        val m = context.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CHANNEL, "アプリの更新", NotificationManager.IMPORTANCE_DEFAULT))
        val b = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setAutoCancel(true)
        if (intent != null) b.setContentIntent(PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        try { m.notify(2, b.build()) } catch (_: SecurityException) { }
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
