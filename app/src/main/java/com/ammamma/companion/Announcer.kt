package com.ammamma.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

        pickBestTeluguVoice()
        // Slightly slower than default = clearer for an elderly listener; normal pitch.
        tts?.setSpeechRate(0.92f)
        tts?.setPitch(1.0f)
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
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = restoreVolumeIfIdle()
            @Deprecated("legacy") override fun onError(utteranceId: String?) = restoreVolumeIfIdle()
        })

        // Engine is ready — say anything that arrived while it was starting up.
        initDone = true
        pendingText?.let { queued ->
            pendingText = null
            speak(queued)
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
     * Speak an event. [eventKey] names the recorded clip to prefer; [fallbackText]
     * is what TTS says if there's no clip yet.
     */
    fun announce(eventKey: String, fallbackText: String) {
        val clip = findClip(eventKey)
        if (clip != null) {
            raiseVolumeToMax()
            playClip(clip)
        } else {
            speak(fallbackText)
        }
    }

    /** Speak arbitrary text (e.g. a live AI reply) via TTS — no clip lookup. */
    fun say(text: String) {
        speak(text)
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

    private fun speak(text: String) {
        if (text.isBlank()) return
        // SELF-HEAL: if the engine was never created or got torn down, bring it back.
        // The line is then queued and spoken from onInit — never dropped.
        if (tts == null) {
            Log.w(TAG, "TTS engine was gone; re-creating it")
            createEngine()
        }
        if (!initDone) {
            pendingText = text
            Log.i(TAG, "TTS still starting; queued: \"$text\"")
            return
        }
        raiseVolumeToMax()
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "announce")
        if (r == TextToSpeech.SUCCESS) {
            Log.i(TAG, "TTS speaking: \"$text\"")
            // volume restored by the utterance listener's onDone (when idle)
        } else {
            Log.w(TAG, "No voice available to say: \"$text\" (result=$r)")
            restoreVolumeIfIdle()
        }
    }

    private fun raiseVolumeToMax() {
        // Save her level ONCE per burst of speech; overlapping lines must not stack
        // saves (which would later restore to an already-raised value).
        if (savedVolume < 0) savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0
            )
        }
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

        // One voice for the whole app — the service and every screen share it, so we
        // never run two TTS engines at once or talk over ourselves. It lives for the
        // whole process; nothing tears it down (see rule 2 in the class header).
        @Volatile
        private var instance: Announcer? = null

        fun get(context: Context): Announcer =
            instance ?: synchronized(this) {
                instance ?: Announcer(context.applicationContext).also { instance = it }
            }
    }
}
