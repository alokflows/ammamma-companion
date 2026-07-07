package com.ammamma.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button

/**
 * The "here I am!" STOP screen — pure UI, no sound of its own.
 *
 * The alarm itself lives in [CompanionService] (see startFindAlarmNow there for
 * why). This screen is just the giant stop button, reachable three ways:
 *   1. direct launch when the code-word SMS arrives,
 *   2. the full-screen notification the service posts,
 *   3. MainActivity.onResume when the alarm is active (open the app -> stop button).
 * Whichever way she (or family) arrives, one tap ends the noise.
 */
class FindPhoneActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake the screen and show over the lock screen — a lost phone must light up.
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        setContentView(R.layout.activity_findphone)
        findViewById<Button>(R.id.stop).setOnClickListener {
            CompanionService.stopFindAlarm(this)
            // Also cut off any companion announcement that happens to be speaking, so
            // one tap really does produce silence.
            Announcer.get(this).stopSpeaking()
            finish()
        }
    }

    companion object {
        /**
         * Ring + (best-effort) show the stop screen. The sound is guaranteed —
         * it starts in the foreground service regardless of whether ColorOS
         * lets this activity become visible.
         */
        fun show(context: Context) {
            CompanionService.startFindAlarm(context)
            runCatching {
                context.startActivity(
                    Intent(context, FindPhoneActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }.onFailure { Log.w("Ammamma", "Find-phone screen launch blocked; notification path remains", it) }
        }
    }
}
