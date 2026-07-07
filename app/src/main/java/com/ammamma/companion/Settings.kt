package com.ammamma.companion

import android.content.Context

/**
 * All the family-editable settings, kept in one small SharedPreferences file.
 *
 * This is the "no rebuild needed" layer: the Gemini API key, the find-my-phone
 * code word, and the allowed family numbers all live here, changed from the
 * Settings screen — never in code.
 */
object Settings {
    private const val PREFS = "ammamma_settings"
    private const val KEY_CODE = "code_word"
    private const val KEY_NUMBERS = "family_numbers"
    private const val KEY_AI = "ai_key"   // OpenRouter API key (sk-or-...)
    private const val KEY_BATT_MIN = "battery_reminder_min"
    private const val KEY_BATT_LOW = "battery_low_pct"          // warn below this %
    private const val KEY_BATT_CRIT = "battery_critical_pct"    // urgent warn below this %
    private const val KEY_BATT_CHARGED = "battery_charged_pct"  // while charging, "enough" at this %
    private const val KEY_LOC_REPLY = "location_reply_sms"      // text GPS back on the code word
    private const val KEY_CALLER_SECS = "caller_repeat_seconds" // gap between spoken caller names
    private const val KEY_CALLER_TIMES = "caller_max_repeats"   // how many times the name is spoken

    private const val DEFAULT_BATT_MIN = 5
    private const val DEFAULT_BATT_LOW = 20
    private const val DEFAULT_BATT_CRIT = 10
    private const val DEFAULT_BATT_CHARGED = 100
    private const val DEFAULT_CALLER_SECS = 4
    private const val DEFAULT_CALLER_TIMES = 12

    private const val DEFAULT_CODE = "FINDME"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The secret word a family SMS must contain to make the phone ring itself. */
    fun codeWord(c: Context): String =
        prefs(c).getString(KEY_CODE, DEFAULT_CODE)?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_CODE

    /** Allowed sender numbers. Empty = accept the code word from anyone (less safe). */
    fun familyNumbers(c: Context): List<String> =
        prefs(c).getString(KEY_NUMBERS, "").orEmpty()
            .split(Regex("[,\\n]"))
            .map { it.filter { ch -> ch.isDigit() || ch == '+' } }
            .filter { it.length >= 4 }

    fun aiKey(c: Context): String = prefs(c).getString(KEY_AI, "").orEmpty().trim()

    /** How often (minutes) the low-battery reminder repeats until charging. */
    fun batteryReminderMinutes(c: Context): Int =
        prefs(c).getString(KEY_BATT_MIN, null)?.toIntOrNull()?.coerceIn(1, 120) ?: DEFAULT_BATT_MIN

    fun batteryReminderRaw(c: Context) = prefs(c).getString(KEY_BATT_MIN, DEFAULT_BATT_MIN.toString()).orEmpty()

    /** Warn ("please charge") when the battery drops to this % or below. */
    fun batteryLowPercent(c: Context): Int =
        prefs(c).getString(KEY_BATT_LOW, null)?.toIntOrNull()?.coerceIn(5, 50) ?: DEFAULT_BATT_LOW

    /** The urgent second warning. Clamped so it can never sit above the low threshold. */
    fun batteryCriticalPercent(c: Context): Int {
        val v = prefs(c).getString(KEY_BATT_CRIT, null)?.toIntOrNull() ?: DEFAULT_BATT_CRIT
        return v.coerceIn(1, batteryLowPercent(c))
    }

    /** While charging, announce "enough — unplug" at this % (100 = only when truly full). */
    fun batteryChargedPercent(c: Context): Int =
        prefs(c).getString(KEY_BATT_CHARGED, null)?.toIntOrNull()?.coerceIn(50, 100)
            ?: DEFAULT_BATT_CHARGED

    fun batteryLowRaw(c: Context) = prefs(c).getString(KEY_BATT_LOW, DEFAULT_BATT_LOW.toString()).orEmpty()
    fun batteryCriticalRaw(c: Context) = prefs(c).getString(KEY_BATT_CRIT, DEFAULT_BATT_CRIT.toString()).orEmpty()
    fun batteryChargedRaw(c: Context) = prefs(c).getString(KEY_BATT_CHARGED, DEFAULT_BATT_CHARGED.toString()).orEmpty()

    /** Battery alert levels — saved separately so the classic save() keeps its shape. */
    fun saveBatteryLevels(c: Context, lowPct: String, criticalPct: String, chargedPct: String) {
        prefs(c).edit()
            .putString(KEY_BATT_LOW, lowPct.trim())
            .putString(KEY_BATT_CRIT, criticalPct.trim())
            .putString(KEY_BATT_CHARGED, chargedPct.trim())
            .apply()
    }

    fun save(c: Context, codeWord: String, familyNumbersRaw: String, aiKey: String, batteryMinutes: String) {
        prefs(c).edit()
            .putString(KEY_CODE, codeWord.trim())
            .putString(KEY_NUMBERS, familyNumbersRaw.trim())
            .putString(KEY_AI, aiKey.trim())
            .putString(KEY_BATT_MIN, batteryMinutes.trim())
            .apply()
    }

    // Raw strings for pre-filling the Settings fields.
    fun codeWordRaw(c: Context) = prefs(c).getString(KEY_CODE, DEFAULT_CODE).orEmpty()
    fun familyNumbersRaw(c: Context) = prefs(c).getString(KEY_NUMBERS, "").orEmpty()

    // --- Location SMS reply ---------------------------------------------------
    // On the code-word SMS the phone also texts its GPS back. Default ON because
    // the family relies on it to find her; the Settings switch turns it off.
    fun locationReplySmsEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_LOC_REPLY, true)

    /** Toggle applies immediately (not on Save) — one clear tap = one clear result. */
    fun setLocationReplySms(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_LOC_REPLY, enabled).apply()
    }

    // --- Caller announcement timing ------------------------------------------
    // When the phone rings we speak the caller's name every N seconds, up to a
    // maximum number of times, so she hears WHO is calling over the ringtone.
    /** Gap between repeats, in seconds. Clamped to a sane 1..30. */
    fun callerRepeatSeconds(c: Context): Int =
        prefs(c).getString(KEY_CALLER_SECS, null)?.toIntOrNull()?.coerceIn(1, 30)
            ?: DEFAULT_CALLER_SECS

    /** How many times the name is spoken (1 = once). Clamped to 1..30. */
    fun callerMaxRepeats(c: Context): Int =
        prefs(c).getString(KEY_CALLER_TIMES, null)?.toIntOrNull()?.coerceIn(1, 30)
            ?: DEFAULT_CALLER_TIMES

    fun callerRepeatSecondsRaw(c: Context) =
        prefs(c).getString(KEY_CALLER_SECS, DEFAULT_CALLER_SECS.toString()).orEmpty()

    fun callerMaxRepeatsRaw(c: Context) =
        prefs(c).getString(KEY_CALLER_TIMES, DEFAULT_CALLER_TIMES.toString()).orEmpty()

    /** Saved with the classic Save button alongside the other fields. */
    fun saveCallerSettings(c: Context, repeatSecondsRaw: String, maxRepeatsRaw: String) {
        prefs(c).edit()
            .putString(KEY_CALLER_SECS, repeatSecondsRaw.trim())
            .putString(KEY_CALLER_TIMES, maxRepeatsRaw.trim())
            .apply()
    }
}
