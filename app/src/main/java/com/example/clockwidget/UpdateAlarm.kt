package com.example.clockwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 毎時の描き直しの目覚まし。時刻はホーム画面が進めるので、時の桁数や日付が変わるときに、大きさと日付の文字を合わせ直すだけ。
 */
object UpdateAlarm {
    const val ACTION = "com.example.clockwidget.UPDATE"

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 100,
            Intent(context, ClockWidgetReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextHour()
        val pi = pendingIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        try {
            when {
                !exact -> alarm.setWindow(AlarmManager.RTC, at, 5_000L, pi)
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
