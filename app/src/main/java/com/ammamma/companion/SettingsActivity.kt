package com.ammamma.companion

import android.Manifest
import android.app.Activity
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
    private lateinit var aiKey: EditText
    private lateinit var batteryMinutes: EditText
    private lateinit var battLow: EditText
    private lateinit var battCritical: EditText
    private lateinit var battCharged: EditText
    private lateinit var numbersContainer: LinearLayout
    private lateinit var callerSeconds: EditText
    private lateinit var callerTimes: EditText

    // Permissions section: header "all good" line, the rows container, and the
    // status icon for each row so onResume can refresh every tick in one pass.
    private lateinit var permsAllGood: TextView
    private lateinit var permsContainer: LinearLayout
    private val permRows = mutableListOf<Pair<PermItem, ImageView>>()

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
        callerSeconds = findViewById(R.id.callerSeconds)
        callerTimes = findViewById(R.id.callerTimes)
        permsAllGood = findViewById(R.id.permsAllGood)
        permsContainer = findViewById(R.id.permsContainer)

        // Pre-fill with whatever is saved.
        codeWord.setText(Settings.codeWordRaw(this))
        aiKey.setText(Settings.aiKey(this))
        batteryMinutes.setText(Settings.batteryReminderRaw(this))
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

        // One row per saved number; a single empty row when none yet.
        val saved = Settings.familyNumbers(this)
        if (saved.isEmpty()) addNumberRow("") else saved.forEach { addNumberRow(it) }
        findViewById<Button>(R.id.addNumber).setOnClickListener { addNumberRow("") }

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        wireDemos()
        buildPermissionRows()
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
        Settings.saveCallerSettings(
            this,
            callerSeconds.text.toString(),
            callerTimes.text.toString()
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
