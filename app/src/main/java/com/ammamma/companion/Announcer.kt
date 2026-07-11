package com.ammamma.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * The single voice of the companion. EVERY spoken line goes through here.
 *
 * Priority (the brief's audio strategy, in code):
 *   1. A pre-recorded family-voice clip for this event  ->  filesDir/clips/<key>.*
 *   2. Android TTS (Telugu if available) as a fallback for anything not recorded.
 *
 * ── CONSISTENCY IS THE PROMISE ────────────────────────────────────────────────
 * Ammamma cannot read, so "sometimes it speaks, sometimes it doesn't" is the worst
 * possible bug: she stops trusting the phone. Three rules keep the voice reliable:
 *
 *   1. SELF-HEALING ENGINE. The TTS engine is created lazily and re-created if it
 *      was ever torn down or never came up. A spoken line is NEVER silently dropped:
 *      if the engine isn't ready yet, the line is queued and spoken from onInit.
 *
 *   2. THE ENGINE OUTLIVES THE SERVICE. The companion service is destroyed and
 *      resurrected constantly on ColorOS (watchdog, task-removal). It must NOT tear
 *      down this shared, app-scoped voice — doing so left every Activity holding a
 *      dead engine that failed silently. The singleton lives for the whole process.
 *
 *   3. ONE STREAM WE CONTROL. Speech plays on STREAM_MUSIC, the exact stream we
 *      raise to max before speaking — so "did I actually hear it?" never depends on
 *      a separate accessibility-stream volume the app can't reliably set.
 *
 * The clip folder is empty until the family records voices, so today everything
 * flows through TTS. When clips arrive, NO code changes — this class just finds and
 * plays them.
 */
class Announcer private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // The engine starts ASYNCHRONOUSLY. Until onInit runs we cannot speak, so any
    // line that arrives first is remembered here and spoken the moment we're ready —
    // grandma never loses a line to a cold start (e.g. a call right after boot).
    @Volatile private var initDone = false
    @Volatile private var pendingText: String? = null
    // Whether the queued line is an IMPORTANT one (find-my-phone / urgent battery):
    // remembered so it's spoken at full volume even after a cold-start queue.
    @Volatile private var pendingImportant = false

    private var player: MediaPlayer? = null

    // Volume save/restore is guarded so overlapping lines can't corrupt it: we save
    // her level ONCE (when the first line starts) and only put it back when the voice
    // is fully idle again — a stale onDone can never quiet a line that's still going.
    private var savedVolume = -1

    // Ring-duck state: -1 means "not ducked". Separate from the media-volume save so
    // answering a call restores the ringtone even in the middle of an announcement.
    private var savedRingVolume = -1
    private var savedNotifVolume = -1

    init {
        createEngine()
    }

    /** Create (or re-create) the TTS engine. Safe to call repeatedly. */
    private fun createEngine() {
        initDone = false
        ttsReady = false
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed (status=$status). Only recorded clips will play.")
            initDone = true      // unblock speak(); it will log-and-skip instead of queueing forever
            pendingText = null
            return
        }
        val res = tts?.setLanguage(Locale("te", "IN"))
        ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        Log.i(TAG, "TTS ready. Telugu available=$ttsReady (result=$res)")

        // Speak as ordinary MEDIA: it plays on STREAM_MUSIC, the one stream we raise
        // to max in raiseVolumeToMax(). (We used to use the accessibility stream,
        // whose volume the app can't always set — so speech was sometimes inaudible.)
        try {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "TTS audio attributes not accepted by this engine", e)
        }
        // Restore volume when TTS finishes — but only if nothing else is speaking.
        // An "audition" utterance (Settings ▶ preview) also needs the REAL saved
        // voice/rate/pitch put back once its sample ends, so a preview can never
        // leak into the voice grandma actually hears the rest of the time.
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == AUDITION_UTTERANCE) applyVoiceSettings(context)
                restoreVolumeIfIdle()
            }
            @Deprecated("legacy") override fun onError(utteranceId: String?) {
                if (utteranceId == AUDITION_UTTERANCE) applyVoiceSettings(context)
                restoreVolumeIfIdle()
            }
        })

        // Engine is ready — flip the flag BEFORE configuring voice/rate/pitch,
        // since applyVoiceSettings() itself checks initDone (it's also called
        // later from Settings, when the engine is long since up).
        initDone = true
        applyVoiceSettings(context)

        // Say anything that arrived while it was starting up.
        pendingText?.let { queued ->
            val wasImportant = pendingImportant
            pendingText = null
            pendingImportant = false
            speak(queued, wasImportant)
        }
    }

    /**
     * Pick the best OFFLINE Telugu voice the engine has. Google TTS usually ships
     * several te-IN voices of different quality; the default is not always the
     * nicest. Network voices are skipped — the app must speak with no internet.
     * Old/simple engines (eSpeak) may not support voice listing at all, so
     * everything is guarded: on any failure we keep the engine's default voice.
     */
    private fun pickBestTeluguVoice() {
        try {
            val best = tts?.voices
                ?.filter { it.locale.language == "te" && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }
                ?: return
            tts?.voice = best
            Log.i(TAG, "TTS voice: ${best.name} (quality=${best.quality}, offline)")
        } catch (e: Exception) {
            Log.w(TAG, "Voice listing unsupported; keeping engine default", e)
        }
    }

    /**
     * Apply the saved voice + rate + pitch to the (already-initialized) engine.
     * Called once from onInit right after the engine comes up, and again any
     * time Settings changes so a new choice is live without an app restart.
     * Safe to call while idle; a no-op if the engine isn't ready yet (onInit
     * will call it itself once it is — see the ordering note there).
     */
    fun applyVoiceSettings(c: Context) {
        val engine = tts ?: return
        if (!initDone) return
        val savedName = Settings.ttsVoiceName(c)
        val chosen = if (savedName.isNotEmpty()) {
            runCatching { engine.voices?.firstOrNull { it.name == savedName } }.getOrNull()
        } else null
        if (chosen != null) {
            runCatching { engine.voice = chosen }
            Log.i(TAG, "TTS voice: ${chosen.name} (saved choice)")
        } else {
            // "" saved (Automatic), or the saved voice vanished (e.g. TTS data
            // was cleared/reinstalled) -> fall back to the best offline Telugu
            // voice, same as before this setting existed.
            pickBestTeluguVoice()
        }
        // Always apply the saved rate/pitch — no more hardcoded 0.92f/1.0f.
        runCatching { engine.setSpeechRate(Settings.ttsRate(c)) }
        runCatching { engine.setPitch(Settings.ttsPitch(c)) }
    }

    /**
     * Telugu voices this engine can offer, best first. OFFLINE voices always
     * sort ahead of any that need the network — the phone must speak with no
     * internet, so a network voice never looks like the obvious pick even if
     * the engine ranks its quality higher. Empty when the engine isn't ready
     * yet, or the engine can't list voices at all (guarded like
     * pickBestTeluguVoice — some simple engines, e.g. eSpeak, don't support this).
     */
    fun availableTeluguVoices(): List<Voice> {
        if (!initDone) return emptyList()
        return try {
            tts?.voices
                ?.filter { it.locale.language == "te" }
                ?.sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality })
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Voice listing unsupported; no Telugu voices to offer", e)
            emptyList()
        }
    }

    /**
     * Preview a candidate voice/rate/pitch for the Settings screen — a ▶ row
     * button, or a rate/pitch slider release. Nothing is saved here: once the
     * sample finishes (or fails), the "audition" branch in the utterance
     * listener above puts back whatever IS actually saved, so an unsaved
     * preview can never leak into the voice grandma actually hears elsewhere.
     * [voice] null previews "Automatic" (the engine's own best-Telugu pick).
     */
    fun audition(voice: Voice?, ratePercent: Int, pitchPercent: Int) {
        val engine = tts ?: return
        if (!initDone) return   // nothing to preview until the engine is actually up
        // ONE VOICE: stop whatever is currently playing before the preview starts.
        silenceCurrent()
        raiseVolume(important = false)
        try {
            if (voice != null) engine.voice = voice else pickBestTeluguVoice()
            engine.setSpeechRate(ratePercent.coerceIn(50, 150) / 100f)
            engine.setPitch(pitchPercent.coerceIn(50, 150) / 100f)
        } catch (e: Exception) {
            Log.w(TAG, "Audition voice/rate/pitch not accepted", e)
        }
        val r = engine.speak(AUDITION_SAMPLE, TextToSpeech.QUEUE_FLUSH, null, AUDITION_UTTERANCE)
        if (r != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Audition speak() failed (result=$r)")
            restoreVolumeIfIdle()
            applyVoiceSettings(context)
        }
    }

    /**
     * Speak an event. [eventKey] names the recorded clip to prefer; [fallbackText]
     * is what TTS says if there's no clip yet.
     *
     * [important] marks a safety line (find-my-phone, urgent low battery): it ignores
     * the quick-mute AND ignores the volume-percent setting (always full volume).
     * Ordinary lines are skipped when muted and play at the family-set volume percent.
     */
    fun announce(eventKey: String, fallbackText: String, important: Boolean = false) {
        if (skipBecauseMuted(important, fallbackText)) return
        val clip = findClip(eventKey)
        if (clip != null) {
            // ONE VOICE: a clip and a TTS line must never overlap. Stop whatever is
            // playing right now (the other engine too) before this clip starts.
            silenceCurrent()
            raiseVolume(important)
            playClip(clip)
        } else if (!playBundledClip(eventKey, important)) {
            speak(fallbackText, important)
        }
    }

    /**
     * Studio-voice defaults: fixed lines pre-synthesized with a neural Telugu voice
     * and bundled in the APK (assets/clips/<key>.mp3) — so the phone speaks
     * beautifully offline out of the box. A family recording (findClip) always
     * wins over these; TTS remains the fallback for keys with no bundled clip.
     */
    private fun playBundledClip(eventKey: String, important: Boolean): Boolean {
        val afd = try { context.assets.openFd("clips/$eventKey.mp3") } catch (e: Exception) { return false }
        return try {
            silenceCurrent()
            raiseVolume(important)
            player = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setOnCompletionListener { restoreVolumeIfIdle() }
                prepare()
                start()
            }
            Log.i(TAG, "Playing bundled clip $eventKey")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Bundled clip failed for $eventKey", e)
            restoreVolumeIfIdle()
            false
        } finally {
            // MediaPlayer duplicates the descriptor in setDataSource — safe to close now.
            runCatching { afd.close() }
        }
    }

    /** Speak arbitrary text (e.g. a live AI reply) via TTS — no clip lookup. */
    fun say(text: String, important: Boolean = false) {
        if (skipBecauseMuted(important, text)) return
        speak(text, important)
    }

    /** True (and logs) when a non-important line should be dropped because the family
     *  has flipped the quick-mute. Important lines are never dropped. */
    private fun skipBecauseMuted(important: Boolean, text: String): Boolean {
        if (important) return false
        if (!Settings.voiceMuted(context)) return false
        Log.i(TAG, "Muted — skipping ordinary line: \"$text\"")
        return true
    }

    /**
     * ONE VOICE, enforced. Stop the current TTS utterance AND any playing clip AND
     * any line still queued behind engine startup — WITHOUT restoring the saved
     * volume, because a new line is about to start and will keep the level raised.
     * (stopSpeaking() is the public "go fully idle + restore" variant.)
     */
    private fun silenceCurrent() {
        pendingText = null
        pendingImportant = false
        runCatching { tts?.stop() }
        player?.run { runCatching { if (isPlaying) stop() }; release() }
        player = null
    }

    private fun findClip(eventKey: String): File? {
        val dir = File(context.filesDir, "clips")
        if (!dir.isDirectory) return null
        return dir.listFiles { f -> f.nameWithoutExtension == eventKey }?.firstOrNull()
    }

    private fun playClip(file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { restoreVolumeIfIdle() }
                prepare()
                start()
            }
            Log.i(TAG, "Playing clip ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Clip play failed for ${file.name}; restoring volume", e)
            restoreVolumeIfIdle()
        }
    }

    private fun speak(text: String, important: Boolean = false) {
        if (text.isBlank()) return
        // SELF-HEAL: if the engine was never created or got torn down, bring it back.
        // The line is then queued and spoken from onInit — never dropped.
        if (tts == null) {
            Log.w(TAG, "TTS engine was gone; re-creating it")
            createEngine()
        }
        if (!initDone) {
            pendingText = text
            pendingImportant = important
            Log.i(TAG, "TTS still starting; queued: \"$text\"")
            return
        }
        // ONE VOICE: stop any clip that might be playing so it can't overlap TTS.
        // (QUEUE_FLUSH below only flushes other TTS, not a MediaPlayer clip.)
        player?.run { runCatching { if (isPlaying) stop() }; release() }
        player = null
        raiseVolume(important)
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "announce")
        if (r == TextToSpeech.SUCCESS) {
            Log.i(TAG, "TTS speaking: \"$text\"")
            // volume restored by the utterance listener's onDone (when idle)
        } else {
            Log.w(TAG, "No voice available to say: \"$text\" (result=$r)")
            restoreVolumeIfIdle()
        }
    }

    /**
     * Raise the media stream for a spoken line. Ordinary lines go to the family-set
     * percentage of max (Settings.speechVolumePercent) — the phone used to SLAM every
     * line to full volume, which startled her. IMPORTANT lines (find-my-phone, urgent
     * low battery) still go to full max so a safety alert is never quiet.
     */
    private fun raiseVolume(important: Boolean) {
        // Save her level ONCE per burst of speech; overlapping lines must not stack
        // saves (which would later restore to an already-raised value).
        if (savedVolume < 0) savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = if (important) {
            max
        } else {
            (max * Settings.speechVolumePercent(context) / 100).coerceIn(1, max)
        }
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    /**
     * Put her media volume back — but ONLY when the voice is fully idle. A finished
     * line whose onDone fires while a NEW line is still playing must not quiet it;
     * that final line's own onDone (or stopSpeaking) does the restore instead.
     */
    private fun restoreVolumeIfIdle() {
        if (isSpeaking()) return
        if (savedVolume >= 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) }
            savedVolume = -1
        }
    }

    /**
     * Quiet the RINGTONE (never silence it) while we announce who is calling.
     * The ringtone lives on STREAM_RING, which used to drown out the spoken name.
     * We drop ring + notification to ~25% of max — still clearly a ringing phone,
     * but the voice wins. Idempotent: calling twice ducks once.
     */
    fun duckRingForSpeech() {
        try {
            if (savedRingVolume < 0) {
                savedRingVolume = audio.getStreamVolume(AudioManager.STREAM_RING)
                val quarter = (audio.getStreamMaxVolume(AudioManager.STREAM_RING) / 4)
                    .coerceAtLeast(1)   // never 0: it must still audibly ring
                audio.setStreamVolume(AudioManager.STREAM_RING, quarter, 0)
            }
            if (savedNotifVolume < 0) {
                savedNotifVolume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                val quarter = (audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) / 4)
                    .coerceAtLeast(1)
                audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, quarter, 0)
            }
            Log.i(TAG, "Ring ducked for caller announcement")
        } catch (e: Exception) {
            // e.g. Do-Not-Disturb policy refuses the change — a quieter announcement
            // is better than a crashed one, so just log and carry on.
            Log.w(TAG, "Could not duck the ringtone", e)
        }
    }

    /** Put the ringtone volume back exactly as she had it. Safe to call anytime. */
    fun restoreRing() {
        try {
            if (savedRingVolume >= 0) {
                audio.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0)
                savedRingVolume = -1
            }
            if (savedNotifVolume >= 0) {
                audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVolume, 0)
                savedNotifVolume = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore ring volume", e)
            savedRingVolume = -1
            savedNotifVolume = -1
        }
    }

    /** True while a line is being spoken, a clip is playing, or one is queued
     *  behind TTS startup. The caller-repeat loop checks this so a LONG name is
     *  never chopped mid-word by the next repeat. */
    fun isSpeaking(): Boolean =
        pendingText != null ||
            runCatching { tts?.isSpeaking == true }.getOrDefault(false) ||
            runCatching { player?.isPlaying == true }.getOrDefault(false)

    /**
     * Cut off whatever is being said RIGHT NOW — for a dismiss/stop tap. Stops the
     * TTS utterance, stops+releases any playing clip, and puts every volume back.
     * Fully idempotent, and the ENGINE STAYS ALIVE: the next announce()/say() works
     * normally. (We never shut the engine down here — that was the old flakiness.)
     */
    fun stopSpeaking() {
        pendingText = null   // a line still waiting for TTS startup must die too
        runCatching { tts?.stop() }
        player?.run { runCatching { if (isPlaying) stop() }; release() }
        player = null
        // Now truly idle → these actually restore (isSpeaking() is false).
        restoreVolumeIfIdle()
        restoreRing()
        Log.i(TAG, "stopSpeaking(): announcement cut off, volumes restored")
    }

    companion object {
        private const val TAG = "Ammamma"

        // Voice audition (v1.1 Settings): a distinct utteranceId so the shared
        // utterance listener knows to restore the REAL saved voice/rate/pitch
        // afterwards, and a fixed warm sample line so every previewed voice
        // says the exact same thing (a fair comparison between voices).
        private const val AUDITION_UTTERANCE = "audition"
        private const val AUDITION_SAMPLE = "నమస్తే అమ్మమ్మ, ఇది నీ కోసం ఒక కొత్త గొంతు"

        // One voice for the whole app — the service and every screen share it, so we
        // never run two TTS engines at once or talk over ourselves. It lives for the
        // whole process; nothing tears it down (see rule 2 in the class header).
        @Volatile
        private var instance: Announcer? = null

        fun get(context: Context): Announcer =
            instance ?: synchronized(this) {
                instance ?: Announcer(context.applicationContext).also { instance = it }
            }

        // GREETING ONCE PER SESSION: a process-level timestamp (not tied to any one
        // Activity, so it survives an Activity being torn down and recreated — though
        // portrait lock already prevents that on rotation). elapsedRealtime() is used
        // because it can't be fooled by the user changing the clock.
        private const val GREETING_COOLDOWN_MS = 30 * 60 * 1000L
        @Volatile private var lastGreetingAtElapsed = -1L

        /**
         * True at most once every 30 minutes for the whole app's lifetime. Marks the
         * greeting as said (starts the cooldown) the moment it returns true — the
         * caller is expected to actually speak right after checking this.
         */
        fun shouldGreetNow(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (lastGreetingAtElapsed >= 0 && now - lastGreetingAtElapsed < GREETING_COOLDOWN_MS) {
                return false
            }
            lastGreetingAtElapsed = now
            return true
        }
    }
}
