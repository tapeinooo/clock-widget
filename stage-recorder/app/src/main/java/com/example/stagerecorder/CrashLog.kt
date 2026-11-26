package com.example.stagerecorder

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * アプリが落ちたとき・うまく動かなかったときの記録。次に開いたとき画面に出す（原因を調べるため）。
 */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "problems.txt")

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(app, "アプリが落ちました", e) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** 落ちはしないが、うまくいかなかったこと */
    fun note(c: Context, what: String, e: Throwable) {
        try { write(c, what, e) } catch (_: Throwable) {}
    }

    private fun write(c: Context, what: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
            "  $what\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}" +
            "  版 ${BuildConfig.VERSION_NAME}\n"
        file(c).appendText(head + sw.toString().lines().take(40).joinToString("\n") + "\n\n")
    }

    /** 記録を取り出して消す。なければ null */
    fun take(c: Context): String? {
        val f = file(c)
        if (!f.exists()) return null
        val t = f.readText().takeLast(8000)
        f.delete()
        return t.ifBlank { null }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
