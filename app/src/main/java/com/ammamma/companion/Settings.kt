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

    private const val DEFAULT_BATT_MIN = 5

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
}
