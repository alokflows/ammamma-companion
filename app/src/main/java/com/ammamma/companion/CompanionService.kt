package com.ammamma.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * The companion's heartbeat.
 *
 * ColorOS (and Android 8 generally) kills background apps to save battery. A
 * foreground service with a persistent, low-importance notification is the one
 * reliable way to stay alive so we can watch the battery, announce callers, etc.
 *
 * It owns the single [Announcer] (voice) and the [BatteryWatcher] (which listens
 * for battery/charger events). Everything that must keep working with the screen
 * off is anchored here.
 */
class CompanionService : Service() {

    private lateinit var announcer: Announcer
    private var battery: BatteryWatcher? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        announcer = Announcer.get(applicationContext)
        battery = BatteryWatcher(announcer).also {
            registerReceiver(it, it.filter())
        }
        Log.i(TAG, "Companion service started; battery watcher registered")
    }

    private fun startAsForeground() {
        val channelId = "companion"
        val nm = getSystemService(NotificationManager::class.java)
        // LOW importance = no sound, no intrusion — just a quiet "I'm here" badge.
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Ammamma Companion", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Ammamma tōḍu")
            .setContentText("Nēnu ikkaḍē unnā")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    // START_STICKY: if the system kills us, restart as soon as it can.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        battery?.let { runCatching { unregisterReceiver(it) } }
        if (::announcer.isInitialized) announcer.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Ammamma"
        private const val NOTIFICATION_ID = 1
    }
}
