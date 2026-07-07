package com.ammamma.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * The companion's heartbeat AND its repeat-engine.
 *
 * A foreground service keeps us alive on ColorOS. It also owns the two repeating
 * announcements, because a one-shot line is useless:
 *   - CALLER: repeats "<name> is calling" every few seconds WHILE the phone rings,
 *     and stops the instant she answers or the call ends.
 *   - BATTERY LOW: repeats "please charge" every N minutes (Settings) until the
 *     charger goes in — then it shuts up. A snooze button can silence it early.
 */
class CompanionService : Service() {

    private lateinit var announcer: Announcer
    private var battery: BatteryWatcher? = null
    private val handler = Handler(Looper.getMainLooper())

    private var callerLoop: Runnable? = null
    private var callerCount = 0
    private var batteryLoop: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        announcer = Announcer.get(applicationContext)
        battery = BatteryWatcher(announcer).also { registerReceiver(it, it.filter()) }
        Log.i(TAG, "Companion service started; battery watcher registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALLER_START -> startCallerLoop(
                intent.getStringExtra(EXTRA_KEY) ?: "caller_unknown",
                intent.getStringExtra(EXTRA_TEXT) ?: "ఎవరో ఫోన్ చేస్తున్నారు"
            )
            ACTION_CALLER_STOP -> stopCallerLoop()
            ACTION_BATTERY_START -> startBatteryLoop()
            ACTION_BATTERY_STOP -> stopBatteryLoop()
        }
        return START_STICKY
    }

    // --- CALLER: repeat while ringing ---
    private fun startCallerLoop(key: String, text: String) {
        stopCallerLoop()
        // Quiet the ringtone (never silence it) so the spoken name wins over it.
        announcer.duckRingForSpeech()
        callerCount = 0
        val r = object : Runnable {
            override fun run() {
                announcer.announce(key, text)
                callerCount++
                if (callerCount < CALLER_MAX_REPEATS) handler.postDelayed(this, CALLER_INTERVAL_MS)
                else stopCallerLoop()   // safety cap in case we miss the "idle" signal
            }
        }
        callerLoop = r
        handler.post(r)   // announce immediately, then every few seconds
    }

    private fun stopCallerLoop() {
        callerLoop?.let { handler.removeCallbacks(it) }
        callerLoop = null
        // ALWAYS restore her ring volume (no-op if nothing was ducked) — answered,
        // ended, or the safety cap: the ringtone goes back exactly as she had it.
        announcer.restoreRing()
    }

    // --- BATTERY LOW: repeat every N minutes until charging ---
    private fun startBatteryLoop() {
        stopBatteryLoop()
        val intervalMs = Settings.batteryReminderMinutes(this).coerceAtLeast(1) * 60_000L
        val r = object : Runnable {
            override fun run() {
                val (pct, charging) = readBattery()
                if (charging || pct < 0 || pct > LOW_CLEAR_PCT) {
                    stopBatteryLoop()   // charged (or plugged in) → shut up
                    return
                }
                announcer.announce("battery_low", "ఛార్జ్ $pct శాతం ఉంది, దయచేసి ఛార్జ్ చేయండి")
                handler.postDelayed(this, intervalMs)
            }
        }
        batteryLoop = r
        // The first line was already spoken by BatteryWatcher; start the repeats after one interval.
        handler.postDelayed(r, intervalMs)
    }

    private fun stopBatteryLoop() {
        batteryLoop?.let { handler.removeCallbacks(it) }
        batteryLoop = null
    }

    private fun readBattery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (scale > 0 && level >= 0) level * 100 / scale else level
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun startAsForeground() {
        val channelId = "companion"
        val nm = getSystemService(NotificationManager::class.java)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        battery?.let { runCatching { unregisterReceiver(it) } }
        stopCallerLoop()
        stopBatteryLoop()
        if (::announcer.isInitialized) announcer.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Ammamma"
        private const val NOTIFICATION_ID = 1

        private const val CALLER_INTERVAL_MS = 5_000L   // repeat the name every 5s while ringing
        private const val CALLER_MAX_REPEATS = 12       // ~1 min safety cap
        private const val LOW_CLEAR_PCT = 22            // above this, stop nagging

        const val ACTION_CALLER_START = "com.ammamma.companion.CALLER_START"
        const val ACTION_CALLER_STOP = "com.ammamma.companion.CALLER_STOP"
        const val ACTION_BATTERY_START = "com.ammamma.companion.BATTERY_START"
        const val ACTION_BATTERY_STOP = "com.ammamma.companion.BATTERY_STOP"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TEXT = "text"

        fun startCaller(ctx: Context, key: String, text: String) = send(ctx, ACTION_CALLER_START) {
            it.putExtra(EXTRA_KEY, key); it.putExtra(EXTRA_TEXT, text)
        }
        fun stopCaller(ctx: Context) = send(ctx, ACTION_CALLER_STOP) {}
        fun startBatteryReminder(ctx: Context) = send(ctx, ACTION_BATTERY_START) {}
        fun stopBatteryReminder(ctx: Context) = send(ctx, ACTION_BATTERY_STOP) {}

        private inline fun send(ctx: Context, action: String, extras: (Intent) -> Unit) {
            val i = Intent(ctx, CompanionService::class.java).setAction(action)
            extras(i)
            ctx.startForegroundService(i)
        }
    }
}
