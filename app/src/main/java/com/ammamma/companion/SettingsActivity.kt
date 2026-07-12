package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Family-only settings. Grandma never needs this; the gear on the home screen
 * opens it for whoever set up the phone.
 *
 * Family numbers are a list of rows (number + remove button) with a big
 * "add number" button — no "one per line" typing rules to remember. Rows are
 * plain views in a LinearLayout: for a handful of numbers that is simpler and
 * more reliable than a RecyclerView.
 *
 * Saving also asks for SMS permission if needed, so find-my-phone actually works.
 */
class SettingsActivity : Activity() {

    private lateinit var codeWord: EditText
    private lateinit var keysContainer: LinearLayout
    private lateinit var batteryMinutes: EditText
    private lateinit var battLow: EditText
    private lateinit var battCritical: EditText
    private lateinit var battCharged: EditText
    private lateinit var numbersContainer: LinearLayout
    private lateinit var callerSeconds: EditText
    private lateinit var callerTimes: EditText
    private lateinit var travelNumbersContainer: LinearLayout

    // Voice (v1.1): dynamic radio rows, built in code like the AI-key/number
    // rows — one per real Telugu voice, plus "Automatic" first. Kept manually
    // exclusive (see wireVoiceSection) since a ▶ audition button sits beside
    // each radio in the same row.
    private lateinit var voicesContainer: LinearLayout
    private val voiceRadios = mutableListOf<RadioButton>()

    // Day clock time fields + grandpa-finder number (all persisted in save()).
    private lateinit var quietStart: EditText
    private lateinit var quietEnd: EditText
    private lateinit var heartbeatHour: EditText
    private lateinit var heartbeatMinute: EditText
    private lateinit var alarmHour: EditText
    private lateinit var alarmMinute: EditText
    private lateinit var herNumber: EditText

    // Permissions section: header "all good" line, the rows container, and the
    // status icon for each row so onResume can refresh every tick in one pass.
    private lateinit var permsAllGood: TextView
    private lateinit var permsContainer: LinearLayout
    private val permRows = mutableListOf<Pair<PermItem, ImageView>>()

    // One AI key per row: the key field, the model it should use ("" = Auto/best),
    // and the little label under it showing "Provider · Model".
    private class KeyRow(val view: View, val keyEdit: EditText, val label: TextView) {
        var model: String = ""
    }
    private val keyRows = mutableListOf<KeyRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        codeWord = findViewById(R.id.codeWord)
        keysContainer = findViewById(R.id.keysContainer)
        batteryMinutes = findViewById(R.id.batteryMinutes)
        battLow = findViewById(R.id.battLow)
        battCritical = findViewById(R.id.battCritical)
        battCharged = findViewById(R.id.battCharged)
        numbersContainer = findViewById(R.id.numbersContainer)
        callerSeconds = findViewById(R.id.callerSeconds)
        callerTimes = findViewById(R.id.callerTimes)
        permsAllGood = findViewById(R.id.permsAllGood)
        permsContainer = findViewById(R.id.permsContainer)

        // Pre-fill with whatever is saved.
        codeWord.setText(Settings.codeWordRaw(this))
        batteryMinutes.setText(Settings.batteryReminderRaw(this))

        // AI keys: one row per saved key (a single empty row when none yet).
        val savedKeys = Settings.aiAccounts(this)
        if (savedKeys.isEmpty()) addKeyRow("", "") else savedKeys.forEach { addKeyRow(it.key, it.model) }
        findViewById<Button>(R.id.addKey).setOnClickListener { addKeyRow("", "") }
        battLow.setText(Settings.batteryLowRaw(this))
        battCritical.setText(Settings.batteryCriticalRaw(this))
        battCharged.setText(Settings.batteryChargedRaw(this))
        callerSeconds.setText(Settings.callerRepeatSecondsRaw(this))
        callerTimes.setText(Settings.callerMaxRepeatsRaw(this))

        // Location SMS reply: a plain switch that saves the moment it is flipped,
        // so the family sees the effect without hunting for the Save button.
        findViewById<Switch>(R.id.locationReplySwitch).apply {
            isChecked = Settings.locationReplySmsEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Settings.setLocationReplySms(this@SettingsActivity, on)
            }
        }

        // Travel mode: saves the moment it is flipped, like the SMS-reply switch.
        findViewById<Switch>(R.id.travelSwitch).apply {
            isChecked = Settings.travelModeEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Settings.setTravelMode(this@SettingsActivity, on)
            }
        }

        // Travel numbers: separate list from family numbers, same row pattern.
        travelNumbersContainer = findViewById(R.id.travelNumbersContainer)
        val savedTravel = Settings.travelNumbers(this)
        if (savedTravel.isEmpty()) addTravelNumberRow("") else savedTravel.forEach { addTravelNumberRow(it) }
        findViewById<Button>(R.id.addTravelNumber).setOnClickListener { addTravelNumberRow("") }

        // Travel channels: SMS is live; email is disabled ("coming soon" — see
        // TRAVEL_EMAIL.md), so it's shown but never wired to a listener.
        findViewById<Switch>(R.id.travelSmsSwitch).apply {
            isChecked = Settings.travelSmsEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Settings.setTravelSmsEnabled(this@SettingsActivity, on)
            }
        }
        findViewById<Switch>(R.id.travelEmailSwitch).isChecked = Settings.travelEmailEnabled(this)

        // One row per saved number; a single empty row when none yet.
        val saved = Settings.familyNumbers(this)
        if (saved.isEmpty()) addNumberRow("") else saved.forEach { addNumberRow(it) }
        findViewById<Button>(R.id.addNumber).setOnClickListener { addNumberRow("") }

        wireSoundsSection()
        wireVoiceSection()
        wireDayClockSection()
        wireFinderSection()

        // Recorder Studio: the family records real voices for every spoken line.
        findViewById<Button>(R.id.recorderStudio).setOnClickListener {
            startActivity(Intent(this, RecorderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            })
        }

        // Family manager: link who's really family, and their SOS/SMS/video flags.
        findViewById<Button>(R.id.familyManager).setOnClickListener {
            startActivity(Intent(this, FamilyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            })
        }

        // Home edit lock: locked = grandma can't accidentally change people.
        findViewById<Switch>(R.id.editLockSwitch).apply {
            isChecked = Settings.editLocked(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setEditLocked(this@SettingsActivity, on) }
        }

        // Chat auto-delete: drop chat sessions untouched 30+ days.
        findViewById<Switch>(R.id.chatAutoDeleteSwitch).apply {
            isChecked = Settings.chatAutoDelete(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setChatAutoDelete(this@SettingsActivity, on) }
        }

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.testAi).setOnClickListener { testAi() }
        wireDemos()
        buildPermissionRows()
    }

    /** Sounds section: volume, master mute, and the per-category speech toggles.
     *  Every control applies immediately (no Save button needed), like the other
     *  plain switches on this screen. */
    private fun wireSoundsSection() {
        findViewById<Switch>(R.id.muteSwitch).apply {
            isChecked = Settings.voiceMuted(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setVoiceMuted(this@SettingsActivity, on) }
        }
        findViewById<Switch>(R.id.greetingSwitch).apply {
            isChecked = Settings.greetingEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setGreetingEnabled(this@SettingsActivity, on) }
        }
        findViewById<Switch>(R.id.uiSoundsSwitch).apply {
            isChecked = Settings.uiSoundsEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setUiSoundsEnabled(this@SettingsActivity, on) }
        }
        findViewById<Switch>(R.id.chargeSoundsSwitch).apply {
            isChecked = Settings.chargingAnnouncementsEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setChargingAnnouncementsEnabled(this@SettingsActivity, on) }
        }

        val volumeSeek = findViewById<SeekBar>(R.id.volumeSeek)
        val volumeLabel = findViewById<TextView>(R.id.volumeLabel)
        val startPct = Settings.speechVolumePercent(this)
        volumeSeek.progress = startPct
        volumeLabel.text = "$startPct%"
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                volumeLabel.text = "$value%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Settings.setSpeechVolumePercent(this@SettingsActivity, seekBar?.progress ?: startPct)
            }
        })

        // Alert repeat window (seconds, 0 = say once). Same pattern as the volume bar.
        val repeatSeek = findViewById<SeekBar>(R.id.alertRepeatSeek)
        val repeatLabel = findViewById<TextView>(R.id.alertRepeatLabel)
        fun repeatText(v: Int) = if (v == 0) "0 — once only" else "$v s"
        val startSecs = Settings.alertRepeatSeconds(this)
        repeatSeek.progress = startSecs
        repeatLabel.text = repeatText(startSecs)
        repeatSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                repeatLabel.text = repeatText(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Settings.setAlertRepeatSeconds(this@SettingsActivity, seekBar?.progress ?: startSecs)
            }
        })
    }

    /**
     * Voice section: "Automatic" + one row per real Telugu voice the engine
     * offers (Announcer.availableTeluguVoices — empty on the emulator, which
     * has no Telugu TTS data). Rate/pitch sliders match the volume/alert-repeat
     * pattern above. Every change saves immediately, applies live, and speaks
     * a short confirmation — same "no Save button needed" feel as the switches.
     */
    private fun wireVoiceSection() {
        voicesContainer = findViewById(R.id.voicesContainer)
        val savedVoiceName = Settings.ttsVoiceName(this)
        val voices = Announcer.get(this).availableTeluguVoices()

        addVoiceRow(
            "స్వయంచాలకం (ఉత్తమ తెలుగు గొంతు) · Automatic (best Telugu voice)",
            voice = null,
            checked = savedVoiceName.isEmpty()
        )

        if (voices.isEmpty()) {
            // Emulator / an engine with no Telugu voice data installed: nothing
            // to list, but "More voices" below still lets the family fetch some.
            voicesContainer.addView(TextView(this).apply {
                text = "ఈ ఫోన్‌లో తెలుగు గొంతులు కనబడలేదు · No Telugu voices found on this device"
                setTextColor(Color.parseColor("#8A7361"))
                textSize = 13f
                setPadding(0, dp(6), 0, dp(6))
            })
        } else {
            voices.forEachIndexed { index, voice ->
                val quality = when {
                    voice.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "very high quality"
                    voice.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "high quality"
                    voice.quality >= android.speech.tts.Voice.QUALITY_NORMAL -> "normal quality"
                    else -> "low quality"
                }
                val network = if (voice.isNetworkConnectionRequired) " (needs internet)" else ""
                addVoiceRow(
                    "Voice ${index + 1} — $quality$network",
                    voice = voice,
                    checked = savedVoiceName == voice.name
                )
            }
        }

        val rateSeek = findViewById<SeekBar>(R.id.rateSeek)
        val rateLabel = findViewById<TextView>(R.id.rateLabel)
        val startRate = Settings.ttsRatePercent(this)
        rateSeek.progress = startRate
        rateLabel.text = "$startRate%"
        rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                rateLabel.text = "$value%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Settings.setTtsRatePercent(this@SettingsActivity, seekBar?.progress ?: startRate)
                confirmVoiceChange()
            }
        })

        val pitchSeek = findViewById<SeekBar>(R.id.pitchSeek)
        val pitchLabel = findViewById<TextView>(R.id.pitchLabel)
        val startPitch = Settings.ttsPitchPercent(this)
        pitchSeek.progress = startPitch
        pitchLabel.text = "$startPitch%"
        pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                pitchLabel.text = "$value%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Settings.setTtsPitchPercent(this@SettingsActivity, seekBar?.progress ?: startPitch)
                confirmVoiceChange()
            }
        })

        // Lets the family install/upgrade Google's Telugu TTS data — guarded
        // since not every device/ROM ships this settings screen.
        findViewById<Button>(R.id.ttsSystemSettings).setOnClickListener {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "TTS సెట్టింగ్‌లు దొరకలేదు · TTS settings not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** One voice row: a RadioButton + a ▶ button that PREVIEWS the voice
     *  without saving anything (Announcer.audition). [voice] null = the
     *  "Automatic" row. Exclusivity is kept by hand (not a RadioGroup) so the
     *  ▶ button can share the row with the radio — see the checked listener. */
    private fun addVoiceRow(label: String, voice: android.speech.tts.Voice?, checked: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val radio = RadioButton(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#402A1C"))
            isChecked = checked
        }
        // Listener added AFTER isChecked above, so building the initial rows
        // never saves a pref or speaks a confirmation — only a real user tap does.
        radio.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) return@setOnCheckedChangeListener
            voiceRadios.filter { it !== radio }.forEach { it.isChecked = false }
            Settings.setTtsVoiceName(this, voice?.name.orEmpty())
            confirmVoiceChange()
        }
        val audition = Button(this).apply {
            text = "▶"
            textSize = 14f
            setOnClickListener {
                Announcer.get(this@SettingsActivity).audition(
                    voice,
                    Settings.ttsRatePercent(this@SettingsActivity),
                    Settings.ttsPitchPercent(this@SettingsActivity)
                )
            }
        }
        row.addView(radio, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(audition, LinearLayout.LayoutParams(dp(48), dp(48)))
        voicesContainer.addView(row)
        voiceRadios.add(radio)
    }

    /** After a voice/rate/pitch change: apply it live, then speak a short
     *  Telugu confirmation IN THE NEW VOICE, so the family hears it land. */
    private fun confirmVoiceChange() {
        Announcer.get(this).applyVoiceSettings(this)
        Announcer.get(this).say("ఇలా ఉంటుంది కొత్త గొంతు")
    }

    /** Day clock: switches apply immediately (like the sounds switches); the
     *  numeric hour/minute fields are persisted in save(), like the battery ones. */
    private fun wireDayClockSection() {
        quietStart = findViewById(R.id.quietStart)
        quietEnd = findViewById(R.id.quietEnd)
        heartbeatHour = findViewById(R.id.heartbeatHour)
        heartbeatMinute = findViewById(R.id.heartbeatMinute)
        alarmHour = findViewById(R.id.alarmHour)
        alarmMinute = findViewById(R.id.alarmMinute)

        quietStart.setText(Settings.quietStartHour(this).toString())
        quietEnd.setText(Settings.quietEndHour(this).toString())
        heartbeatHour.setText(Settings.heartbeatHour(this).toString())
        heartbeatMinute.setText(Settings.heartbeatMinute(this).toString())
        alarmHour.setText(Settings.alarmHour(this).toString())
        alarmMinute.setText(Settings.alarmMinute(this).toString())

        findViewById<Switch>(R.id.chimesSwitch).apply {
            isChecked = Settings.chimesEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setChimesEnabled(this@SettingsActivity, on) }
        }
        findViewById<Switch>(R.id.heartbeatSwitch).apply {
            isChecked = Settings.heartbeatEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setHeartbeatEnabled(this@SettingsActivity, on) }
        }
        findViewById<Switch>(R.id.alarmSwitch).apply {
            isChecked = Settings.alarmEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Settings.setAlarmEnabled(this@SettingsActivity, on) }
        }
    }

    /** Grandpa finder: the role switch applies immediately; her number is a
     *  numeric field persisted in save(). The button shows the finder screen. */
    private fun wireFinderSection() {
        herNumber = findViewById(R.id.herNumber)
        herNumber.setText(Settings.herNumber(this))

        findViewById<Switch>(R.id.finderRoleSwitch).apply {
            isChecked = Settings.finderRole(this@SettingsActivity) == "finder"
            setOnCheckedChangeListener { _, on ->
                Settings.setFinderRole(this@SettingsActivity, if (on) "finder" else "")
            }
        }
        findViewById<Button>(R.id.openFinder).setOnClickListener {
            // Persist the number first, so the finder screen sees what's typed here.
            Settings.setHerNumber(this, herNumber.text.toString())
            startActivity(Intent(this, FinderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            })
        }
    }

    // --- AI: one row per key. Each row has a "Get models ▾" that lets the family
    //     choose "Auto (best)" or a specific model. A live "Test AI" diagnoses a
    //     wrong key or dead model HERE, not as a mystery "busy" during a chat. ---

    /** Persist the on-screen key rows so buttons test the screen, not stale prefs. */
    private fun saveAiAccounts() {
        Settings.saveAiAccounts(this, collectAccounts())
        AiBrain.forgetAutoModel()   // provider/key/model may have changed — re-pick fresh
    }

    private fun collectAccounts(): List<AiAccount> =
        keyRows.mapNotNull { row ->
            val k = row.keyEdit.text.toString().trim()
            if (k.isEmpty()) null else AiAccount(k, row.model)
        }

    /** Build one key row: [key field] [Get models ▾] [✕] with a "Provider · Model" label. */
    private fun addKeyRow(prefillKey: String, prefillModel: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val keyEdit = EditText(this).apply {
            hint = "API key (gsk_… / sk-or-… / sk-…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(prefillKey)
            setTextColor(Color.parseColor("#402A1C"))
            textSize = 14f
        }
        val label = TextView(this).apply {
            setTextColor(Color.parseColor("#8A7361"))
            textSize = 13f
            setPadding(dp(2), dp(2), 0, 0)
        }
        val row = KeyRow(col, keyEdit, label).apply { model = prefillModel.trim() }

        val getModels = Button(this).apply {
            text = "Get models ▾"
            textSize = 12f
            setOnClickListener { fetchModelsForRow(row) }
        }
        val remove = Button(this).apply {
            text = "✕"
            textSize = 18f
            setOnClickListener {
                keysContainer.removeView(col)
                keyRows.remove(row)
            }
        }
        // Keep the "Groq · …" label truthful as the key is typed/pasted.
        keyEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updateRowLabel(row)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        top.addView(keyEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(getModels, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        top.addView(remove, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(top)
        col.addView(label)
        keysContainer.addView(col)
        keyRows.add(row)
        updateRowLabel(row)
    }

    private fun updateRowLabel(row: KeyRow) {
        val provider = Settings.providerLabel(row.keyEdit.text.toString())
        val modelLabel = if (row.model.isBlank()) "Auto (best model)" else row.model
        row.label.text = when {
            provider.isEmpty() -> "Paste a key — Groq (gsk_…) recommended"
            else -> "$provider · $modelLabel"
        }
    }

    private fun fetchModelsForRow(row: KeyRow) {
        val key = row.keyEdit.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Paste the key first", Toast.LENGTH_SHORT).show()
            return
        }
        saveAiAccounts()
        Toast.makeText(this, "Fetching models…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = AiBrain.fetchModels(key)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (!r.ok) {
                    showAiDialog("Could not get models", r.error)
                    return@runOnUiThread
                }
                // First choice is always Auto; the rest are the live model list (best first).
                val display = arrayOf("✨ Auto — best model (recommended)") + r.ids.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Model for this key (${r.ids.size})")
                    .setItems(display) { _, which ->
                        row.model = if (which == 0) "" else r.ids[which - 1]
                        updateRowLabel(row)
                        saveAiAccounts()
                        val what = if (which == 0) "Auto (best)" else r.ids[which - 1]
                        Toast.makeText(this, "Set: $what", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun testAi() {
        saveAiAccounts()
        Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = AiBrain.ask(this, "నమస్తే")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (r.ok) showAiDialog("✅ AI works", r.text)
                else showAiDialog("❌ AI failed", "${r.text}\n\n${r.detail}")
            }
        }.start()
    }

    private fun showAiDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /** Returning from a system settings screen must update the ticks instantly. */
    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    // --- Demos: every alert on demand, so the family can SEE the app work ---
    private fun wireDemos() {
        findViewById<Button>(R.id.callerDemo).setOnClickListener {
            val who = Contacts.load(this).firstOrNull()?.name ?: "కూతురు"
            Announcer.get(this).announce("caller_demo", "$who ఫోన్ చేస్తున్నారు")
        }
        findViewById<Button>(R.id.lowBatteryDemo).setOnClickListener {
            // Demo speaks the CURRENTLY CONFIGURED threshold, so tuning is audible.
            // (No repeating reminder here — it would read the real battery and stop.)
            // repeatText + important mirror the REAL low-battery path, so the family
            // hears the alert-repeat window exactly as she would.
            val n = Settings.batteryLowPercent(this)
            val line = "ఛార్జ్ $n శాతం ఉంది, దయచేసి ఛార్జ్ చేయండి"
            Announcer.get(this).announce("battery_low", line, important = true)
            AlertActivity.show(this, "ఛార్జ్ $n%\nఛార్జ్ చేయండి", green = false,
                repeatText = line, important = true)
        }
        findViewById<Button>(R.id.fullBatteryDemo).setOnClickListener {
            val line = "ఛార్జింగ్ నిండింది, ఛార్జర్ తీసేయండి"
            Announcer.get(this).announce("battery_full", line)
            AlertActivity.show(this, "బ్యాటరీ నిండింది", green = true, repeatText = line)
        }
        findViewById<Button>(R.id.chargingDemo).setOnClickListener {
            // The charging screen reads the sticky battery broadcast, so it shows
            // real values even when unplugged — no special demo mode needed.
            ChargingActivity.show(this)
        }
        findViewById<Button>(R.id.findPhoneDemo).setOnClickListener {
            FindPhoneActivity.show(this)
        }
        findViewById<Button>(R.id.chimeDemo).setOnClickListener {
            // Mirrors the real hourly-chime path: the clip key is THIS hour's, so
            // the family hears exactly what she would hear right now.
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val display = if (hour % 12 == 0) 12 else hour % 12
            Announcer.get(this).announce("hour_$hour", "సమయం $display గంటలు")
        }
        findViewById<Button>(R.id.heartbeatDemo).setOnClickListener {
            Announcer.get(this).announce("goodmorning", "శుభోదయం అమ్మమ్మ")
        }
        findViewById<Button>(R.id.alarmDemo).setOnClickListener {
            // Mirrors the real talking-alarm path: spoken line + full-screen card.
            val text = "అమ్మమ్మ, సమయం అయింది"
            Announcer.get(this).announce("alarm", text, important = true)
            AlertActivity.show(this, text, green = true, repeatText = text, important = true)
        }
    }

    // --- Family number rows ---
    private fun addNumberRow(prefill: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "+91 98xxxxxxxx"
            setText(prefill)
            setTextColor(Color.parseColor("#402A1C"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "✕"
            textSize = 18f
            setOnClickListener { numbersContainer.removeView(row) }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        numbersContainer.addView(row)
    }

    /** Collect the rows back into the same comma-separated pref format. */
    private fun collectNumbers(): String {
        val numbers = mutableListOf<String>()
        for (i in 0 until numbersContainer.childCount) {
            val row = numbersContainer.getChildAt(i) as? LinearLayout ?: continue
            val edit = row.getChildAt(0) as? EditText ?: continue
            val n = edit.text.toString().filter { it.isDigit() || it == '+' }
            if (n.length >= 4) numbers.add(n)
        }
        return numbers.joinToString(",")
    }

    // --- Travel-number rows: same pattern as the family-number rows above, but a
    //     separate list (Settings.travelNumbers) and container. ---
    private fun addTravelNumberRow(prefill: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "+91 98xxxxxxxx"
            setText(prefill)
            setTextColor(Color.parseColor("#402A1C"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "✕"
            textSize = 18f
            setOnClickListener { travelNumbersContainer.removeView(row) }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        travelNumbersContainer.addView(row)
    }

    private fun collectTravelNumbers(): String {
        val numbers = mutableListOf<String>()
        for (i in 0 until travelNumbersContainer.childCount) {
            val row = travelNumbersContainer.getChildAt(i) as? LinearLayout ?: continue
            val edit = row.getChildAt(0) as? EditText ?: continue
            val n = edit.text.toString().filter { it.isDigit() || it == '+' }
            if (n.length >= 4) numbers.add(n)
        }
        return numbers.joinToString(",")
    }

    private fun save() {
        Settings.save(
            this,
            codeWord.text.toString(),
            collectNumbers(),
            collectAccounts().firstOrNull()?.key.orEmpty(),
            batteryMinutes.text.toString()
        )
        saveAiAccounts()
        Settings.saveBatteryLevels(
            this,
            battLow.text.toString(),
            battCritical.text.toString(),
            battCharged.text.toString()
        )
        Settings.saveCallerSettings(
            this,
            callerSeconds.text.toString(),
            callerTimes.text.toString()
        )
        Settings.saveTravelNumbers(this, collectTravelNumbers())

        // Day clock times + finder number. Blank or junk falls back to the saved
        // value (Settings clamps ranges), so a half-cleared field can't break the clock.
        fun num(edit: EditText, fallback: Int) = edit.text.toString().trim().toIntOrNull() ?: fallback
        Settings.setQuietHours(
            this,
            num(quietStart, Settings.quietStartHour(this)),
            num(quietEnd, Settings.quietEndHour(this))
        )
        Settings.setHeartbeatTime(
            this,
            num(heartbeatHour, Settings.heartbeatHour(this)),
            num(heartbeatMinute, Settings.heartbeatMinute(this))
        )
        Settings.setAlarmTime(
            this,
            num(alarmHour, Settings.alarmHour(this)),
            num(alarmMinute, Settings.alarmMinute(this))
        )
        Settings.setHerNumber(this, herNumber.text.toString())
        // Times may have just changed — re-arm the day-clock alarms immediately.
        DayScheduler.scheduleAll(this)

        Toast.makeText(this, "సేవ్ అయ్యింది · Saved", Toast.LENGTH_SHORT).show()

        // Find-my-phone + grandpa finder need: read incoming SMS, send the location
        // SMS back, and read GPS. Ask for whatever isn't granted yet.
        val needed = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_SMS)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_SMS) {
            finish()
            return
        }
        if (requestCode == REQ_PERM_ROW) {
            // If the system silently denied (the "never ask again" state returns
            // DENIED with no dialog), send the family to the app-info screen where
            // the toggle can still be flipped by hand. Refresh the ticks either way.
            val stillDenied = grantResults.isEmpty() ||
                grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (stillDenied) openAppDetails()
            refreshPermissions()
        }
    }

    // ---------------------------------------------------------------------------
    // Permissions section
    // ---------------------------------------------------------------------------

    /**
     * One tappable row per permission the app needs. Runtime permissions are
     * requested right here on tap; battery/overlay open their system screens.
     * Rows are built once; refreshPermissions() only swaps the tick icons.
     */
    private data class PermItem(
        val label: String,
        val isGranted: () -> Boolean,
        val onFix: () -> Unit
    )

    private fun permissionItems(): List<PermItem> = listOf(
        PermItem(
            "కాల్స్ · Calls",
            { granted(Manifest.permission.CALL_PHONE) },
            { requestGroup(Manifest.permission.CALL_PHONE) }
        ),
        // Caller identity needs BOTH the phone state AND the call log: on Android 9+
        // the call log is gated separately, and without it callers show as "unknown".
        PermItem(
            "కాలర్ గుర్తింపు · Caller identity",
            { granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.READ_CALL_LOG) },
            { requestGroup(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG) }
        ),
        PermItem(
            "కాంటాక్ట్‌లు · Contacts",
            { granted(Manifest.permission.READ_CONTACTS) },
            { requestGroup(Manifest.permission.READ_CONTACTS) }
        ),
        PermItem(
            "SMS స్వీకరణ · SMS receive",
            { granted(Manifest.permission.RECEIVE_SMS) },
            { requestGroup(Manifest.permission.RECEIVE_SMS) }
        ),
        PermItem(
            "SMS పంపడం · SMS send",
            { granted(Manifest.permission.SEND_SMS) },
            { requestGroup(Manifest.permission.SEND_SMS) }
        ),
        PermItem(
            "లొకేషన్ · Location",
            { granted(Manifest.permission.ACCESS_FINE_LOCATION) },
            { requestGroup(Manifest.permission.ACCESS_FINE_LOCATION) }
        ),
        PermItem(
            "మైక్రోఫోన్ · Microphone",
            { granted(Manifest.permission.RECORD_AUDIO) },
            { requestGroup(Manifest.permission.RECORD_AUDIO) }
        ),
        // Theft guard photographs whoever handles the phone in Travel mode.
        PermItem(
            "కెమెరా · Camera",
            { granted(Manifest.permission.CAMERA) },
            { requestGroup(Manifest.permission.CAMERA) }
        ),
        PermItem(
            "బ్యాటరీ నియంత్రణ లేదు · Battery unrestricted",
            { batteryUnrestricted() },
            { openBatterySettings() }
        ),
        PermItem(
            "యాప్‌ల పైన చూపడం · Display over apps",
            { android.provider.Settings.canDrawOverlays(this) },
            { openOverlaySettings() }
        )
    )

    private fun buildPermissionRows() {
        permRows.clear()
        permsContainer.removeAllViews()
        val divider = Color.parseColor("#EADFD0")
        permissionItems().forEachIndexed { index, item ->
            if (index > 0) {
                permsContainer.addView(View(this).apply {
                    setBackgroundColor(divider)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                // Tapping a row (re-)requests or opens the right settings screen.
                setOnClickListener { item.onFix() }
            }
            row.addView(TextView(this).apply {
                text = item.label
                setTextColor(Color.parseColor("#402A1C"))
                textSize = 16f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val status = ImageView(this)
            row.addView(status, LinearLayout.LayoutParams(dp(28), dp(28)))
            permsContainer.addView(row)
            permRows.add(item to status)
        }
        refreshPermissions()
    }

    /** Swap every row's tick; collapse the list into one green line when all done. */
    private fun refreshPermissions() {
        if (permRows.isEmpty()) return
        var allGood = true
        for ((item, status) in permRows) {
            val ok = item.isGranted()
            if (!ok) allGood = false
            status.setImageResource(if (ok) R.drawable.ic_perm_ok else R.drawable.ic_perm_missing)
        }
        permsContainer.visibility = if (allGood) View.GONE else View.VISIBLE
        permsAllGood.visibility = if (allGood) View.VISIBLE else View.GONE
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Re-asking on an explicit tap is correct UX, even if the OS just no-ops it. */
    private fun requestGroup(vararg permissions: String) {
        requestPermissions(permissions, REQ_PERM_ROW)
    }

    private fun batteryUnrestricted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatterySettings() {
        // The direct "please exempt me" dialog; falls back to the settings list.
        try {
            startActivity(Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ))
    }

    private fun openAppDetails() {
        startActivity(Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_SMS = 201
        private const val REQ_PERM_ROW = 202
    }
}
