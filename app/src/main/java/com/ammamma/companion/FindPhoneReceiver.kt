package com.ammamma.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Find-my-phone. When a family SMS containing the secret code word arrives, make
 * the phone ring loudly (even on silent) so she — or whoever is nearby — can find it.
 *
 * Safety filters (the brief's requirement): the message must contain the exact code
 * word AND, if family numbers are configured, come from one of them. A random text
 * can't set the phone screaming.
 *
 * Declared in the manifest (SMS_RECEIVED is exempt from Android 8's implicit-broadcast
 * ban), so it works even when the app isn't open.
 */
class FindPhoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages[0].originatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }

        val code = Settings.codeWord(context)
        if (!body.contains(code, ignoreCase = true)) return

        val allowed = Settings.familyNumbers(context)
        if (allowed.isNotEmpty() && !senderAllowed(sender, allowed)) {
            Log.w(TAG, "Code word from a non-family number; ignoring")
            return
        }

        Log.i(TAG, "Find-my-phone triggered by SMS")
        FindPhoneActivity.show(context)                 // ring loudly (always)
        // Texting the GPS location back is a separate, family-toggleable step. The
        // ring/alarm above is never affected by this switch.
        if (Settings.locationReplySmsEnabled(context)) {
            LocationReplyService.start(context, sender)     // text back where it is
        }
    }

    // Numbers can differ by country code (+91...) vs local, so match on the tail.
    private fun senderAllowed(sender: String, allowed: List<String>): Boolean {
        val s = sender.filter { it.isDigit() }
        return allowed.any { a ->
            val n = a.filter { ch -> ch.isDigit() }
            n.isNotEmpty() && (s.endsWith(n) || n.endsWith(s))
        }
    }

    companion object {
        private const val TAG = "Ammamma"
    }
}
