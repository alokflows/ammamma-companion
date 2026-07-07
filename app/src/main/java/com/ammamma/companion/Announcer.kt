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
 * The clip folder is empty until the family records voices (Alok's last step), so
 * today everything flows through TTS. When clips arrive, NO code changes — this
 * class just finds and plays them. That's the "swap voices without rebuild" promise.
 *
 * Before any sound we raise media volume to max (so she always hears it), and
 * restore her previous volume when the sound finishes.
 */
class Announcer private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var savedAccessVolume = -1
    // Ring-duck state: -1 means "not ducked". Kept separate from the media-volume
    // save so answering a call restores the ringtone even mid-announcement.
    private var savedRingVolume = -1
    private var savedNotifVolume = -1

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed (status=$status). Only recorded clips will play.")
            return
        }
        val res = tts?.setLanguage(Locale("te", "IN"))
        ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        Log.i(TAG, "TTS ready. Telugu available=$ttsReady (result=$res)")

        pickBestTeluguVoice()
        // Slightly slower than default = clearer for an elderly listener; normal pitch.
        tts?.setSpeechRate(0.92f)
        tts?.setPitch(1.0f)
        // Mark our speech as accessibility assistance: the system treats it as
        // important spoken output (not casual media), and it survives silent mode
        // better on some builds.
        try {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "TTS audio attributes not accepted by this engine", e)
        }
        // Restore volume once TTS finishes speaking.
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = restoreVolume()
            @Deprecated("legacy") override fun onError(utteranceId: String?) = restoreVolume()
        })
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
        raiseVolumeToMax()
        val clip = findClip(eventKey)
        if (clip != null) {
            playClip(clip)
        } else {
            speak(fallbackText)
        }
    }

    /** Speak arbitrary text (e.g. a live AI reply) via TTS — no clip lookup. */
    fun say(text: String) {
        raiseVolumeToMax()
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
                setOnCompletionListener { restoreVolume() }
                prepare()
                start()
            }
            Log.i(TAG, "Playing clip ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Clip play failed for ${file.name}; restoring volume", e)
            restoreVolume()
        }
    }

    private fun speak(text: String) {
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "announce")
        if (r == TextToSpeech.SUCCESS) {
            Log.i(TAG, "TTS speaking: \"$text\"")
            // volume restored by the utterance listener's onDone
        } else {
            Log.w(TAG, "No voice available to say: \"$text\" (result=$r)")
            restoreVolume()
        }
    }

    private fun raiseVolumeToMax() {
        if (savedVolume < 0) savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
        // TTS now plays on the ACCESSIBILITY stream (see the audio attributes in
        // onInit), which has its own volume on Android 8 — raise it too so speech
        // is as loud as the recorded clips.
        try {
            if (savedAccessVolume < 0) {
                savedAccessVolume = audio.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
            }
            audio.setStreamVolume(
                AudioManager.STREAM_ACCESSIBILITY,
                audio.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY),
                0
            )
        } catch (e: Exception) {
            Log.w(TAG, "Accessibility stream not adjustable here", e)
        }
    }

    private fun restoreVolume() {
        if (savedVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            savedVolume = -1
        }
        try {
            if (savedAccessVolume >= 0) {
                audio.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, savedAccessVolume, 0)
                savedAccessVolume = -1
            }
        } catch (e: Exception) {
            savedAccessVolume = -1
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

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null
        player?.release(); player = null
        // Drop the cached singleton so a fresh service gets a working engine.
        synchronized(Companion) { if (instance === this) instance = null }
    }

    companion object {
        private const val TAG = "Ammamma"

        // One voice for the whole app — the service and every screen share it, so we
        // never run two TTS engines at once or talk over ourselves.
        @Volatile
        private var instance: Announcer? = null

        fun get(context: Context): Announcer =
            instance ?: synchronized(this) {
                instance ?: Announcer(context.applicationContext).also { instance = it }
            }
    }
}
