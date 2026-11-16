package com.example.clockwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 絵で出すフォントのときに、毎分0秒に描き直すための目覚まし。
 * 画面が消えている間は起こさない種類にして、電池を使わないようにする（画面が付いたらすぐ届く）。
 */
object TickAlarm {
    const val ACTION = "com.example.clockwidget.TICK"

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 101,
            Intent(context, ClockWidgetReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val at = now - now % 60_000L + 60_000L
        val pi = pendingIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        try {
            if (exact) alarm.setExact(AlarmManager.RTC, at, pi) else alarm.setWindow(AlarmManager.RTC, at, 5_000L, pi)
        } catch (e: SecurityException) {
            alarm.setWindow(AlarmManager.RTC, at, 5_000L, pi)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }
}
