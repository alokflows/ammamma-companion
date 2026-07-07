package com.ammamma.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Announces WHO is calling, out loud in Telugu — the feature Ammamma needs most.
 *
 * Lookup order when the phone rings:
 *   1. The app's own people (photo-dial contacts) — may have a recorded clip.
 *   2. The PHONE's contact book (ContactsContract) — so anyone the family saved
 *      on the phone is announced by name too, even if not added in the app.
 *   3. Neither knows the number -> "ఎవరో ఫోన్ చేస్తున్నారు" (someone is calling).
 *
 * 100% OFFLINE — pure Android telephony + our Announcer (clip-first, TTS fallback).
 * A recorded family-voice clip per person (key "caller_<index>") can replace the
 * spoken name later with zero code change.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                handleRinging(context, number)
            }
            // Answered or ended → stop immediately and forget this ring.
            TelephonyManager.EXTRA_STATE_OFFHOOK,
            TelephonyManager.EXTRA_STATE_IDLE -> {
                announcedNumber = null
                announcedUnknown = false
                CompanionService.stopCaller(context)
            }
        }
    }

    private fun handleRinging(context: Context, number: String) {
        // Android often fires RINGING twice: first WITHOUT the number, then WITH it.
        if (number.isBlank()) {
            // No number in this broadcast. If we already said something for this
            // ring, stay quiet; otherwise at least tell her the phone is ringing.
            if (announcedUnknown || announcedNumber != null) return
            announcedUnknown = true
            Log.i(TAG, "Ringing (no number in broadcast) -> announce unknown")
            CompanionService.startCaller(context, "caller_unknown", "ఎవరో ఫోన్ చేస్తున్నారు")
            return
        }
        if (number == announcedNumber) return   // duplicate broadcast for the same ring

        val (clipKey, text) = describeCaller(context, number)
        announcedNumber = number
        Log.i(TAG, "Ringing from \"$number\" -> repeat \"$text\"")
        // Repeat the name every few seconds WHILE ringing (service drives it).
        // If we first said "someone is calling", this upgrades it to the real name.
        CompanionService.startCaller(context, clipKey, text)
    }

    /** Who is this number? App contacts first (clips!), then the phone's book. */
    private fun describeCaller(context: Context, number: String): Pair<String, String> {
        val contacts = Contacts.load(context)
        val idx = contacts.indexOfFirst { it.number.isNotBlank() && sameNumber(it.number, number) }
        if (idx >= 0) {
            return "caller_$idx" to "${contacts[idx].name} ఫోన్ చేస్తున్నారు"   // "<name> is calling"
        }

        val deviceName = lookupDeviceContact(context, number)
        if (deviceName != null) {
            // Key "caller_device" (not "caller_unknown") so a generic recorded
            // "someone is calling" clip never shadows the actual spoken name.
            return "caller_device" to "$deviceName ఫోన్ చేస్తున్నారు"
        }

        return "caller_unknown" to "ఎవరో ఫోన్ చేస్తున్నారు"                     // "someone is calling"
    }

    /**
     * One fast indexed query against the phone's contact book. PhoneLookup is
     * Android's purpose-built "who owns this number" table, so formats like
     * +91 98… vs 098… match without any string games on our side.
     * Never crashes: permission denied / any provider error just returns null.
     */
    private fun lookupDeviceContact(context: Context, number: String): String? {
        val granted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "READ_CONTACTS not granted; skipping phone-book lookup")
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            context.contentResolver
                .query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        } catch (e: Exception) {
            Log.w(TAG, "Phone-book lookup failed", e)
            null
        }
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

        // A fresh receiver object is created for every broadcast, so per-ring state
        // must live here (process-wide). Reset on OFFHOOK/IDLE.
        @Volatile private var announcedNumber: String? = null
        @Volatile private var announcedUnknown = false
    }
}
