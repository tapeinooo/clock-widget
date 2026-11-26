package com.example.stagerecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub の Releases（タグ stage-recorder）から新しい版を探して入れる。 */
object Updater {
    private const val API = "https://api.github.com/repos/tapeinooo/clock-widget/releases/tags/stage-recorder"
    private const val ASSET = "stage-recorder.apk"

    data class Info(val versionCode: Int, val url: String, val size: Long)

    val current get() = BuildConfig.VERSION_CODE

    /** 公開されている版（読めなければ null）。通信するので画面の流れ以外で呼ぶ。 */
    fun fetch(): Info? {
        val conn = URL(API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "StageRecorder")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = Regex("versionCode:\\s*(\\d+)").find(json.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == ASSET) {
                    return Info(code, a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** APK を取ってくる。progress は 0〜100。 */
    fun download(c: Context, info: Info, progress: (Int) -> Unit): File {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "stage-recorder-${info.versionCode}.apk")
        var url = URL(info.url)
        var conn: HttpURLConnection
        // GitHub は別のホストへ転送するので、たどる
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "StageRecorder")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** このアプリから入れてよいか（「不明なアプリのインストール」の許可） */
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")))
    }

    fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
