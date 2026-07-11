package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The voice companion — a real chat, not a one-shot Q&A. Native pieces only, no
 * libraries (ponytail):
 *   - MIC: Android SpeechRecognizer (Telugu STT) -> text  [works on her real phone]
 *   - TYPE: a text box -> text  [works everywhere, incl. the emulator for testing]
 *   both feed the same brain (AiBrain + CommandRouter) and the same session memory
 *   (ChatStore), and every reply is shown as a bubble AND spoken (Announcer).
 *
 * HANDS-FREE LOOP: on open, she's greeted, then — once the greeting finishes
 * playing — listening starts on its own. After every reply finishes being spoken,
 * listening restarts on its own again. The recognizer is NEVER started while
 * Announcer.isSpeaking(): that is exactly how the phone used to hear its own voice
 * and parrot itself. Two silent/no-match turns in a row stop the loop gently (one
 * spoken line) rather than nagging forever; a mic tap always restarts it.
 */
class TalkActivity : Activity(), RecognitionListener {

    private lateinit var chatTitle: TextView
    private lateinit var status: TextView
    private lateinit var transcript: ListView
    private lateinit var input: EditText
    private lateinit var adapter: ChatAdapter
    private lateinit var session: ChatStore.Session

    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    // True only while this screen is actually on top — guards every recognizer/TTS
    // restart so nothing from a stale poll loop fires after she's left the screen.
    private var resumed = false
    // Whether the hands-free loop should keep re-listening after the next reply.
    // Turned off after two silent/no-match turns in a row; a mic tap turns it back on.
    private var loopActive = false
    private var noMatchStreak = 0
    // Greet ONCE per screen entry (and per new chat) — NOT on every onResume.
    // Coming back from the chat list, from a call the assistant placed, or from
    // YouTube must not replay "చెప్పమ్మా…" again and again.
    private var greeted = false
    // Was the hands-free loop running when we paused? Then coming back resumes
    // listening silently instead of re-greeting.
    private var resumeLoopOnReturn = false
    // Set just before startActivity() for a call/app/video. The covering screen's
    // onPause/onStop must NOT cut "…కి ఫోన్ చేస్తున్నాను" off mid-word — hearing the
    // name is her ONLY confirmation the right call is being placed. The Announcer is
    // app-scoped, so the line finishes playing over the dialer. That is desired.
    private var launchingAction = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_talk)

        chatTitle = findViewById(R.id.chatTitle)
        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        input = findViewById(R.id.input)

        session = loadInitialSession()
        adapter = ChatAdapter(this, session.messages)
        transcript.adapter = adapter
        chatTitle.text = displayTitle()

        findViewById<View>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.newChat).setOnClickListener { startNewChat() }
        findViewById<View>(R.id.chatsBtn).setOnClickListener { openChatList() }
        findViewById<View>(R.id.micButton).setOnClickListener { onMicTap() }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }

        // Old conversations she'll never come back to just clutter filesDir — prune
        // them once per open, off the main thread, if the family left this setting on.
        if (Settings.chatAutoDelete(this)) {
            bg.execute { ChatStore.pruneOlderThan(this, THIRTY_DAYS_MS) }
        }
    }

    private fun loadInitialSession(): ChatStore.Session {
        val id = intent.getStringExtra(ChatListActivity.EXTRA_SESSION_ID)
        return id?.let { ChatStore.load(this, it) } ?: ChatStore.newSession()
    }

    private fun displayTitle(): String = session.title.ifBlank { "మాట్లాడు" }

    // --- Lifecycle: leaving the screen silences everything instantly ---

    override fun onResume() {
        super.onResume()
        resumed = true
        launchingAction = false
        noMatchStreak = 0
        if (!greeted) {
            // Fresh entry to the screen: greet once, then the loop starts itself.
            greeted = true
            greetThenAutoListen()
        } else if (resumeLoopOnReturn) {
            // Back from the chat list / a placed call / YouTube with the loop
            // previously running: resume listening SILENTLY — no re-greeting.
            resumeLoopOnReturn = false
            waitUntilIdleThen { attemptAutoListen() }
        }
    }

    override fun onPause() {
        resumed = false
        resumeLoopOnReturn = loopActive
        loopActive = false
        recognizer?.cancel()
        // Leaving the screen silences everything instantly — EXCEPT when it's our
        // own action's screen (dialer/YouTube) covering us: that confirmation line
        // must finish playing (see launchingAction).
        if (!launchingAction) Announcer.get(this).stopSpeaking()
        super.onPause()
    }

    override fun onStop() {
        recognizer?.cancel()
        if (!launchingAction) Announcer.get(this).stopSpeaking()
        launchingAction = false
        super.onStop()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        bg.shutdown()
        super.onDestroy()
    }

    // --- Header actions ---

    private fun startNewChat() {
        recognizer?.cancel()
        Announcer.get(this).stopSpeaking()
        session = ChatStore.newSession()
        adapter = ChatAdapter(this, session.messages)
        transcript.adapter = adapter
        chatTitle.text = displayTitle()
        status.text = "నొక్కి మాట్లాడండి లేదా టైప్ చేయండి"
        noMatchStreak = 0
        greeted = true   // a new chat greets right here — onResume must not repeat it
        if (resumed) greetThenAutoListen()
    }

    private fun openChatList() {
        startActivityForResult(Intent(this, ChatListActivity::class.java), REQ_CHATS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CHATS && resultCode == RESULT_OK) {
            val id = data?.getStringExtra(ChatListActivity.EXTRA_SESSION_ID) ?: return
            val loaded = ChatStore.load(this, id) ?: return
            session = loaded
            adapter = ChatAdapter(this, session.messages)
            transcript.adapter = adapter
            chatTitle.text = displayTitle()
            scrollToBottom()
            // onResume() fires right after this; it resumes listening silently if
            // the loop was running — it does NOT greet again.
        }
    }

    // --- Hands-free loop ---

    private fun greetThenAutoListen() {
        // Telugu SCRIPT, not romanized — a te-IN TTS voice mangles Latin text.
        Announcer.get(this).announce("talk_greeting", "చెప్పమ్మా, ఏం కావాలి")
        waitUntilIdleThen { attemptAutoListen() }
    }

    /** Poll every 300ms until the voice is quiet, then run [then]. Never starts the
     *  recognizer while Announcer.isSpeaking() — that's how it used to hear itself. */
    private fun waitUntilIdleThen(then: () -> Unit) {
        main.postDelayed({
            if (!resumed) return@postDelayed
            if (Announcer.get(this).isSpeaking()) waitUntilIdleThen(then) else then()
        }, 300)
    }

    private fun attemptAutoListen() {
        if (!resumed) return
        val voice = Announcer.get(this)
        if (Settings.aiKey(this).isBlank()) {
            val msg = "ఇది మాట్లాడటానికి, ఇంట్లో వాళ్ళు ఒకసారి సిద్ధం చేయాలి"  // family must set it up once
            status.text = msg
            voice.say(msg)
            loopActive = false
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // No speech engine (e.g. this emulator). Speak the guidance — and do NOT
            // pop the keyboard: a keyboard reads as "you must type", which frightens
            // a non-typist.
            val msg = "మైక్ ఇక్కడ లేదు, ఇంట్లో వాళ్ళను అడగండి"  // no mic here, ask your family
            status.text = msg
            voice.say(msg)
            loopActive = false
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return   // onRequestPermissionsResult resumes the loop on grant
        }
        loopActive = true
        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                attemptAutoListen()
            } else {
                val msg = "మైక్‌కు అనుమతి లేదు, టైప్ చేయండి"
                status.text = msg
                Announcer.get(this).say(msg)
                loopActive = false
            }
        }
    }

    /** Manual mic tap — also the way she restarts the loop after it stopped gently. */
    private fun onMicTap() {
        val voice = Announcer.get(this)
        if (voice.isSpeaking()) voice.stopSpeaking()   // she's interrupting — let her
        noMatchStreak = 0
        loopActive = true
        attemptAutoListen()
    }

    private fun startListening() {
        if (!resumed) return
        if (Announcer.get(this).isSpeaking()) {
            // Guard again right before starting — never listen while it's talking.
            waitUntilIdleThen { startListening() }
            return
        }
        status.text = "వింటున్నాను…"
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }
        val rIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
        }
        recognizer?.startListening(rIntent)
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        hideKeyboard()
        noMatchStreak = 0
        loopActive = true
        handleUtterance(text)
    }

    /** She said (or typed) [text]; route it to a command or a warm Telugu reply. */
    private fun handleUtterance(text: String) {
        status.text = "ఆలోచిస్తోంది…"   // thinking
        val history = ChatStore.historyForApi(session)
        ChatStore.addMessage(session, "user", text)
        ChatStore.save(this, session)
        chatTitle.text = displayTitle()
        adapter.notifyDataSetChanged()
        scrollToBottom()
        bg.execute {
            // Weather questions still get today's facts folded in for a warm answer.
            val weather = if (Weather.isWeatherQuestion(text)) Weather.factsForBrain() else null
            val action = CommandRouter.resolve(this, text, history, weather)
            main.post { applyAction(action) }
        }
    }

    private fun applyAction(action: CommandRouter.Action) {
        val spoken = when (action) {
            is CommandRouter.Action.Say -> action.spoken
            is CommandRouter.Action.Call -> action.spoken
            is CommandRouter.Action.Launch -> action.spoken
        }
        // Save the reply into the session's memory even if she's since left the
        // screen — only the SPEAKING and any new action are gated on being resumed.
        ChatStore.addMessage(session, "assistant", spoken)
        ChatStore.save(this, session)
        if (!resumed) return

        status.text = ""
        adapter.notifyDataSetChanged()
        scrollToBottom()
        val voice = Announcer.get(this)
        voice.say(spoken)
        when (action) {
            is CommandRouter.Action.Call -> {
                // The dialer covering us fires onPause — launchingAction keeps it
                // from chopping "…కి ఫోన్ చేస్తున్నాను" mid-word (her only
                // confirmation the RIGHT call is being placed).
                launchingAction = true
                try {
                    startActivity(
                        Intent(Intent.ACTION_CALL, Uri.parse("tel:${action.number}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: SecurityException) {
                    launchingAction = false   // nothing is covering us after all
                    voice.say("ఫోన్ చేయడానికి అనుమతి లేదు")
                }
            }
            is CommandRouter.Action.Launch -> {
                launchingAction = true
                try { startActivity(action.intent) }
                catch (e: Exception) {
                    launchingAction = false
                    voice.say("అది తెరవడం కుదరలేదు")
                }
            }
            is CommandRouter.Action.Say -> {}
        }
        // After the reply finishes being SPOKEN, auto-listen again — the hands-free loop.
        waitUntilIdleThen { if (loopActive && resumed) startListening() }
    }

    private fun scrollToBottom() {
        transcript.post { if (adapter.count > 0) transcript.setSelection(adapter.count - 1) }
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
        if (text.isNullOrBlank()) handleNoMatch() else {
            noMatchStreak = 0
            handleUtterance(text)
        }
    }

    override fun onError(error: Int) {
        handleNoMatch()
    }

    /** Silence / no-match. Once: retry automatically. Twice in a row: stop the loop
     *  gently — one spoken line — and wait for a mic tap rather than nag forever. */
    private fun handleNoMatch() {
        if (!resumed) return
        noMatchStreak++
        val voice = Announcer.get(this)
        if (noMatchStreak >= 2) {
            loopActive = false
            val msg = "పర్వాలేదు, మీకు కావాలంటే మైక్ నొక్కండి"  // it's okay, tap the mic when you need it
            status.text = msg
            voice.say(msg)
        } else {
            val msg = "వినిపించలేదు, మళ్ళీ చెప్పండి"
            status.text = msg
            voice.say(msg)
            waitUntilIdleThen { if (loopActive && resumed) startListening() }
        }
    }

    override fun onEndOfSpeech() { status.text = "ఆలోచిస్తోంది…" }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        private const val REQ_MIC = 301
        private const val REQ_CHATS = 302
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
