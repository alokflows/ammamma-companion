package com.ammamma.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Owns all AlarmManager work for the day clock (WP-B):
 *   - hourly chime  ("సమయం మూడు గంటలు")
 *   - morning heartbeat ("శుభోదయం అమ్మమ్మ")
 *   - talking alarm (full-screen card + repeat)
 *
 * Design: three EXACT alarms via setExactAndAllowWhileIdle so they fire even in
 * Doze — this phone sits idle on a table all day, so inexact alarms would drift
 * by up to an hour. Exact alarms don't repeat, so each fire re-arms the next
 * one from DayReceiver (self-rescheduling chain). scheduleAll() is idempotent:
 * FLAG_UPDATE_CURRENT + a fixed request code per alarm means calling it again
 * just replaces the pending alarms instead of stacking duplicates.
 *
 * We ALWAYS schedule all three, even when their toggles are off. The receiver
 * checks Settings at fire time, so flipping a toggle in SettingsActivity takes
 * effect at the very next fire without anyone having to reschedule anything.
 */
object DayScheduler {

    private const val TAG = "Ammamma"

    // Distinct actions so DayReceiver knows which alarm fired.
    const val ACTION_CHIME = "com.ammamma.companion.action.CHIME"
    const val ACTION_HEARTBEAT = "com.ammamma.companion.action.HEARTBEAT"
    const val ACTION_ALARM = "com.ammamma.companion.action.ALARM"

    // Distinct request codes so the three PendingIntents never collide
    // (same receiver class + same flags would otherwise overwrite each other).
    private const val REQ_CHIME = 201
    private const val REQ_HEARTBEAT = 202
    private const val REQ_ALARM = 203

    /**
     * (Re)schedules all three day-clock alarms. Safe to call any number of
     * times — from service start, boot, or after a settings change. Times are
     * recomputed from Settings on every call so edited heartbeat/alarm times
     * take effect immediately.
     */
    fun scheduleAll(context: Context) {
        scheduleChime(context)
        scheduleHeartbeat(context)
        scheduleAlarm(context)
    }

    /** Arms the chime for the next top of the hour. */
    fun scheduleChime(context: Context) {
        // Zero out minutes/seconds then add an hour: at 14:37:12 this yields
        // 15:00:00, always strictly in the future.
        val next = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        setExact(context, ACTION_CHIME, REQ_CHIME, next.timeInMillis)
        Log.i(TAG, "Chime scheduled for ${next.time}")
    }

    /** Arms the morning heartbeat for the next heartbeatHour:heartbeatMinute. */
    fun scheduleHeartbeat(context: Context) {
        val at = nextDailyOccurrence(Settings.heartbeatHour(context), Settings.heartbeatMinute(context))
        setExact(context, ACTION_HEARTBEAT, REQ_HEARTBEAT, at.timeInMillis)
        Log.i(TAG, "Heartbeat scheduled for ${at.time}")
    }

    /** Arms the talking alarm for the next alarmHour:alarmMinute. */
    fun scheduleAlarm(context: Context) {
        val at = nextDailyOccurrence(Settings.alarmHour(context), Settings.alarmMinute(context))
        setExact(context, ACTION_ALARM, REQ_ALARM, at.timeInMillis)
        Log.i(TAG, "Alarm scheduled for ${at.time}")
    }

    /**
     * Next occurrence of hour:minute — today if that moment is still ahead,
     * otherwise tomorrow. Also covers "right now": we push to tomorrow rather
     * than fire an alarm for a time that has already ticked past.
     */
    private fun nextDailyOccurrence(hour: Int, minute: Int): Calendar {
        val now = Calendar.getInstance()
        return (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun setExact(context: Context, action: String, requestCode: Int, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DayReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // setExactAndAllowWhileIdle: fires on time even in Doze. That is the
        // whole point of the day clock — a chime at 15:23 is worse than none.
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
    }
}
