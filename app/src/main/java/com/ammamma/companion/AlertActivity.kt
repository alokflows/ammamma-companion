package com.ammamma.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen + turn the screen on. These flags work on API 26/27.
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        render(intent)
    }

    // This activity is singleTask, so a NEW alert (e.g. red "charge me" replacing a
    // stale green card) arrives here instead of a fresh onCreate. Redraw for it.
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        render(newIntent)
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
            setOnClickListener {
                // Silence any line still playing the instant she taps — don't let it
                // finish on its own.
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
                setOnClickListener {
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
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_GREEN = "green"

        /** Launch the card from anywhere (service, receiver). */
        fun show(context: Context, message: String, green: Boolean) {
            val i = Intent(context, AlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_GREEN, green)
            }
            context.startActivity(i)
        }
    }
}
