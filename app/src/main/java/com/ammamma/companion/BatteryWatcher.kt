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
 * All the levels come from [Settings] — the family tunes them, no rebuild:
 *   - low % (default 20)      -> red card + repeating reminder
 *   - critical % (default 10) -> a second, more urgent red card
 *   - charged % (default 100) -> while charging, green "enough, unplug" card
 *
 * We warn ONCE per threshold (not on every 1% tick). The low warnings reset when
 * the level recovers; the charged warning resets when the charger is pulled —
 * one "take it off" per charge session, reminded but never nagged.
 */
class BatteryWatcher(private val announcer: Announcer) : BroadcastReceiver() {

    private var warnedLow = false
    private var warnedCritical = false
    private var warnedCharged = false

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
                // Travel mode: every charger event silently texts the family
                // WHERE the phone is — a breadcrumb trail when she's away from home.
                if (Settings.travelModeEnabled(context)) {
                    LocationReplyService.startTravelPing(context, pluggedIn = true)
                    TheftGuard.onChargerEvent(context)   // silent front+back photo + toast
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                val pct = currentPercent(context)
                ChargeState.onDisconnected()
                // A new charge session gets a fresh "enough, unplug" announcement.
                warnedCharged = false
                // "Charger removed, now X percent."
                announcer.announce("charger_removed", "ఛార్జర్ తీసేశారు, ఇప్పుడు $pct శాతం ఉంది")
                if (Settings.travelModeEnabled(context)) {
                    LocationReplyService.startTravelPing(context, pluggedIn = false)
                    TheftGuard.onChargerEvent(context)   // silent front+back photo + toast
                }
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

        // --- Charging: "charged enough, take me off" at the family-set level ---
        // Once per charge session (flag resets on unplug, not here — otherwise a
        // level like 80% would re-announce on every following 1% tick).
        val chargedAt = Settings.batteryChargedPercent(context)
        if (charging && (status == BatteryManager.BATTERY_STATUS_FULL || pct >= chargedAt)) {
            if (!warnedCharged) {
                warnedCharged = true
                if (pct >= 100 || status == BatteryManager.BATTERY_STATUS_FULL) {
                    // "Charging is full, please remove the charger."
                    announcer.announce("battery_full", "ఛార్జింగ్ నిండింది, ఛార్జర్ తీసేయండి")
                    AlertActivity.show(context, "బ్యాటరీ నిండింది", green = true)
                } else {
                    // "Charge is X percent — enough. Remove the charger."
                    announcer.announce("battery_full", "ఛార్జ్ $pct శాతం అయ్యింది, సరిపోతుంది. ఛార్జర్ తీసేయండి")
                    AlertActivity.show(context, "ఛార్జ్ $pct%\nసరిపోతుంది", green = true)
                }
            }
            return
        }

        if (charging) {
            // Plugged in below the target: clear the low warnings and the reminder.
            warnedLow = false
            warnedCritical = false
            CompanionService.stopBatteryReminder(context)
            return
        }

        // --- Discharging: warn at the family-set levels (critical takes priority) ---
        // "Charge is X percent, please charge it." (percentage spoken so she knows)
        val lowAt = Settings.batteryLowPercent(context)
        val criticalAt = Settings.batteryCriticalPercent(context)
        if (pct <= criticalAt && !warnedCritical) {
            warnedCritical = true
            announcer.announce("battery_low", "ఛార్జ్ $pct శాతం మాత్రమే ఉంది, వెంటనే ఛార్జ్ చేయండి")
            AlertActivity.show(context, "ఛార్జ్ $pct%\nఛార్జ్ చేయండి", green = false)
            CompanionService.startBatteryReminder(context)   // nag every N min until charged
        } else if (pct <= lowAt && !warnedLow) {
            warnedLow = true
            announcer.announce("battery_low", "ఛార్జ్ $pct శాతం ఉంది, దయచేసి ఛార్జ్ చేయండి")
            AlertActivity.show(context, "ఛార్జ్ $pct%\nఛార్జ్ చేయండి", green = false)
            CompanionService.startBatteryReminder(context)
        }

        // Reset flags once she's safely (5 points) above each threshold again.
        if (pct > criticalAt + 5) warnedCritical = false
        if (pct > lowAt + 5) warnedLow = false
    }

    companion object {
        private const val TAG = "Ammamma"
    }
}
