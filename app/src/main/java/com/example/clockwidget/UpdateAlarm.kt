package com.example.clockwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 描き直しの目覚まし。
 * - 毎時（端末のフォント）：時刻はホーム画面が進めるので、時の桁数や日付が変わるときに大きさを合わせ直すだけ。
 * - 毎分（アプリに入れたフォント）：常駐（TickService）が止められたときの予備。画面が消えている間は起こさない。
 */
object UpdateAlarm {
    const val ACTION = "com.example.clockwidget.UPDATE"

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 100,
            Intent(context, ClockWidgetReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun schedule(context: Context, everyMinute: Boolean) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val step = if (everyMinute) 60_000L else 3_600_000L
        val now = System.currentTimeMillis()
        // 30分ずれたタイムゾーンでも、正時は 1970年からの時間の区切りと合うとは限らないので、分は現地時刻から求める
        val at = if (everyMinute) now - now % step + step else nextHour()
        val pi = pendingIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        try {
            when {
                !exact -> alarm.setWindow(AlarmManager.RTC, at, 5_000L, pi)
                everyMinute -> alarm.setExact(AlarmManager.RTC, at, pi)
                else -> alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            alarm.setWindow(AlarmManager.RTC, at, 5_000L, pi)
        }
    }

    private fun nextHour(): Long =
        java.time.ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }
}
