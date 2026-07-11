package com.ammamma.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Receives the day-clock alarms (hourly chime, morning heartbeat, talking
 * alarm) armed by DayScheduler.
 *
 * Order matters: we RESCHEDULE FIRST, then act. Exact alarms don't repeat, so
 * if announcing crashed before we re-armed, the whole day clock would silently
 * die until the next reboot. Rescheduling first (plus a try/catch around the
 * action body) makes the chain unbreakable.
 *
 * Toggles are checked HERE, at fire time — not at schedule time. The alarms
 * are always armed; a disabled feature just fires into a no-op. That way
 * flipping a switch in SettingsActivity needs no reschedule plumbing.
 */
class DayReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Ammamma"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DayScheduler.ACTION_CHIME -> {
                DayScheduler.scheduleChime(context) // re-arm before anything can throw
                try {
                    fireChime(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "Chime failed", t)
                }
            }
            DayScheduler.ACTION_HEARTBEAT -> {
                DayScheduler.scheduleHeartbeat(context)
                try {
                    fireHeartbeat(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "Heartbeat failed", t)
                }
            }
            DayScheduler.ACTION_ALARM -> {
                DayScheduler.scheduleAlarm(context)
                try {
                    fireAlarm(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "Alarm failed", t)
                }
            }
            else -> Log.w(TAG, "DayReceiver: unknown action ${intent.action}")
        }
    }

    /** Hourly chime: "సమయం మూడు గంటలు" — skipped during quiet hours or when off. */
    private fun fireChime(context: Context) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) // 0..23
        val quiet = isQuietHour(context, hour)
        Log.i(TAG, "Chime fired hour=$hour quiet=$quiet")
        if (!Settings.chimesEnabled(context) || quiet) return

        // Clip key uses the 24h hour (hour_0..hour_23, one recording each);
        // the spoken fallback uses the 12h number people actually say.
        val display = when {
            hour == 0 || hour == 12 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        // Not important: an hourly chime must respect mute — it is ambience,
        // not an emergency.
        Announcer.get(context).announce("hour_$hour", "సమయం $display గంటలు")
    }

    /** Morning heartbeat: a daily "శుభోదయం" so the house hears a friendly voice. */
    private fun fireHeartbeat(context: Context) {
        val enabled = Settings.heartbeatEnabled(context)
        Log.i(TAG, "Heartbeat fired enabled=$enabled")
        if (!enabled) return
        Announcer.get(context).announce("goodmorning", "శుభోదయం అమ్మమ్మ")
    }

    /** Talking alarm: full-screen green card + voice, repeats until సరే. */
    private fun fireAlarm(context: Context) {
        val enabled = Settings.alarmEnabled(context)
        Log.i(TAG, "Alarm fired enabled=$enabled")
        if (!enabled) return
        val text = "అమ్మమ్మ, సమయం అయింది"
        // important=true: an alarm she asked for must sound even when muted.
        // Speech-first convention: caller speaks the first utterance, the card
        // then repeats it every alertRepeatSeconds until dismissed.
        Announcer.get(context).announce("alarm", text, important = true)
        // AlertActivity.show already adds FLAG_ACTIVITY_NO_USER_ACTION for us.
        AlertActivity.show(context, text, green = true, repeatText = text, important = true)
    }

    /**
     * Quiet hours with midnight wrap. With start s and end e:
     *   s == e -> never quiet (a zero-length window, not a 24h one)
     *   s <  e -> quiet when hour in [s, e)        e.g. 13..15
     *   s >  e -> quiet when hour >= s or < e      e.g. 21..7 wraps midnight
     */
    private fun isQuietHour(context: Context, hour: Int): Boolean {
        val start = Settings.quietStartHour(context)
        val end = Settings.quietEndHour(context)
        return when {
            start == end -> false
            start < end -> hour in start until end
            else -> hour >= start || hour < end
        }
    }
}
