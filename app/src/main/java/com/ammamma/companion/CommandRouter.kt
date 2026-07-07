package com.ammamma.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import android.util.Log
import org.json.JSONObject

/**
 * Turns what Ammamma said into an ACTION. Runs the AI in "assistant" (JSON-intent)
 * mode, then resolves it ON THE PHONE: call a contact, open an app, or just chat.
 *
 * PRIVACY: only contact NAMES ever go to the AI. The number is looked up locally
 * and never leaves the phone.
 */
object CommandRouter {

    private const val TAG = "Ammamma"

    sealed class Action {
        data class Say(val spoken: String, val detail: String = "") : Action()
        data class Call(val number: String, val spoken: String) : Action()
        data class Launch(val intent: Intent, val spoken: String) : Action()
    }

    // Apps she might name -> package + web fallback + Telugu name.
    private data class KnownApp(val pkg: String, val web: String?, val telugu: String)
    private val KNOWN = mapOf(
        "youtube" to KnownApp("com.google.android.youtube", "https://m.youtube.com", "యూట్యూబ్"),
        "whatsapp" to KnownApp("com.whatsapp", "https://web.whatsapp.com", "వాట్సాప్"),
        "maps" to KnownApp("com.google.android.apps.maps", "https://maps.google.com", "మ్యాప్స్"),
        "chrome" to KnownApp("com.android.chrome", "https://www.google.com", "బ్రౌజర్"),
        "camera" to KnownApp("", null, "కెమెరా"),
        "phone" to KnownApp("", null, "ఫోన్"),
        "settings" to KnownApp("", null, "సెట్టింగ్స్")
    )

    /** BLOCKING (network inside) — call OFF the main thread. */
    fun resolve(context: Context, userText: String, extraContext: String?): Action {
        val contacts = Contacts.load(context)
        val contactNames = contacts.map { it.name }.filter { it.isNotBlank() }
        val appNames = KNOWN.keys.toList()

        val r = AiBrain.assistant(context, userText, contactNames, appNames, extraContext)
        if (!r.ok) return Action.Say(r.text, r.detail)

        val json = extractJson(r.text) ?: return Action.Say(r.text) // model just chatted plainly
        return when (json.optString("action")) {
            "call" -> resolveCall(context, contacts, json.optString("name"))
            "open" -> resolveOpen(context, json.optString("app"))
            else -> Action.Say(json.optString("say").ifBlank { r.text })
        }
    }

    private fun resolveCall(context: Context, contacts: List<Contact>, name: String): Action {
        if (name.isBlank()) return Action.Say("ఎవరికి ఫోన్ చేయాలో అర్థం కాలేదు")
        val hit = contacts.firstOrNull { it.name.equals(name, true) }
            ?: contacts.firstOrNull { it.name.contains(name, true) || name.contains(it.name, true) }
        val number = hit?.number?.takeIf { it.isNotBlank() } ?: lookupDeviceNumber(context, name)
        if (number.isNullOrBlank()) return Action.Say("$name నంబర్ లేదు, ఇంట్లో వాళ్ళను అడగండి")
        return Action.Call(number, "${hit?.name ?: name}కి ఫోన్ చేస్తున్నాను")
    }

    private fun resolveOpen(context: Context, app: String): Action {
        if (app.isBlank()) return Action.Say("ఏ యాప్ తెరవాలో అర్థం కాలేదు")
        val pm = context.packageManager
        val key = app.lowercase().trim()
        val known = KNOWN[key]

        // Built-in intents for camera / phone / settings.
        when (key) {
            "camera" -> return Action.Launch(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "కెమెరా తెరుస్తున్నాను")
            "phone" -> return Action.Launch(
                Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "ఫోన్ తెరుస్తున్నాను")
            "settings" -> return Action.Launch(
                Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "సెట్టింగ్స్ తెరుస్తున్నాను")
        }

        // Known package installed?
        known?.pkg?.takeIf { it.isNotBlank() }?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let {
                return Action.Launch(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "${known.telugu} తెరుస్తున్నాను")
            }
        }
        // Match any installed app whose label contains the name.
        findInstalledByLabel(context, key)?.let {
            return Action.Launch(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "$app తెరుస్తున్నాను")
        }
        // Not installed but has a website -> open browser and TELL her.
        known?.web?.let {
            return Action.Launch(
                Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "${known.telugu} యాప్ లేదు, బ్రౌజర్‌లో తెరుస్తున్నాను")
        }
        return Action.Say("$app ఈ ఫోన్‌లో లేదు")
    }

    private fun findInstalledByLabel(context: Context, key: String): Intent? {
        val pm = context.packageManager
        return try {
            pm.getInstalledApplications(0).firstOrNull { info ->
                val label = pm.getApplicationLabel(info).toString().lowercase()
                label.contains(key) && pm.getLaunchIntentForPackage(info.packageName) != null
            }?.let { pm.getLaunchIntentForPackage(it.packageName) }
        } catch (e: Exception) { null }
    }

    /** Look up a phone number by contact display name — stays on the phone. */
    private fun lookupDeviceNumber(context: Context, name: String): String? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            context.contentResolver.query(uri, cols, sel, arrayOf("%$name%"), null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "contact lookup failed", e); null
        }
    }

    /** Pull the first {...} block out of the model's reply and parse it. */
    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (e: Exception) { null }
    }
}
