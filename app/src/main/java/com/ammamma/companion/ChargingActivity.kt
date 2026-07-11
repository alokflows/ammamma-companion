package com.ammamma.companion

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * The live charging screen. Appears when the charger is plugged in, wakes the
 * screen, and shows real information — percentage, battery health, and our own
 * estimated time-to-full — then reads a charging line aloud.
 *
 * It updates live while visible and closes itself when the charger is removed.
 * We intentionally do NOT keep the screen on forever (that would waste battery
 * while charging overnight) — the normal screen timeout applies.
 */
class ChargingActivity : Activity() {

    private lateinit var tvPercent: TextView
    private lateinit var tvHealth: TextView
    private lateinit var tvTime: TextView

    // Live updates: refresh on every battery change; close when unplugged.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> finish()
                Intent.ACTION_BATTERY_CHANGED -> render(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_charging)

        tvPercent = findViewById(R.id.tvPercent)
        tvHealth = findViewById(R.id.tvHealth)
        tvTime = findViewById(R.id.tvTime)
        findViewById<Button>(R.id.btnOk).setOnClickListener {
            // Cut off the spoken charging line the moment she closes the screen.
            Announcer.get(this).stopSpeaking()
            finish()
        }
        // The spoken "charger connected, X percent" line is said by BatteryWatcher
        // (one voice, no double-speak). This screen just shows the live details.
    }

    override fun onResume() {
        super.onResume()
        // registerReceiver returns the current sticky battery status immediately,
        // so the screen fills in the moment it opens.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val sticky = registerReceiver(receiver, filter)
        sticky?.let { render(it) }
    }

    override fun onPause() {
        runCatching { unregisterReceiver(receiver) }
        super.onPause()
    }

    private fun render(batteryIntent: Intent) {
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (scale > 0 && level >= 0) level * 100 / scale else level
        tvPercent.text = "$pct%"

        val health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        tvHealth.text = healthLabel(health)

        val mins = ChargeState.minutesToFull(pct)
        tvTime.text = when {
            pct >= 100 -> "నిండింది"          // full
            mins == null -> "లెక్కిస్తోంది…"    // calculating
            else -> "సుమారు " + durationLabel(mins)  // about <duration>
        }
    }

    private fun healthLabel(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "మంచిది"          // good
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "వేడిగా ఉంది" // overheating
        BatteryManager.BATTERY_HEALTH_COLD -> "చల్లగా ఉంది"     // cold
        BatteryManager.BATTERY_HEALTH_DEAD -> "పాడైంది"         // dead
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "వోల్టేజ్ ఎక్కువ"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "సమస్య ఉంది"
        else -> "తెలియదు"                                        // unknown
    }

    private fun durationLabel(mins: Int): String {
        if (mins < 60) return "$mins నిమిషాలు"        // minutes
        val h = mins / 60
        val m = mins % 60
        return if (m == 0) "$h గంట" else "$h గంట $m నిమిషాలు"  // hours [minutes]
    }

    companion object {
        fun show(context: Context) {
            // NO_USER_ACTION: an app-launched card must not fire onUserLeaveHint on the screen below (that silences the voice).
            val i = Intent(context, ChargingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            context.startActivity(i)
        }
    }
}
