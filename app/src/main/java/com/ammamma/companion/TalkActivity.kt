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
        // She cannot read — so never answer her with a SILENT text line. Every status she
        // needs to know gets SPOKEN too. (Silence made our tester think she'd broken the phone.)
        val voice = Announcer.get(this)
        if (Settings.aiKey(this).isBlank()) {
            val msg = "ఇది మాట్లాడటానికి, ఇంట్లో వాళ్ళు ఒకసారి సిద్ధం చేయాలి"  // family must set it up once
            status.text = msg
            voice.say(msg)
            return
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            ensureMicPermissionThenListen()
        } else {
            // No speech engine (e.g. this emulator). Speak the guidance — and do NOT auto-pop
            // the keyboard: a keyboard reads as "you must type", which frightens a non-typist.
            val msg = "మైక్ ఇక్కడ లేదు, ఇంట్లో వాళ్ళను అడగండి"  // no mic here, ask your family
            status.text = msg
            voice.say(msg)
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
        bg.execute {
            // If she's asking about the weather, fetch today's facts for her town and let
            // the brain phrase them warmly in Telugu. Fetch fails silently → normal chat.
            val weather = if (Weather.isWeatherQuestion(text)) Weather.factsForBrain() else null
            val r = AiBrain.ask(this, text, weather)
            main.post {
                if (r.ok) {
                    status.text = ""
                    reply.text = r.text
                    Announcer.get(this).say(r.text)
                } else {
                    // Never silently drop what she said — show it AND speak the reason aloud,
                    // because a text-only error is invisible to her (r.text is warm Telugu).
                    // The technical detail is printed small for the FAMILY: grandma can't
                    // read it, but whoever debugs the phone sees the real cause on-screen.
                    // No raw HTTP detail on HER screen (it scared the grandma tester);
                    // the family diagnoses with Settings -> Test AI, which shows it all.
                    status.text = "మళ్ళీ ప్రయత్నించండి"
                    reply.text = "మీరు: $text\n\n${r.text}"
                    Announcer.get(this).say(r.text)
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
        if (text.isNullOrBlank()) {
            val msg = "వినిపించలేదు, మళ్ళీ చెప్పండి"
            status.text = msg
            Announcer.get(this).say(msg)   // she can't read the status line
        } else handleUtterance(text)
    }

    override fun onError(error: Int) {
        // "Didn't hear — say it again": SPOKEN, because a text-only status is
        // invisible to her (the grandma tester hit this exact dead-end).
        val msg = "వినిపించలేదు, మళ్ళీ చెప్పండి"
        status.text = msg
        Announcer.get(this).say(msg)
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
