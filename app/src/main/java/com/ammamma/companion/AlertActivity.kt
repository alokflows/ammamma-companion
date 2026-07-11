package com.ammamma.companion

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The giant full-screen "do ONE thing" card.
 *
 * It shows over the lock screen and wakes the screen, so even if the phone is
 * asleep in her hand she sees it. Exactly one huge button dismisses it — there is
 * nothing else on screen to read or understand.
 *
 * Design choice worth knowing: we use a full-screen Activity, NOT a
 * SYSTEM_ALERT_WINDOW overlay. An overlay needs a special permission that ColorOS
 * hides in its settings and often blocks. A full-screen Activity needs no such
 * permission, so this card "just works" the day we sideload. (targetSdk 27 also
 * lets us launch it from the background, which newer Android would restrict.)
 */
class AlertActivity : Activity() {

    // REPEAT ENGINE: the alert re-speaks its line for a family-set window
    // (Settings.alertRepeatSeconds) after appearing — she may be in another room
    // when the first line plays. The first utterance itself is spoken by the
    // caller (BatteryWatcher, speech-first); this loop only handles the repeats.
    private val ui = Handler(Looper.getMainLooper())
    private var repeatLoop: Runnable? = null

    // Charger state changing IS her answer to a battery alert: plugging in answers
    // "please charge", unplugging answers "remove the charger". Either way, stop
    // repeating immediately — nagging past the fix would be maddening.
    private var receiverRegistered = false
    private val chargerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            cancelRepeats()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen + turn the screen on. These flags work on API 26/27.
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        registerReceiver(chargerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        receiverRegistered = true
        render(intent)
    }

    // This activity is singleTask, so a NEW alert (e.g. red "charge me" replacing a
    // stale green card) arrives here instead of a fresh onCreate. Redraw for it.
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        render(newIntent)
    }

    // The repeat loop must NEVER outlive the screen (a voice that won't stop was
    // this app's worst historical bug). Cancel on ANY pause/finish; onDestroy also
    // covers paths where onPause was skipped and drops the receiver.
    override fun onPause() {
        cancelRepeats()
        super.onPause()
    }

    override fun onDestroy() {
        cancelRepeats()
        if (receiverRegistered) {
            receiverRegistered = false
            runCatching { unregisterReceiver(chargerReceiver) }
        }
        super.onDestroy()
    }

    /**
     * Start (or restart, on a replacing alert) the repeat window. Polls every
     * ~500ms and only re-speaks when the voice is idle AND has been quiet for
     * ~2.5s — a line is never chopped by its own repeat. Each repeat carries the
     * same [EXTRA_IMPORTANT] flag as the first utterance, so a low-battery repeat
     * always sounds while a "charged enough" repeat still respects the mute.
     */
    private fun startRepeats(intent: Intent) {
        cancelRepeats()
        val text = intent.getStringExtra(EXTRA_REPEAT_TEXT) ?: return
        if (text.isBlank()) return
        val windowSecs = Settings.alertRepeatSeconds(this)
        if (windowSecs <= 0) return   // 0 = speak once only (the caller already did)
        val important = intent.getBooleanExtra(EXTRA_IMPORTANT, false)
        val announcer = Announcer.get(this)
        val deadline = SystemClock.elapsedRealtime() + windowSecs * 1000L

        val loop = object : Runnable {
            // When the voice last went idle; 0 = it is (or was just) speaking.
            var quietSince = 0L
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                if (now >= deadline) {
                    repeatLoop = null   // window over — loop dies on its own
                    return
                }
                if (announcer.isSpeaking()) {
                    quietSince = 0L
                } else if (quietSince == 0L) {
                    quietSince = now
                } else if (now - quietSince >= QUIET_GAP_MS) {
                    announcer.say(text, important)
                    quietSince = 0L
                }
                ui.postDelayed(this, POLL_MS)
            }
        }
        repeatLoop = loop
        // First poll after one interval: the caller's own first utterance is
        // usually still starting up (TTS queue), and polling finds that out.
        ui.postDelayed(loop, POLL_MS)
    }

    private fun cancelRepeats() {
        repeatLoop?.let { ui.removeCallbacks(it) }
        repeatLoop = null
    }

    private fun render(intent: Intent) {
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "!"
        val isGood = intent.getBooleanExtra(EXTRA_GREEN, false)
        val bg = if (isGood) Color.parseColor("#1E8E3E") else Color.parseColor("#C62828")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        root.addView(TextView(this).apply {
            text = message
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        // One enormous, unmissable dismiss button.
        root.addView(Button(this).apply {
            text = "సరే"   // "OK"
            textSize = 34f
            // Green = the safe, always-right tap (drawable shared with FindPhone's ఆపు).
            setBackgroundResource(R.drawable.btn_primary_green)
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener {
                // Silence any line still playing the instant she taps — don't let it
                // finish on its own — and kill the repeat loop with it.
                cancelRepeats()
                Announcer.get(this@AlertActivity).stopSpeaking()
                finish()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.22f).toInt()
        ).apply { topMargin = dp(40) })

        // Low-battery only: a snooze button that silences the repeating reminder.
        if (!isGood) {
            root.addView(Button(this).apply {
                text = "తర్వాత చెప్పు"   // "tell me later" (snooze)
                textSize = 26f
                setBackgroundResource(R.drawable.btn_outline)
                isAllCaps = false
                setOnClickListener {
                    cancelRepeats()
                    Announcer.get(this@AlertActivity).stopSpeaking()
                    CompanionService.stopBatteryReminder(this@AlertActivity)
                    finish()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
        }

        setContentView(root)
        startRepeats(intent)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_GREEN = "green"
        private const val EXTRA_REPEAT_TEXT = "repeat_text"
        private const val EXTRA_IMPORTANT = "important"

        private const val POLL_MS = 500L         // how often the loop checks the voice
        private const val QUIET_GAP_MS = 2_500L  // silence required between repeats

        /**
         * Launch the card from anywhere (service, receiver). [repeatText] is the
         * spoken line to KEEP repeating for Settings.alertRepeatSeconds — pass null
         * for a silent card (e.g. when the family turned that category's voice off).
         * The caller speaks the FIRST utterance itself before/while calling this
         * (speech-first: the essential line must never wait for a window to appear);
         * [important] must match that first utterance so repeats behave identically.
         */
        fun show(
            context: Context,
            message: String,
            green: Boolean,
            repeatText: String? = null,
            important: Boolean = false
        ) {
            val i = Intent(context, AlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_GREEN, green)
                putExtra(EXTRA_REPEAT_TEXT, repeatText)
                putExtra(EXTRA_IMPORTANT, important)
            }
            context.startActivity(i)
        }
    }
}
