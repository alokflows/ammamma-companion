package com.ammamma.companion

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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

    // Find-my-phone alarm state (sound + vibration owned HERE, never by a screen).
    private var findPlayer: MediaPlayer? = null
    private var findVibrator: Vibrator? = null
    private var findTimeout: Runnable? = null
    private var savedAlarmVolume = -1

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        announcer = Announcer.get(applicationContext)
        battery = BatteryWatcher(announcer).also { registerReceiver(it, it.filter()) }
        armWatchdog()
        Log.i(TAG, "Companion service started; battery watcher registered")
    }

    /**
     * She swiped the app away (or ColorOS "cleaned" it). The service dies with the
     * task — so before that, arm an alarm that restarts us 1.5s later. To Ammamma
     * the companion simply never goes away.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExact(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1_500,
            restartIntent(this, REQ_RESURRECT)
        )
        Log.i(TAG, "Task removed -> resurrection alarm armed")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Heartbeat: every ~15 min an alarm start-commands this service. If we're
     * already running it's a harmless no-op; if ColorOS silently killed us, it
     * brings us back. Deliberately NEVER cancelled (not even in onDestroy) —
     * being resurrected after a kill is the whole point.
     */
    private fun armWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_MS,
            WATCHDOG_MS,
            restartIntent(this, REQ_WATCHDOG)
        )
    }

    private fun restartIntent(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            ctx,
            requestCode,
            Intent(ctx, CompanionService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALLER_START -> startCallerLoop(
                intent.getStringExtra(EXTRA_KEY) ?: "caller_unknown",
                intent.getStringExtra(EXTRA_TEXT) ?: "ఎవరో ఫోన్ చేస్తున్నారు"
            )
            ACTION_CALLER_STOP -> stopCallerLoop()
            ACTION_BATTERY_START -> startBatteryLoop()
            ACTION_BATTERY_STOP -> stopBatteryLoop()
            ACTION_FIND_START -> startFindAlarmNow()
            ACTION_FIND_STOP -> stopFindAlarmNow()
        }
        return START_STICKY
    }

    // --- FIND MY PHONE: the alarm lives in the SERVICE, never in an activity ---
    // ColorOS can silently block a background activity from becoming VISIBLE while
    // its code still runs. If the sound lived in the activity (as it once did), that
    // meant an invisible, unstoppable alarm. Owning sound + stop here guarantees the
    // alarm and its stop-path always exist together, whatever ColorOS does to the UI.
    private fun startFindAlarmNow() {
        if (isFindAlarmActive) return   // already sounding
        isFindAlarmActive = true

        // Max the ALARM stream (it sounds even on silent), remembering her volume.
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        runCatching {
            findPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@CompanionService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.e(TAG, "Find-alarm sound failed to start", it) }

        findVibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        findVibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))

        postFindNotification()

        // Auto-stop: an alarm that never ends is terrifying. Three minutes is
        // plenty to locate a phone inside a house.
        val timeout = Runnable { stopFindAlarmNow() }
        findTimeout = timeout
        handler.postDelayed(timeout, FIND_AUTO_STOP_MS)

        Log.i(TAG, "Find-alarm STARTED (auto-stop in ${FIND_AUTO_STOP_MS / 1000}s)")
    }

    private fun stopFindAlarmNow() {
        if (!isFindAlarmActive) return
        isFindAlarmActive = false

        findTimeout?.let { handler.removeCallbacks(it) }
        findTimeout = null
        findPlayer?.run { runCatching { if (isPlaying) stop() }; release() }
        findPlayer = null
        findVibrator?.cancel()
        findVibrator = null

        if (savedAlarmVolume >= 0) {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            savedAlarmVolume = -1
        }
        getSystemService(NotificationManager::class.java).cancel(FIND_NOTIFICATION_ID)
        Log.i(TAG, "Find-alarm STOPPED")
    }

    /**
     * Second road to the stop button: a full-screen, alarm-category notification.
     * If ColorOS blocks our direct activity launch, Android itself shows
     * [FindPhoneActivity] (screen off / locked) or a heads-up banner to tap.
     */
    private fun postFindNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(FIND_CHANNEL, "Find phone", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, FindPhoneActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, FIND_CHANNEL)
            .setContentTitle("ఇక్కడ ఉన్నా! · Phone is here")
            .setContentText("ఆపడానికి నొక్కండి · Tap to stop")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        nm.notify(FIND_NOTIFICATION_ID, n)
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
                // Stop nagging once she's a couple of percent above the FAMILY'S
                // configured low threshold (not a hardcoded one) or plugged in.
                if (charging || pct < 0 || pct > Settings.batteryLowPercent(this@CompanionService) + 2) {
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
        stopFindAlarmNow()   // never leave the alarm orphaned if the service dies
        if (::announcer.isInitialized) announcer.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Ammamma"
        private const val NOTIFICATION_ID = 1
        private const val FIND_NOTIFICATION_ID = 2          // separate from the heartbeat
        private const val FIND_CHANNEL = "findphone"
        private const val FIND_AUTO_STOP_MS = 3 * 60_000L   // safety: alarm always ends

        /** True while the find-phone alarm is sounding. MainActivity checks this so
         *  opening the app ALWAYS lands on the stop button. */
        @Volatile
        var isFindAlarmActive = false
            private set

        private const val WATCHDOG_MS = 15 * 60_000L    // heartbeat interval
        private const val REQ_RESURRECT = 11            // distinct request codes so the
        private const val REQ_WATCHDOG = 12             // two alarms never replace each other

        private const val CALLER_INTERVAL_MS = 5_000L   // repeat the name every 5s while ringing
        private const val CALLER_MAX_REPEATS = 12       // ~1 min safety cap

        const val ACTION_CALLER_START = "com.ammamma.companion.CALLER_START"
        const val ACTION_CALLER_STOP = "com.ammamma.companion.CALLER_STOP"
        const val ACTION_BATTERY_START = "com.ammamma.companion.BATTERY_START"
        const val ACTION_BATTERY_STOP = "com.ammamma.companion.BATTERY_STOP"
        const val ACTION_FIND_START = "com.ammamma.companion.FIND_START"
        const val ACTION_FIND_STOP = "com.ammamma.companion.FIND_STOP"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TEXT = "text"

        fun startCaller(ctx: Context, key: String, text: String) = send(ctx, ACTION_CALLER_START) {
            it.putExtra(EXTRA_KEY, key); it.putExtra(EXTRA_TEXT, text)
        }
        fun stopCaller(ctx: Context) = send(ctx, ACTION_CALLER_STOP) {}
        fun startBatteryReminder(ctx: Context) = send(ctx, ACTION_BATTERY_START) {}
        fun stopBatteryReminder(ctx: Context) = send(ctx, ACTION_BATTERY_STOP) {}
        fun startFindAlarm(ctx: Context) = send(ctx, ACTION_FIND_START) {}
        fun stopFindAlarm(ctx: Context) = send(ctx, ACTION_FIND_STOP) {}

        private inline fun send(ctx: Context, action: String, extras: (Intent) -> Unit) {
            val i = Intent(ctx, CompanionService::class.java).setAction(action)
            extras(i)
            ctx.startForegroundService(i)
        }
    }
}
