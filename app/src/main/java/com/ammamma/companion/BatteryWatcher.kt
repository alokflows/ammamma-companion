package com.ammamma.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Watches battery level + charger events and, at the right moments, asks the
 * [Announcer] to speak and pops the giant dismiss card ([AlertActivity]).
 *
 * Registered dynamically by [CompanionService] so it lives exactly as long as the
 * foreground service. (ACTION_BATTERY_CHANGED cannot be declared in the manifest,
 * so a dynamic receiver tied to our always-alive service is the correct home.)
 *
 * We warn ONCE per threshold (not on every 1% tick) and reset the flags when the
 * phone is charged back up, so she is reminded but never nagged.
 */
class BatteryWatcher(private val announcer: Announcer) : BroadcastReceiver() {

    private var warned20 = false
    private var warned10 = false
    private var warnedFull = false

    fun filter(): IntentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                val pct = currentPercent(context)
                ChargeState.onConnected(pct)
                // "Charger connected, now X percent."
                announcer.announce("charger_connected", "ఛార్జర్ పెట్టారు, ఇప్పుడు $pct శాతం ఉంది")
                ChargingActivity.show(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                val pct = currentPercent(context)
                ChargeState.onDisconnected()
                // "Charger removed, now X percent."
                announcer.announce("charger_removed", "ఛార్జర్ తీసేశారు, ఇప్పుడు $pct శాతం ఉంది")
            }
            Intent.ACTION_BATTERY_CHANGED -> handleLevel(context, intent)
        }
    }

    /** Read the current battery % from the sticky battery broadcast. */
    private fun currentPercent(context: Context): Int {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (scale > 0 && level >= 0) level * 100 / scale else level
    }

    private fun handleLevel(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0) return

        val pct = if (scale > 0) level * 100 / scale else level
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        Log.i(TAG, "Battery $pct%, charging=$charging")

        // --- Battery full: gentle "take me off the charger" ---
        // Only when actually charging/full — 100% while UNPLUGGED is not "full".
        if (charging && (status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100)) {
            if (!warnedFull) {
                warnedFull = true
                // "Charging is full, please remove the charger."
                announcer.announce("battery_full", "ఛార్జింగ్ నిండింది, ఛార్జర్ తీసేయండి")
                AlertActivity.show(context, "బ్యాటరీ నిండింది", green = true)
            }
            return
        }
        warnedFull = false

        if (charging) {
            // Plugged in and climbing: clear the low-battery reminders.
            warned20 = false
            warned10 = false
            return
        }

        // --- Discharging: warn at 10% then 20% (10% takes priority) ---
        // "Charge is X percent, please charge it." (percentage spoken so she knows)
        if (pct <= 10 && !warned10) {
            warned10 = true
            announcer.announce("battery_low", "ఛార్జ్ $pct శాతం ఉంది, దయచేసి ఛార్జ్ చేయండి")
            AlertActivity.show(context, "ఛార్జ్ $pct%\nఛార్జ్ చేయండి", green = false)
        } else if (pct <= 20 && !warned20) {
            warned20 = true
            announcer.announce("battery_low", "ఛార్జ్ $pct శాతం ఉంది, దయచేసి ఛార్జ్ చేయండి")
            AlertActivity.show(context, "ఛార్జ్ $pct%\nఛార్జ్ చేయండి", green = false)
        }

        // Reset flags once she's safely above each threshold again.
        if (pct > 15) warned10 = false
        if (pct > 25) warned20 = false
    }

    companion object {
        private const val TAG = "Ammamma"
    }
}
