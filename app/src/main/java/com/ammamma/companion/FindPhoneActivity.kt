package com.ammamma.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.ammamma.companion.ui.GlowBackdrop
import com.ammamma.companion.ui.Press

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

    private lateinit var glow: GlowBackdrop

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

        // WP-LUX: red mood, and here the blobs also slowly pulse alpha —
        // urgency without panic.
        glow = findViewById<GlowBackdrop>(R.id.glow).also {
            it.setMood(Color.rgb(255, 75, 75), Color.rgb(198, 40, 40), Color.rgb(255, 107, 74))
            it.setPulsing(true)
        }
        // Single card scales in from 0.94 + fades.
        findViewById<View>(R.id.card).apply {
            alpha = 0f; scaleX = 0.94f; scaleY = 0.94f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        }

        findViewById<Button>(R.id.stop).also { btn ->
            btn.setOnClickListener {
                CompanionService.stopFindAlarm(this)
                // Also cut off any companion announcement that happens to be speaking, so
                // one tap really does produce silence.
                Announcer.get(this).stopSpeaking()
                finish()
            }
            Press.attach(btn, cornerRadiusDp = 22f, addRipple = false)  // btn_primary_green already ripples
        }
    }

    override fun onStart() {
        super.onStart()
        glow.start()
    }

    override fun onStop() {
        glow.stop()
        super.onStop()
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
                // NO_USER_ACTION: an app-launched card must not fire onUserLeaveHint on the screen below (that silences the voice).
                context.startActivity(
                    Intent(context, FindPhoneActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                )
            }.onFailure { Log.w("Ammamma", "Find-phone screen launch blocked; notification path remains", it) }
        }
    }
}
