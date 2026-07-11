package com.ammamma.companion

import android.content.Context

/**
 * THE single source of truth for every recordable announcement. Add one line
 * here and Recorder Studio shows it automatically; Announcer plays
 * clips/<key> first and falls back to TTS. Never define a clip key anywhere
 * else.
 */
object ClipCatalog {

    /** One recordable line: the Announcer event key, a Telugu label the
     *  family understands, and the words they should actually say into the
     *  mic. Keys are permanent — an already-installed clip on a phone is
     *  matched by key, so renaming one here orphans that recording. */
    data class ClipSpec(val key: String, val label: String, val hint: String)

    /** A section header + its small helper line, kept together with the
     *  specs it introduces so a row can never end up under the wrong
     *  heading. */
    data class Section(val title: String, val helper: String, val specs: List<ClipSpec>)

    /** Day-part words matching how the hours are spoken at home. */
    private fun periodTelugu(h: Int): String = when (h) {
        in 4..5 -> "తెల్లవారుజాము"   // early dawn
        in 6..11 -> "ఉదయం"          // morning
        in 12..15 -> "మధ్యాహ్నం"     // afternoon
        in 16..18 -> "సాయంత్రం"      // evening
        else -> "రాత్రి"             // night (19–23 and 0–3)
    }

    /** hour_0..hour_23 — generated, not hand-typed, since the label for each
     *  hour follows a fixed pattern. Keys must stay exactly "hour_<h>". */
    private val hourlySpecs: List<ClipSpec> = (0..23).map { h ->
        val h12 = if (h % 12 == 0) 12 else h % 12
        val gantalu = if (h12 == 1) "1 గంట" else "$h12 గంటలు"
        ClipSpec("hour_$h", "సమయం $gantalu (${periodTelugu(h)})", "ఇప్పుడు ${periodTelugu(h)} $gantalu")
    }

    /**
     * The full, ordered catalog. [context] is used ONLY to read the phone's
     * saved contacts for the per-person caller rows — those keys
     * (caller_<contact id>) are the one part of the catalog that can't be
     * known ahead of time, so they're generated here instead of hand-typed,
     * keyed by the STABLE contact id (never list position) so a clip keeps
     * working after the family reorders or edits people.
     */
    fun sections(context: Context): List<Section> = listOf(
        // 1. Greetings — hints copied verbatim from the current TTS lines.
        Section(
            "పలకరింపులు · Greetings",
            "The friendly lines she hears when opening screens.",
            listOf(
                ClipSpec("home_greeting", "ఇంటి పలకరింపు · Home", "నమస్తే అమ్మమ్మ"),
                ClipSpec("talk_greeting", "మాట్లాడు తెర · Talk screen", "చెప్పమ్మా, ఏం కావాలి"),
                ClipSpec("goodmorning", "శుభోదయం · Good morning", "శుభోదయం అమ్మమ్మ")
            )
        ),
        // 2. Battery — the TTS versions speak the exact %, a clip can't, so
        //    the hints are the same sentences without the number.
        Section(
            "బ్యాటరీ & ఛార్జింగ్ · Battery",
            "Charger in/out and charge warnings.",
            listOf(
                ClipSpec("charger_connected", "ఛార్జర్ పెట్టినప్పుడు · Plugged in", "ఛార్జర్ పెట్టారు"),
                ClipSpec("charger_removed", "ఛార్జర్ తీసినప్పుడు · Unplugged", "ఛార్జర్ తీసేశారు"),
                ClipSpec("battery_full", "ఛార్జ్ నిండినప్పుడు · Full", "ఛార్జింగ్ నిండింది, ఛార్జర్ తీసేయండి"),
                ClipSpec("battery_low", "ఛార్జ్ తక్కువప్పుడు · Low", "ఛార్జ్ తక్కువగా ఉంది, దయచేసి ఛార్జ్ చేయండి")
            )
        ),
        // 3. Find-phone.
        Section(
            "ఫోన్ వెతుకు · Find phone",
            "Spoken while the phone rings out loud to be found.",
            listOf(ClipSpec("found_phone", "ఫోన్ దొరికినప్పుడు · Found it", "ఫోన్ ఇక్కడ ఉంది అమ్మమ్మ!"))
        ),
        // 4. Talking alarm.
        Section(
            "అలారం · Alarm",
            "The talking-alarm line at the set time.",
            listOf(ClipSpec("alarm", "అలారం మాట · Alarm line", "అమ్మమ్మ, సమయం అయింది"))
        ),
        // 5. Hourly chime — one clip per hour of the day, labeled in the
        //    12-hour Telugu form she actually thinks in.
        Section(
            "గంట గంటకి · Hourly chime",
            "One line per hour; record only the hours you want a real voice for.",
            hourlySpecs
        ),
        // 6. Callers — keyed by the STABLE contact id (never the list
        //    position), so a clip keeps working after the family reorders
        //    or edits people.
        Section(
            "ఎవరు ఫోన్ చేస్తున్నారు · Callers",
            "Announced while the phone rings — one per person.",
            Contacts.load(context).map { c ->
                ClipSpec("caller_${c.id}", "${c.name} · ${c.english}", "${c.name} ఫోన్ చేస్తున్నారు")
            } + listOf(
                ClipSpec(
                    "caller_device", "ఫోన్‌లో సేవ్ అయిన వ్యక్తి · Other saved contact",
                    "తెలిసినవారు ఫోన్ చేస్తున్నారు"
                ),
                ClipSpec("caller_unknown", "తెలియని నంబర్ · Unknown number", "ఎవరో ఫోన్ చేస్తున్నారు")
            )
        )
    )
}
