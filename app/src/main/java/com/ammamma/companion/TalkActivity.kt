package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The voice companion. Native pieces only — no libraries (ponytail):
 *   - MIC: Android SpeechRecognizer (Telugu STT) → text  [works on her real phone]
 *   - TYPE: a text box → text  [works everywhere, incl. the emulator for testing]
 *   both feed the same brain: AiBrain (OpenRouter, Gemma-4) → warm Telugu reply,
 *   shown on screen AND spoken (Announcer / TTS).
 */
class TalkActivity : Activity(), RecognitionListener {

    private lateinit var status: TextView
    private lateinit var reply: TextView
    private lateinit var input: EditText
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_talk)
        status = findViewById(R.id.status)
        reply = findViewById(R.id.reply)
        input = findViewById(R.id.input)

        findViewById<Button>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.micButton).setOnClickListener { onMicTap() }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }

        Announcer.get(this).announce("talk_greeting", "Cheppamma, emi kavali")
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        hideKeyboard()
        handleUtterance(text)
    }

    private fun onMicTap() {
        if (Settings.aiKey(this).isBlank()) {
            status.text = "AI సెటప్ కావాలి — settings"
            return
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            ensureMicPermissionThenListen()
        } else {
            // No speech engine (e.g. this emulator): point her at the text box.
            status.text = "మైక్ లేదు — కింద టైప్ చేయండి"   // no mic, type below
            input.requestFocus()
        }
    }

    private fun ensureMicPermissionThenListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        }
    }

    private fun startListening() {
        status.text = "వింటున్నాను…"   // listening
        reply.text = ""
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
        }
        recognizer?.startListening(intent)
    }

    /** She said (or typed) [text]; get a warm Telugu reply, show it and speak it. */
    private fun handleUtterance(text: String) {
        status.text = "ఆలోచిస్తోంది…"   // thinking
        reply.text = text
        val key = Settings.aiKey(this)
        bg.execute {
            val r = AiBrain.ask(key, text)
            main.post {
                if (r.ok) {
                    status.text = ""
                    reply.text = r.text
                    Announcer.get(this).say(r.text)
                } else {
                    // Never silently drop what she said — keep it visible with the reason.
                    status.text = "మళ్ళీ ప్రయత్నించండి"
                    reply.text = "మీరు: $text\n\n${r.text}"
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    // --- RecognitionListener: only results/errors matter ---
    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (text.isNullOrBlank()) status.text = "వినిపించలేదు — మళ్ళీ చెప్పండి లేదా టైప్ చేయండి"
        else handleUtterance(text)
    }

    override fun onError(error: Int) {
        // Kinder than a bare "try again", and always offers the text box.
        status.text = "వినిపించలేదు — టైప్ చేయండి"   // didn't hear — please type
    }

    override fun onEndOfSpeech() { status.text = "ఆలోచిస్తోంది…" }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        recognizer?.destroy()
        bg.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 301
    }
}
