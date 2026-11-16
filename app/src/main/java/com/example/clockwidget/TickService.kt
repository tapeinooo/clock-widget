package com.example.clockwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * アプリに入れたフォントのときだけ動く常駐。
 * 毎分の合図（TIME_TICK）と画面が付いた合図は、動いているアプリにしか届かないので、常駐して受け取り、ウィジェットを描き直す。
 * 画面が消えている間は TIME_TICK で端末を起こさないので、電池はほとんど使わない。
 */
class TickService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = ClockWidget.updateAll(context)
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "時刻の更新", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("時計ウィジェットの時刻を更新しています")
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, 1, n, type)
        } catch (e: Exception) {
            // 裏からは常駐を始められないことがある。次の機会（目覚まし・設定画面）にやり直す
            stopSelf()
            return START_NOT_STICKY
        }
        ClockWidget.updateAll(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "tick"
        @Volatile private var running = false

        /** 要るなら始め、要らなければ止める。 */
        fun sync(context: Context, needed: Boolean) {
            val intent = Intent(context, TickService::class.java)
            if (!needed) {
                context.stopService(intent)
            } else if (!running) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    // 裏からは始められないことがある（毎分の目覚ましが予備で描き直す）
                }
            }
        }
    }
}
