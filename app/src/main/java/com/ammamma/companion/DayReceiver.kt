package com.ammamma.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the day-clock alarms (hourly chime, morning heartbeat, talking alarm).
 * STUB: replaced by the WP-B implementation; exists so all branches compile.
 */
class DayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // WP-B fills this in (chime / heartbeat / alarm dispatch + reschedule).
    }
}
