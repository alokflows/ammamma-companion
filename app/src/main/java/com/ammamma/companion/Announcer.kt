package com.ammamma.companion

import android.content.Context
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
        // Restore volume once TTS finishes speaking.
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = restoreVolume()
            @Deprecated("legacy") override fun onError(utteranceId: String?) = restoreVolume()
        })
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
    }

    private fun restoreVolume() {
        if (savedVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            savedVolume = -1
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
