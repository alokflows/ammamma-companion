package com.ammamma.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One AI account: an API key and, optionally, a specific model to use. The provider
 * (and its base URL) is worked out from the key prefix — the family never types a
 * URL. A blank [model] means "Auto" — the app picks the best model for that provider.
 */
data class AiAccount(val key: String, val model: String = "") {
    val base: String get() = Settings.baseForKey(key)
    val providerLabel: String get() = Settings.providerLabel(key)
}

/**
 * All the family-editable settings, kept in one small SharedPreferences file.
 *
 * This is the "no rebuild needed" layer: the AI API keys, the find-my-phone
 * code word, and the allowed family numbers all live here, changed from the
 * Settings screen — never in code.
 */
object Settings {
    private const val PREFS = "ammamma_settings"
    private const val KEY_CODE = "code_word"
    private const val KEY_NUMBERS = "family_numbers"
    private const val KEY_AI = "ai_key"          // legacy single API key (migrated to accounts)
    private const val KEY_AI_BASE = "ai_base_url" // legacy base URL (migrated)
    private const val KEY_AI_MODEL = "ai_model"   // legacy model id (migrated)
    private const val KEY_AI_ACCOUNTS = "ai_accounts" // JSON list of {key, model}; the real source now
    private const val KEY_TRAVEL = "travel_mode"  // SMS location ping on charger events
    private const val KEY_BATT_MIN = "battery_reminder_min"
    private const val KEY_BATT_LOW = "battery_low_pct"          // warn below this %
    private const val KEY_BATT_CRIT = "battery_critical_pct"    // urgent warn below this %
    private const val KEY_BATT_CHARGED = "battery_charged_pct"  // while charging, "enough" at this %
    private const val KEY_SMS_REPLY = "sms_reply_enabled"       // find-phone texts location back
    private const val KEY_CALLER_SECS = "caller_repeat_secs"    // gap between "X is calling" repeats
    private const val KEY_CALLER_REPEATS = "caller_max_repeats" // how many times the name is said

    private const val DEFAULT_BATT_MIN = 5
    private const val DEFAULT_BATT_LOW = 20
    private const val DEFAULT_BATT_CRIT = 10
    private const val DEFAULT_BATT_CHARGED = 100
    private const val DEFAULT_CALLER_SECS = 4
    private const val DEFAULT_CALLER_REPEATS = 12

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

    const val DEFAULT_AI_BASE = "https://openrouter.ai/api/v1"

    /** The OpenAI-compatible base URL for a key, worked out from its prefix so the
     *  family never types a URL. Returned without a trailing slash so callers can
     *  append /chat/completions or /models. */
    fun baseForKey(key: String): String {
        val k = key.trim()
        return when {
            k.startsWith("gsk_") -> "https://api.groq.com/openai/v1"       // Groq
            k.startsWith("sk-or-") -> "https://openrouter.ai/api/v1"       // OpenRouter
            k.startsWith("sk-") -> "https://api.openai.com/v1"             // OpenAI
            else -> DEFAULT_AI_BASE                                        // safe default
        }
    }

    /** Friendly provider name for a key, shown next to the key row. */
    fun providerLabel(key: String): String {
        val k = key.trim()
        return when {
            k.isEmpty() -> ""
            k.startsWith("gsk_") -> "Groq"
            k.startsWith("sk-or-") -> "OpenRouter"
            k.startsWith("sk-") -> "OpenAI"
            else -> "Custom"
        }
    }

    /**
     * The AI accounts, in try-order. AiBrain uses the first that answers and falls
     * through to the next on a rate-limit/failure — so a second free key is a cheap
     * safety net. Blank-key entries are dropped. Migrates any older single-key setup.
     */
    fun aiAccounts(c: Context): List<AiAccount> {
        val raw = prefs(c).getString(KEY_AI_ACCOUNTS, null)
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val key = o.optString("key").trim()
                    if (key.isEmpty()) null else AiAccount(key, o.optString("model").trim())
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        // Migrate a pre-accounts install: fold the old single key/model into one account.
        val legacyKey = prefs(c).getString(KEY_AI, "").orEmpty().trim()
        val legacyModel = prefs(c).getString(KEY_AI_MODEL, "").orEmpty().trim()
        return if (legacyKey.isEmpty()) emptyList() else listOf(AiAccount(legacyKey, legacyModel))
    }

    fun saveAiAccounts(c: Context, accounts: List<AiAccount>) {
        val arr = JSONArray()
        accounts.filter { it.key.isNotBlank() }.forEach {
            arr.put(JSONObject().put("key", it.key.trim()).put("model", it.model.trim()))
        }
        // Mirror the first key into the legacy field so any old code path still finds one.
        prefs(c).edit()
            .putString(KEY_AI_ACCOUNTS, arr.toString())
            .putString(KEY_AI, accounts.firstOrNull { it.key.isNotBlank() }?.key?.trim().orEmpty())
            .apply()
    }

    /** First saved key — used only as a quick "is AI set up at all?" gate. */
    fun aiKey(c: Context): String = aiAccounts(c).firstOrNull()?.key.orEmpty()

    /** Travel mode: on every charger plug/unplug, silently SMS the phone's GPS
     *  location to all family numbers. Off by default. */
    fun travelModeEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_TRAVEL, false)

    fun setTravelMode(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_TRAVEL, enabled).apply()
    }

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

    /** Whether find-my-phone also texts the GPS location back (family can turn it off). */
    fun locationReplySmsEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_SMS_REPLY, true)

    fun setLocationReplySms(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_SMS_REPLY, enabled).apply()
    }

    /** Seconds between repeats of "<name> is calling" while the phone rings. */
    fun callerRepeatSeconds(c: Context): Int =
        prefs(c).getString(KEY_CALLER_SECS, null)?.toIntOrNull()?.coerceIn(2, 15) ?: DEFAULT_CALLER_SECS

    /** How many times the name is spoken per ring (1 = say it once). */
    fun callerMaxRepeats(c: Context): Int =
        prefs(c).getString(KEY_CALLER_REPEATS, null)?.toIntOrNull()?.coerceIn(1, 30)
            ?: DEFAULT_CALLER_REPEATS

    fun callerRepeatSecondsRaw(c: Context) =
        prefs(c).getString(KEY_CALLER_SECS, DEFAULT_CALLER_SECS.toString()).orEmpty()
    fun callerMaxRepeatsRaw(c: Context) =
        prefs(c).getString(KEY_CALLER_REPEATS, DEFAULT_CALLER_REPEATS.toString()).orEmpty()

    fun saveCallerSettings(c: Context, repeatSecs: String, maxRepeats: String) {
        prefs(c).edit()
            .putString(KEY_CALLER_SECS, repeatSecs.trim())
            .putString(KEY_CALLER_REPEATS, maxRepeats.trim())
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

    // ─────────────────────────────────────────────────────────────────────────
    // SOUNDS (added for the real-device bug batch). Volume + mute + per-category
    // gates. Appended at the end to keep the classic save() and the rest untouched.
    // ─────────────────────────────────────────────────────────────────────────
    private const val KEY_SPEECH_VOLUME = "speech_volume_pct"   // 10..100
    private const val KEY_VOICE_MUTED = "voice_muted"           // quick-mute
    private const val KEY_GREETING_ON = "greeting_enabled"      // home hello
    private const val KEY_UI_SOUNDS_ON = "ui_sounds_enabled"    // gear-tap etc. spoken hints
    private const val KEY_CHARGE_SOUNDS_ON = "charge_sounds_enabled" // charging/battery lines

    /** Spoken-line loudness as a % of the media stream's max (10–100). Ordinary
     *  (non-important) lines use this instead of slamming to full volume. */
    fun speechVolumePercent(c: Context): Int =
        prefs(c).getInt(KEY_SPEECH_VOLUME, 100).coerceIn(10, 100)

    fun setSpeechVolumePercent(c: Context, pct: Int) {
        prefs(c).edit().putInt(KEY_SPEECH_VOLUME, pct.coerceIn(10, 100)).apply()
    }

    /** Quick-mute: when on, ordinary lines are skipped entirely. IMPORTANT lines
     *  (find-my-phone, urgent low battery) still speak — safety is never muted. */
    fun voiceMuted(c: Context): Boolean = prefs(c).getBoolean(KEY_VOICE_MUTED, false)

    fun setVoiceMuted(c: Context, muted: Boolean) {
        prefs(c).edit().putBoolean(KEY_VOICE_MUTED, muted).apply()
    }

    /** Say "నమస్తే అమ్మమ్మ" when she opens the app. */
    fun greetingEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_GREETING_ON, true)

    fun setGreetingEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_GREETING_ON, on).apply()
    }

    /** Small spoken hints for UI taps (e.g. the gear "this is for the family" line). */
    fun uiSoundsEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_UI_SOUNDS_ON, true)

    fun setUiSoundsEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_UI_SOUNDS_ON, on).apply()
    }

    /** The charging-screen and non-urgent battery spoken lines. (Urgent low-battery
     *  is important and always speaks, regardless of this toggle or mute.) */
    fun chargingAnnouncementsEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_CHARGE_SOUNDS_ON, true)

    fun setChargingAnnouncementsEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_CHARGE_SOUNDS_ON, on).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRAVEL MODE recipients + channels (separate from the family/find-phone list).
    // ─────────────────────────────────────────────────────────────────────────
    private const val KEY_TRAVEL_NUMBERS = "travel_numbers"
    private const val KEY_TRAVEL_SMS = "travel_sms_enabled"     // default on
    private const val KEY_TRAVEL_EMAIL = "travel_email_enabled" // default off (not built yet)

    /** Travel-mode recipients. Kept DISTINCT from familyNumbers so the people who get
     *  location breadcrumbs while she travels can differ from the find-phone senders. */
    fun travelNumbers(c: Context): List<String> =
        prefs(c).getString(KEY_TRAVEL_NUMBERS, "").orEmpty()
            .split(Regex("[,\\n]"))
            .map { it.filter { ch -> ch.isDigit() || ch == '+' } }
            .filter { it.length >= 4 }

    fun travelNumbersRaw(c: Context) = prefs(c).getString(KEY_TRAVEL_NUMBERS, "").orEmpty()

    fun saveTravelNumbers(c: Context, raw: String) {
        prefs(c).edit().putString(KEY_TRAVEL_NUMBERS, raw.trim()).apply()
    }

    /** Travel mode sends its breadcrumb by SMS. On by default. */
    fun travelSmsEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_TRAVEL_SMS, true)

    fun setTravelSmsEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_TRAVEL_SMS, on).apply()
    }

    /** Email channel — NOT built yet (see TRAVEL_EMAIL.md). Off by default; the UI
     *  shows it as "coming soon" so the family knows it's a planned relay, not live. */
    fun travelEmailEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_TRAVEL_EMAIL, false)

    fun setTravelEmailEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_TRAVEL_EMAIL, on).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ALERT REPEAT window. An alert card (AlertActivity) keeps re-speaking its
    // line for this many seconds after it appears — she may be in another room
    // when the first line plays. 0 = speak once only, no repeats.
    // ─────────────────────────────────────────────────────────────────────────
    private const val KEY_ALERT_REPEAT = "alert_repeat_seconds"

    /** Seconds an alert keeps repeating its spoken line (0..120, default 30). */
    fun alertRepeatSeconds(c: Context): Int =
        prefs(c).getInt(KEY_ALERT_REPEAT, 30).coerceIn(0, 120)

    fun setAlertRepeatSeconds(c: Context, secs: Int) {
        prefs(c).edit().putInt(KEY_ALERT_REPEAT, secs.coerceIn(0, 120)).apply()
    }

    /** Talk companion: auto-delete chat sessions untouched for 30+ days. On by default. */
    fun chatAutoDelete(c: Context): Boolean = prefs(c).getBoolean("chat_auto_delete", true)

    fun setChatAutoDelete(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("chat_auto_delete", on).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // v1.0: home-screen edit lock, day clock (hourly chime / morning heartbeat /
    // talking alarm) and the grandpa-phone finder role.
    // ─────────────────────────────────────────────────────────────────────────
    private const val KEY_EDIT_LOCKED = "edit_locked"
    private const val KEY_CHIMES_ON = "chimes_enabled"
    private const val KEY_QUIET_START = "quiet_start_hour"   // chimes pause from this hour…
    private const val KEY_QUIET_END = "quiet_end_hour"       // …and resume at this hour
    private const val KEY_HEARTBEAT_ON = "heartbeat_enabled"
    private const val KEY_HEARTBEAT_HOUR = "heartbeat_hour"
    private const val KEY_HEARTBEAT_MIN = "heartbeat_min"
    private const val KEY_ALARM_ON = "alarm_enabled"
    private const val KEY_ALARM_HOUR = "alarm_hour"
    private const val KEY_ALARM_MIN = "alarm_min"
    private const val KEY_FINDER_ROLE = "finder_role"        // "" = Ammamma's phone, "finder" = grandpa's
    private const val KEY_HER_NUMBER = "her_number"          // the number a finder phone texts

    /** Home-screen edit lock. ON by default: long-press explains how to unlock
     *  instead of opening the edit dialog, and the "+" add tile is hidden. */
    fun editLocked(c: Context): Boolean = prefs(c).getBoolean(KEY_EDIT_LOCKED, true)

    fun setEditLocked(c: Context, locked: Boolean) {
        prefs(c).edit().putBoolean(KEY_EDIT_LOCKED, locked).apply()
    }

    /** Hourly Telugu chime ("సమయం N గంటలు"). On by default; silent in quiet hours. */
    fun chimesEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_CHIMES_ON, true)

    fun setChimesEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_CHIMES_ON, on).apply()
    }

    /** Quiet hours: chimes sleep from [quietStartHour] (inclusive) to [quietEndHour]
     *  (exclusive), wrapping midnight. Defaults 21:00–07:00. */
    fun quietStartHour(c: Context): Int = prefs(c).getInt(KEY_QUIET_START, 21).coerceIn(0, 23)

    fun quietEndHour(c: Context): Int = prefs(c).getInt(KEY_QUIET_END, 7).coerceIn(0, 23)

    fun setQuietHours(c: Context, startHour: Int, endHour: Int) {
        prefs(c).edit()
            .putInt(KEY_QUIET_START, startHour.coerceIn(0, 23))
            .putInt(KEY_QUIET_END, endHour.coerceIn(0, 23))
            .apply()
    }

    /** Morning heartbeat ("good morning" at a fixed time). OFF by default until a
     *  goodmorning clip is recorded — TTS alone would be a cold way to wake up. */
    fun heartbeatEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_HEARTBEAT_ON, false)

    fun setHeartbeatEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_HEARTBEAT_ON, on).apply()
    }

    fun heartbeatHour(c: Context): Int = prefs(c).getInt(KEY_HEARTBEAT_HOUR, 7).coerceIn(0, 23)

    fun heartbeatMinute(c: Context): Int = prefs(c).getInt(KEY_HEARTBEAT_MIN, 0).coerceIn(0, 59)

    fun setHeartbeatTime(c: Context, hour: Int, minute: Int) {
        prefs(c).edit()
            .putInt(KEY_HEARTBEAT_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_HEARTBEAT_MIN, minute.coerceIn(0, 59))
            .apply()
    }

    /** Talking alarm (full-screen card + spoken line at a fixed time). Off by default. */
    fun alarmEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ALARM_ON, false)

    fun setAlarmEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ALARM_ON, on).apply()
    }

    fun alarmHour(c: Context): Int = prefs(c).getInt(KEY_ALARM_HOUR, 6).coerceIn(0, 23)

    fun alarmMinute(c: Context): Int = prefs(c).getInt(KEY_ALARM_MIN, 0).coerceIn(0, 59)

    fun setAlarmTime(c: Context, hour: Int, minute: Int) {
        prefs(c).edit()
            .putInt(KEY_ALARM_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_ALARM_MIN, minute.coerceIn(0, 59))
            .apply()
    }

    /** "" on Ammamma's phone (normal app); "finder" on grandpa's phone, which
     *  turns the whole app into one giant find-her-phone button. */
    fun finderRole(c: Context): String = prefs(c).getString(KEY_FINDER_ROLE, "").orEmpty()

    fun setFinderRole(c: Context, role: String) {
        prefs(c).edit().putString(KEY_FINDER_ROLE, role.trim()).apply()
    }

    /** Ammamma's number — where a finder phone sends the code-word SMS. */
    fun herNumber(c: Context): String = prefs(c).getString(KEY_HER_NUMBER, "").orEmpty()

    fun setHerNumber(c: Context, raw: String) {
        prefs(c).edit().putString(KEY_HER_NUMBER, raw.trim()).apply()
    }
}
