package com.ammamma.companion

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import java.util.Calendar

/**
 * LOCAL, ZERO-INTERNET matcher for the small set of everyday commands that must
 * work instantly even with no signal: time, battery, torch, wifi, volume, alarm,
 * photo-send, mobile data. Tried BEFORE the AI (see CommandRouter.resolve()) —
 * only a non-null result short-circuits the AI; anything else (real chat) falls
 * through untouched.
 *
 * Patterns are generous (Telugu + common STT transliteration + English) but each
 * one requires a fairly distinctive word/phrase, so an ordinary conversational
 * sentence should never accidentally fire a verb.
 */
object OfflineIntents {

    private const val TAG = "Ammamma"

    fun match(context: Context, text: String): CommandRouter.Action? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return matchTimeOrDate(t)
            ?: matchBattery(context, t)
            ?: matchTorch(context, t)
            ?: matchWifi(context, t)
            ?: matchVolume(context, t)
            ?: matchAlarmCancel(context, t)
            ?: matchAlarmSet(context, t)
            ?: matchPhotoSend(context, t)
            ?: matchMobileData(t)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Whole-word match for short English tokens ("time", "on") — a plain
     *  .contains() would also fire inside unrelated words ("phone" has "on"). */
    private fun containsWord(text: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private val ON_WORDS_TE = listOf("వెయ్యి", "వెలిగించు", "ఆన్", "వేయండి", "వెలిగించండి")
    private val ON_WORDS_EN = listOf("on")
    private val OFF_WORDS_TE = listOf("ఆపు", "ఆర్పు", "ఆఫ్", "కట్టేయ్", "తీసేయ్")
    private val OFF_WORDS_EN = listOf("off")

    private fun wantsOn(t: String) = ON_WORDS_TE.any { t.contains(it) } || ON_WORDS_EN.any { containsWord(t, it) }
    private fun wantsOff(t: String) = OFF_WORDS_TE.any { t.contains(it) } || OFF_WORDS_EN.any { containsWord(t, it) }

    /** ఉదయం/మధ్యాహ్నం/సాయంత్రం/రాత్రి day-part word for a 24h hour. */
    private fun dayPart(hour: Int): String = when (hour) {
        in 0..3 -> "తెల్లవారుజాము"
        in 4..11 -> "ఉదయం"
        in 12..15 -> "మధ్యాహ్నం"
        in 16..18 -> "సాయంత్రం"
        else -> "రాత్రి"
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. Time / date
    // ─────────────────────────────────────────────────────────────────────

    private val TIME_PHRASES = listOf("టైం ఎంత", "సమయం ఎంత", "ఇప్పుడు టైం", "టైమెంత", "సమయమెంత")
    private val DATE_PHRASES = listOf("ఈరోజు ఏ రోజు", "తేదీ", "ఏ రోజు ఈరోజు")
    private val WEEKDAYS = arrayOf(
        "", "ఆదివారం", "సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం"
    )
    private val MONTHS = arrayOf(
        "జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్",
        "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"
    )

    private fun matchTimeOrDate(t: String): CommandRouter.Action? {
        val isTime = TIME_PHRASES.any { t.contains(it) } || containsWord(t, "time")
        val isDate = DATE_PHRASES.any { t.contains(it) } || containsWord(t, "date")
        if (!isTime && !isDate) return null
        val cal = Calendar.getInstance()
        if (isDate && !isTime) {
            val weekday = WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK)]
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val month = MONTHS[cal.get(Calendar.MONTH)]
            val year = cal.get(Calendar.YEAR)
            return CommandRouter.Action.Say("ఈరోజు $weekday, $day $month $year")
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val part = dayPart(hour)
        val spoken = if (minute == 0) "ఇప్పుడు $part $hour12 గంటలు"
        else "ఇప్పుడు $part $hour12 గంటల $minute నిమిషాలు"
        return CommandRouter.Action.Say(spoken)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. Battery — real numbers, never dumbed down.
    // ─────────────────────────────────────────────────────────────────────

    private val BATTERY_PHRASES = listOf("బ్యాటరీ", "ఛార్జింగ్ ఎంత", "ఛార్జ్ ఎంత")

    private fun matchBattery(context: Context, t: String): CommandRouter.Action? {
        if (!BATTERY_PHRASES.any { t.contains(it) } && !containsWord(t, "battery")) return null
        val sticky = context.applicationContext
            .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return CommandRouter.Action.Say("బ్యాటరీ స్థితి తెలియడం లేదు")
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return CommandRouter.Action.Say("బ్యాటరీ స్థితి తెలియడం లేదు")
        val pct = level * 100 / scale
        return when (sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_FULL ->
                CommandRouter.Action.Say("బ్యాటరీ $pct శాతం, పూర్తిగా ఛార్జ్ అయ్యింది")
            BatteryManager.BATTERY_STATUS_CHARGING -> {
                val mins = ChargeState.minutesToFull(pct)
                val eta = if (mins != null) "ఇంకా $mins నిమిషాల్లో నిండుతుంది"
                else "ఇంకా ఎంత సేపు పడుతుందో తెలియడం లేదు"
                CommandRouter.Action.Say("బ్యాటరీ $pct శాతం ఉంది, ఛార్జ్ అవుతోంది, $eta")
            }
            else -> CommandRouter.Action.Say("బ్యాటరీ $pct శాతం ఉంది, ఛార్జింగ్‌లో లేదు")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. Flashlight/torch — "లైట్" alone is too common a word (any light/lamp
    //    talk), so it only fires paired with an explicit on/off word; the
    //    unambiguous loanwords (టార్చ్/torch/ఫ్లాష్/flash) can toggle bare.
    // ─────────────────────────────────────────────────────────────────────

    private var torchOn = false

    private fun matchTorch(context: Context, t: String): CommandRouter.Action? {
        val unambiguous = listOf("టార్చ్", "ఫ్లాష్").any { t.contains(it) } ||
            listOf("torch", "flash").any { containsWord(t, it) }
        val on = wantsOn(t)
        val off = wantsOff(t)
        val lightWithAction = t.contains("లైట్") && (on || off)
        if (!unambiguous && !lightWithAction) return null
        val turnOn = when {
            on && !off -> true
            off && !on -> false
            else -> !torchOn // bare mention ("టార్చ్") -> toggle
        }
        return setTorch(context, turnOn)
    }

    private fun setTorch(context: Context, on: Boolean): CommandRouter.Action {
        val mgr = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return CommandRouter.Action.Say("టార్చ్ ఈ ఫోన్‌లో లేదు")
        return try {
            val id = mgr.cameraIdList.firstOrNull { cid ->
                val chars = mgr.getCameraCharacteristics(cid)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return CommandRouter.Action.Say("ఈ ఫోన్‌లో ఫ్లాష్ లేదు")
            mgr.setTorchMode(id, on)
            torchOn = on
            CommandRouter.Action.Say(if (on) "టార్చ్ వెలిగించాను" else "టార్చ్ ఆపేశాను")
        } catch (e: Exception) {
            Log.w(TAG, "torch toggle failed", e)
            CommandRouter.Action.Say("టార్చ్ మార్చలేకపోయాను")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. Wi-Fi — legal at targetSdk 27 (deprecated on 29+, this app targets 27).
    // ─────────────────────────────────────────────────────────────────────

    private fun matchWifi(context: Context, t: String): CommandRouter.Action? {
        if (!t.contains("వైఫై") && !containsWord(t, "wifi")) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return CommandRouter.Action.Say("వైఫై నియంత్రణ ఈ ఫోన్‌లో లేదు")
        val turnOn = when {
            wantsOn(t) && !wantsOff(t) -> true
            wantsOff(t) && !wantsOn(t) -> false
            else -> !wm.isWifiEnabled
        }
        return try {
            @Suppress("DEPRECATION")
            val ok = wm.setWifiEnabled(turnOn)
            if (ok) CommandRouter.Action.Say(if (turnOn) "వైఫై ఆన్ చేశాను" else "వైఫై ఆఫ్ చేశాను")
            else CommandRouter.Action.Say("వైఫై మార్చలేకపోయాను")
        } catch (e: Exception) {
            Log.w(TAG, "wifi toggle failed", e)
            CommandRouter.Action.Say("వైఫై మార్చలేకపోయాను")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. Volume — STREAM_MUSIC (her speech/media) + STREAM_RING, 2 steps at a
    //    time so "louder" is a noticeable nudge, not a jump to full blast.
    // ─────────────────────────────────────────────────────────────────────

    private const val VOLUME_STEP = 2

    private fun matchVolume(context: Context, t: String): CommandRouter.Action? {
        val hasVolumeWord = t.contains("సౌండ్") || t.contains("వాల్యూమ్") || containsWord(t, "volume") ||
            (t.contains("గట్టిగా") && t.contains("చెప్పు")) ||
            (t.contains("నెమ్మదిగా") && t.contains("చెప్పు"))
        if (!hasVolumeWord) return null
        val increase = t.contains("పెంచు") || t.contains("గట్టిగా")
        val decrease = t.contains("తగ్గించు") || t.contains("నెమ్మదిగా")
        val toMax = t.contains("పూర్తిగా")

        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (!increase && !decrease && !toMax) {
            return CommandRouter.Action.Say("సౌండ్ ఇప్పుడు $maxMusic లో $curMusic ఉంది")
        }
        val newMusic = stepVolume(curMusic, maxMusic, increase, toMax)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newMusic, 0)
        // Ring stream gets the same nudge on its own scale so the ringer stays audible too.
        val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        val curRing = am.getStreamVolume(AudioManager.STREAM_RING)
        runCatching { am.setStreamVolume(AudioManager.STREAM_RING, stepVolume(curRing, maxRing, increase, toMax), 0) }
        return CommandRouter.Action.Say("సౌండ్ $maxMusic లో $newMusic")
    }

    private fun stepVolume(current: Int, max: Int, increase: Boolean, toMax: Boolean): Int = when {
        toMax -> max
        increase -> (current + VOLUME_STEP).coerceAtMost(max)
        else -> (current - VOLUME_STEP).coerceAtLeast(0)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 6. Alarm set/cancel — parses digits or Telugu number words, defaults to
    //    the nearest FUTURE occurrence when am/pm isn't said.
    // ─────────────────────────────────────────────────────────────────────

    private val ALARM_WORDS = listOf("అలారం") // + English "alarm" checked separately (word boundary)
    private fun hasAlarmWord(t: String) = ALARM_WORDS.any { t.contains(it) } || containsWord(t, "alarm")

    private val CANCEL_WORDS_TE = listOf("తీసెయ్", "తీసేయ్")

    private fun matchAlarmCancel(context: Context, t: String): CommandRouter.Action? {
        if (!hasAlarmWord(t)) return null
        if (!wantsOff(t) && !CANCEL_WORDS_TE.any { t.contains(it) } && !containsWord(t, "cancel")) return null
        Settings.setAlarmEnabled(context, false)
        DayScheduler.scheduleAll(context)
        return CommandRouter.Action.Say("అలారం ఆపేశాను")
    }

    private val HOUR_WORDS = linkedMapOf(
        "ఒకటి" to 1, "రెండు" to 2, "మూడు" to 3, "నాలుగు" to 4, "ఐదు" to 5, "ఆరు" to 6,
        "ఏడు" to 7, "ఎనిమిది" to 8, "తొమ్మిది" to 9, "పది" to 10, "పదకొండు" to 11, "పన్నెండు" to 12
    )

    /** Digits ("6:30", "6 30"), Telugu number-word hours ("ఆరు గంటలకు" = 6:00), and
     *  the half-hour contraction ("ఆరున్నర" = 6:30). Returns 24h-agnostic (hour, minute)
     *  — am/pm is resolved separately in [alarmCalendar]. */
    private fun parseAlarmTime(text: String): Pair<Int, Int>? {
        for ((word, hour) in HOUR_WORDS) {
            if (text.contains("${word}న్నర")) return hour to 30
        }
        Regex("([01]?[0-9]|2[0-3])[:\\s]([0-5][0-9])\\b").find(text)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        Regex("([01]?[0-9]|2[0-3])\\s*గంట").find(text)?.let {
            return it.groupValues[1].toInt() to 0
        }
        for ((word, hour) in HOUR_WORDS) {
            if (text.contains(word) && text.contains("గంట")) return hour to 0
        }
        return null
    }

    /** Resolves am/pm from Telugu day-part words; with none given and an hour
     *  0..11, picks whichever of the AM/PM reading comes soonest in the future. */
    private fun alarmCalendar(hourRaw: Int, minute: Int, text: String): Calendar {
        val now = Calendar.getInstance()
        fun at(hour: Int): Calendar = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun nextOccurrence(cal: Calendar): Calendar {
            if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal
        }
        if (hourRaw >= 12) return nextOccurrence(at(hourRaw.coerceAtMost(23)))
        val isAm = text.contains("ఉదయం") || text.contains("పొద్దున")
        val isPm = text.contains("మధ్యాహ్నం") || text.contains("సాయంత్రం") || text.contains("రాత్రి")
        return when {
            isAm -> nextOccurrence(at(hourRaw))
            isPm -> nextOccurrence(at(hourRaw + 12))
            else -> {
                val am = nextOccurrence(at(hourRaw))
                val pm = nextOccurrence(at(hourRaw + 12))
                if (am.timeInMillis <= pm.timeInMillis) am else pm
            }
        }
    }

    private fun matchAlarmSet(context: Context, t: String): CommandRouter.Action? {
        if (!hasAlarmWord(t)) return null
        val (rawHour, minute) = parseAlarmTime(t)
            ?: return CommandRouter.Action.Say("టైం అర్థం కాలేదు, ఇంకోసారి చెప్పండి")
        val cal = alarmCalendar(rawHour, minute, t)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        Settings.setAlarmTime(context, hour, minute)
        Settings.setAlarmEnabled(context, true)
        DayScheduler.scheduleAll(context)
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val timeStr = if (minute == 0) "$hour12 గంటలకు" else "$hour12 గంటల $minute నిమిషాలకు"
        return CommandRouter.Action.Say("${dayPart(hour)} $timeStr అలారం పెట్టాను")
    }

    // ─────────────────────────────────────────────────────────────────────
    // 7. Photo send — hands off to CameraSendActivity (built in a parallel
    //    worktree). Referenced by STRING so this branch keeps compiling
    //    without that class. Plain "ఫోటో తియ్యి" (no send word) falls through.
    // ─────────────────────────────────────────────────────────────────────

    private fun matchPhotoSend(context: Context, t: String): CommandRouter.Action? {
        val hasPhoto = t.contains("ఫోటో") || containsWord(t, "photo")
        val hasSend = t.contains("పంపు") || t.contains("పంపండి") || containsWord(t, "pampu") || containsWord(t, "send")
        if (!hasPhoto || !hasSend) return null
        val intent = Intent().setClassName(context, "com.ammamma.companion.CameraSendActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return CommandRouter.Action.Launch(intent, "కెమెరా తెరుస్తున్నాను, ఫోటో తీసి పంపుదాం")
    }

    // ─────────────────────────────────────────────────────────────────────
    // 8. Mobile data — Android forbids apps toggling it; be honest and point
    //    her at the one screen that can.
    // ─────────────────────────────────────────────────────────────────────

    private fun matchMobileData(t: String): CommandRouter.Action? {
        val hasData = t.contains("డేటా") || t.contains("నెట్")
        if (!hasData || !wantsOn(t)) return null
        val intent = Intent(AndroidSettings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return CommandRouter.Action.Launch(intent, "డేటా నేను ఆన్ చేయలేను, ఈ స్క్రీన్‌లో డేటా బటన్ నొక్కండి")
    }
}
