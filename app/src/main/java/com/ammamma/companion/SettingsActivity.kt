package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var aiKey: EditText
    private lateinit var batteryMinutes: EditText
    private lateinit var battLow: EditText
    private lateinit var battCritical: EditText
    private lateinit var battCharged: EditText
    private lateinit var numbersContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        codeWord = findViewById(R.id.codeWord)
        aiKey = findViewById(R.id.aiKey)
        batteryMinutes = findViewById(R.id.batteryMinutes)
        battLow = findViewById(R.id.battLow)
        battCritical = findViewById(R.id.battCritical)
        battCharged = findViewById(R.id.battCharged)
        numbersContainer = findViewById(R.id.numbersContainer)

        // Pre-fill with whatever is saved.
        codeWord.setText(Settings.codeWordRaw(this))
        aiKey.setText(Settings.aiKey(this))
        batteryMinutes.setText(Settings.batteryReminderRaw(this))
        battLow.setText(Settings.batteryLowRaw(this))
        battCritical.setText(Settings.batteryCriticalRaw(this))
        battCharged.setText(Settings.batteryChargedRaw(this))

        // One row per saved number; a single empty row when none yet.
        val saved = Settings.familyNumbers(this)
        if (saved.isEmpty()) addNumberRow("") else saved.forEach { addNumberRow(it) }
        findViewById<Button>(R.id.addNumber).setOnClickListener { addNumberRow("") }

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        wireDemos()
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
            val n = Settings.batteryLowPercent(this)
            Announcer.get(this).announce("battery_low", "ఛార్జ్ $n శాతం ఉంది, దయచేసి ఛార్జ్ చేయండి")
            AlertActivity.show(this, "ఛార్జ్ $n%\nఛార్జ్ చేయండి", green = false)
        }
        findViewById<Button>(R.id.fullBatteryDemo).setOnClickListener {
            Announcer.get(this).announce("battery_full", "ఛార్జింగ్ నిండింది, ఛార్జర్ తీసేయండి")
            AlertActivity.show(this, "బ్యాటరీ నిండింది", green = true)
        }
        findViewById<Button>(R.id.chargingDemo).setOnClickListener {
            // The charging screen reads the sticky battery broadcast, so it shows
            // real values even when unplugged — no special demo mode needed.
            ChargingActivity.show(this)
        }
        findViewById<Button>(R.id.findPhoneDemo).setOnClickListener {
            FindPhoneActivity.show(this)
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

    private fun save() {
        Settings.save(
            this,
            codeWord.text.toString(),
            collectNumbers(),
            aiKey.text.toString(),
            batteryMinutes.text.toString()
        )
        Settings.saveBatteryLevels(
            this,
            battLow.text.toString(),
            battCritical.text.toString(),
            battCharged.text.toString()
        )
        Toast.makeText(this, "సేవ్ అయ్యింది · Saved", Toast.LENGTH_SHORT).show()

        // Find-my-phone + grandpa finder need: read incoming SMS, send the location
        // SMS back, and read GPS. Ask for whatever isn't granted yet.
        val needed = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION
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
        if (requestCode == REQ_SMS) finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_SMS = 201
    }
}
