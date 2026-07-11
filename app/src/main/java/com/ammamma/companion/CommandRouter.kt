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
 * mode, then resolves it ON THE PHONE: call a contact/number, open an app, show a
 * video, ask a clarifying question, or just chat.
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

    // Words that, alongside a long digit run, confirm she means "dial this number"
    // rather than reciting some unrelated number (a pin code, a price, an address).
    private val CALL_WORDS = listOf(
        "ఫోన్", "కాల్", "డయల్", "కలపు", "కలపండి", "నంబర్", "call", "phone", "dial"
    )

    /** BLOCKING (network inside) — call OFF the main thread.
     *  [history] is the prior (role, content) turns of this chat session, oldest
     *  first — passed straight through to the AI so a pending clarifying question
     *  ("ask") has her answer land with full context on the next turn. */
    fun resolve(
        context: Context,
        userText: String,
        history: List<Pair<String, String>>,
        extraContext: String?
    ): Action {
        // RAGE-BUG FIX: if she said/typed a number, dial it directly — never let the
        // model or the fuzzy contact-matcher get a chance to reroute it to someone else.
        extractDirectNumber(userText)?.let { number ->
            return Action.Call(number, "$number కి ఫోన్ చేస్తున్నాను")
        }

        // LOCAL-FIRST: time/battery/torch/wifi/volume/alarm/photo/data work
        // instantly with zero internet — try them before ever calling the AI.
        OfflineIntents.match(context, userText)?.let { return it }

        val contacts = Contacts.load(context)
        val contactNames = contacts.map { it.name }.filter { it.isNotBlank() }
        val appNames = KNOWN.keys.toList()

        val r = AiBrain.assistant(context, userText, contactNames, appNames, history, extraContext)
        if (!r.ok) return Action.Say(r.text, r.detail)

        val json = extractJson(r.text) ?: return Action.Say(r.text) // malformed JSON -> treat as plain chat
        return when (json.optString("action")) {
            "call" -> {
                // Model's call{number} is honored too, but local extraction (above) always wins.
                val modelNumber = json.optString("number").filter { it.isDigit() }
                if (modelNumber.length >= 6) Action.Call(modelNumber, "$modelNumber కి ఫోన్ చేస్తున్నాను")
                else resolveCall(context, contacts, json.optString("name"))
            }
            "open" -> resolveOpen(context, json.optString("app"))
            "video" -> resolveVideo(json.optString("query"))
            "ask" -> Action.Say(json.optString("say").ifBlank { r.text })
            "chat" -> Action.Say(json.optString("say").ifBlank { r.text })
            else -> Action.Say(json.optString("say").ifBlank { r.text })
        }
    }

    /** A digit run of 6+ (spaces/dashes allowed) that reads as a phone number she
     *  said or typed — but only when the utterance is actually ABOUT calling: either
     *  a call word is present, or the text is basically just the number itself. */
    private fun extractDirectNumber(text: String): String? {
        val run = Regex("[0-9][0-9 \\-]{4,}[0-9]").find(text)?.value ?: return null
        val digits = run.filter { it.isDigit() }
        if (digits.length < 6) return null
        val hasCallWord = CALL_WORDS.any { text.contains(it, ignoreCase = true) }
        val remainder = text.replace(Regex("[0-9 \\-]"), "").trim()
        return if (hasCallWord || remainder.length <= 8) digits else null
    }

    private fun resolveCall(context: Context, contacts: List<Contact>, name: String): Action {
        if (name.isBlank()) return Action.Say("ఎవరికి ఫోన్ చేయాలో అర్థం కాలేదు")

        // 1. Exact match in her own curated face-grid contacts wins outright.
        contacts.firstOrNull { it.name.equals(name, true) }?.let { return callAction(it.name, it.number) }

        // 2. Loose (substring) match in that same small list — only dial if there's
        //    exactly one candidate; more than one means we must ask, not guess.
        val loose = contacts.filter { it.name.contains(name, true) || name.contains(it.name, true) }
        if (loose.size == 1) return callAction(loose[0].name, loose[0].number)
        if (loose.size > 1) return Action.Say("${name}కి ఫోన్ చేయాలా? ఎవరికో సరిగ్గా చెప్పండి")

        // 3. Fall back to the phone's own contact book. A WEAK/AMBIGUOUS match there
        //    (this is exactly how the old code once dialed the wrong, old contact)
        //    must ask instead of guessing.
        val hit = lookupDeviceNumber(context, name)
            ?: return Action.Say("$name నంబర్ కనబడలేదు, ఇంట్లో వాళ్ళను అడగండి")
        return if (hit.ambiguous) Action.Say("${name}కి ఫోన్ చేయాలా?")
        else callAction(hit.displayName, hit.number)
    }

    private fun callAction(name: String, number: String): Action {
        if (number.isBlank()) return Action.Say("$name నంబర్ లేదు, ఇంట్లో వాళ్ళను అడగండి")
        return Action.Call(number, "${name}కి ఫోన్ చేస్తున్నాను")
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

    /** ACTION_VIEW a YouTube search — the YouTube app opens it if installed,
     *  otherwise Android hands it to the browser automatically. */
    private fun resolveVideo(query: String): Action {
        if (query.isBlank()) return Action.Say("ఏమి చూపించాలో అర్థం కాలేదు")
        val url = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Action.Launch(intent, "యూట్యూబ్‌లో చూపిస్తున్నాను")
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

    private data class DeviceHit(val displayName: String, val number: String, val ambiguous: Boolean)

    /** Look up a phone number by contact display name — stays on the phone. If more
     *  than one distinct person matches, [DeviceHit.ambiguous] is true so the caller
     *  asks instead of guessing (never dial the wrong, similarly-named contact). */
    private fun lookupDeviceNumber(context: Context, name: String): DeviceHit? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cols = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            context.contentResolver.query(uri, cols, sel, arrayOf("%$name%"), null)?.use { c ->
                var firstNumber: String? = null
                var firstName: String? = null
                val distinctNames = HashSet<String>()
                while (c.moveToNext()) {
                    val num = c.getString(0)
                    val disp = c.getString(1)
                    if (firstNumber == null) { firstNumber = num; firstName = disp }
                    distinctNames.add(disp)
                }
                firstNumber?.let { DeviceHit(firstName ?: name, it, ambiguous = distinctNames.size > 1) }
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
