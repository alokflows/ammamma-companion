package com.ammamma.companion

import android.os.SystemClock

/**
 * Estimates "time until full" ourselves.
 *
 * Android 8.1 (API 27) has NO system API for this — BatteryManager.computeChargeTimeRemaining()
 * only arrived in Android 9. So we measure it: remember the % and the clock time when
 * charging began, and from how fast the % is climbing, extrapolate to 100%.
 *
 * This lives in the always-alive service, so it keeps measuring across the whole
 * charging session even if the charging screen is only shown briefly.
 *
 * It's an ESTIMATE, and we say so — early on (before a measurable rise) it honestly
 * reports "not yet known" rather than guessing wildly.
 */
object ChargeState {

    private var startElapsedMs = 0L
    private var startPct = -1

    /** Call when the charger is plugged in. */
    fun onConnected(pct: Int) {
        startElapsedMs = SystemClock.elapsedRealtime()
        startPct = pct
    }

    /** Call when the charger is removed. */
    fun onDisconnected() {
        startPct = -1
    }

    val isCharging: Boolean get() = startPct >= 0

    /**
     * Minutes until full, or null if we can't estimate yet (just plugged in, or the
     * level hasn't risen enough to measure a rate).
     */
    fun minutesToFull(currentPct: Int): Int? {
        if (startPct < 0 || currentPct <= startPct) return null
        val elapsedMin = (SystemClock.elapsedRealtime() - startElapsedMs) / 60000.0
        if (elapsedMin < 0.25) return null            // too soon to be meaningful
        val pctPerMin = (currentPct - startPct) / elapsedMin
        if (pctPerMin <= 0) return null
        return Math.ceil((100 - currentPct) / pctPerMin).toInt()
    }
}
