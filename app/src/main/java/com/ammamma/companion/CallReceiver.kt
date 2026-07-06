package com.ammamma.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Announces WHO is calling, out loud in Telugu — the feature Ammamma needs most.
 *
 * When the phone rings, we look up the caller's number among her people and say,
 * e.g. "కూతురు ఫోన్ చేస్తున్నారు" (Daughter is calling). An unknown number becomes
 * "ఎవరో ఫోన్ చేస్తున్నారు" (someone is calling).
 *
 * 100% OFFLINE — pure Android telephony + our Announcer (clip-first, TTS fallback).
 * A recorded family-voice clip per person (key "caller_<index>") can replace the
 * spoken name later with zero code change.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        // We only announce when the phone is RINGING (an incoming call).
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return

        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        val contacts = Contacts.load(context)
        val idx = contacts.indexOfFirst { it.number.isNotBlank() && sameNumber(it.number, number) }

        val clipKey: String
        val text: String
        if (idx >= 0) {
            clipKey = "caller_$idx"
            text = "${contacts[idx].name} ఫోన్ చేస్తున్నారు"      // "<name> is calling"
        } else {
            clipKey = "caller_unknown"
            text = "ఎవరో ఫోన్ చేస్తున్నారు"                        // "someone is calling"
        }

        Log.i(TAG, "Incoming call from \"$number\" -> \"$text\"")
        Announcer.get(context).announce(clipKey, text)
    }

    /** Match by the last digits, so +91 / spaces / local formats still match. */
    private fun sameNumber(saved: String, incoming: String): Boolean {
        val a = saved.filter { it.isDigit() }
        val b = incoming.filter { it.isDigit() }
        if (a.length < 4 || b.length < 4) return a == b && a.isNotEmpty()
        return a.takeLast(9) == b.takeLast(9)
    }

    companion object {
        private const val TAG = "Ammamma"
    }
}
