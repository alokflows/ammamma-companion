package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ammamma's home. Builds the photo-dial grid from [Contacts], keeps a live clock,
 * and opens the voice companion. Tapping a face places a real call.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private var pendingNumber: String? = null

    // repeating tick that keeps the clock current
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            ui.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make sure the always-alive companion service is running.
        startForegroundService(Intent(this, CompanionService::class.java))

        // First run: ask for everything the companion needs, up front, in one go.
        requestStartupPermissions()

        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        buildFaceGrid()

        findViewById<View>(R.id.talkButton).setOnClickListener {
            startActivity(Intent(this, TalkActivity::class.java))
        }

        // Settings is family-only. A casual tap does nothing (a gentle Telugu hint);
        // it opens only on a long-press, so Ammamma can't wander into the technical /
        // API-key screen and get scared. (Kamala's #3.)
        val gear = findViewById<View>(R.id.settings)
        gear.setOnClickListener {
            Toast.makeText(this, "కుటుంబం కోసం — నొక్కి పట్టుకోండి", Toast.LENGTH_SHORT).show()
        }
        gear.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
    }

    override fun onResume() {
        super.onResume()
        clockTick.run()  // start ticking
    }

    override fun onPause() {
        ui.removeCallbacks(clockTick)
        super.onPause()
    }

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now) +
            SimpleDateFormat(" a", Locale.ENGLISH).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", Locale("te", "IN")).format(now)
    }

    /** Inflate one face card per contact into the 2-column grid. */
    private fun buildFaceGrid() {
        val grid = findViewById<GridLayout>(R.id.grid)
        grid.removeAllViews()   // rebuilt after every edit
        val inflater = LayoutInflater.from(this)
        val gap = dp(7)

        Contacts.load(this).forEachIndexed { index, contact ->
            val card = inflater.inflate(R.layout.item_face, grid, false)

            card.findViewById<TextView>(R.id.name).text = contact.name
            card.findViewById<TextView>(R.id.rel).text = contact.english

            // per-person colored ring around the avatar
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F3E7D3"))
                setStroke(dp(4), contact.ringColor)
            }
            card.findViewById<FrameLayout>(R.id.avatarFrame).background = ring

            val hasNumber = contact.number.isNotBlank()
            // A card with no number yet LOOKS different: dimmed, and no green call badge —
            // so Ammamma never taps it expecting a call. (Kamala's #1 confusion.)
            card.findViewById<View>(R.id.callBadge).visibility =
                if (hasNumber) View.VISIBLE else View.INVISIBLE
            card.findViewById<View>(R.id.noNumber).visibility =
                if (hasNumber) View.GONE else View.VISIBLE
            card.alpha = if (hasNumber) 1f else 0.6f

            // Short tap: call if we can. If there's no number, DON'T drop her into an
            // English form — just gently say (in Telugu) to ask the family. Editing
            // stays on long-press, for family.
            card.setOnClickListener {
                if (hasNumber) {
                    call(contact.number)
                } else {
                    Toast.makeText(this, "నంబర్ లేదు — కుటుంబాన్ని అడగండి", Toast.LENGTH_SHORT).show()
                    Announcer.get(this).say("nambaru ledu")
                }
            }
            // Long press = edit name/number (for family).
            card.setOnLongClickListener { showEditDialog(index, contact); true }

            // two equal columns, each cell with a little margin
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(gap, gap, gap, gap)
            }
            grid.addView(card, lp)
        }
    }

    /** Family editor for one face: change the Telugu name, label, and phone number. */
    private fun showEditDialog(index: Int, contact: Contact) {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        val nameEt = EditText(this).apply { setText(contact.name); hint = "పేరు / Name (Telugu)" }
        val engEt = EditText(this).apply { setText(contact.english); hint = "Label (English)" }
        val numEt = EditText(this).apply {
            setText(contact.number); hint = "Phone number"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        box.addView(nameEt); box.addView(engEt); box.addView(numEt)

        AlertDialog.Builder(this)
            .setTitle("పేరు · నంబర్ మార్చండి")   // change name / number
            .setView(box)
            .setPositiveButton("సేవ్") { _, _ ->
                Contacts.update(this, index, nameEt.text.toString(), engEt.text.toString(), numEt.text.toString())
                buildFaceGrid()
            }
            .setNegativeButton("రద్దు", null)
            .show()
    }

    /**
     * Place the call. We use ACTION_CALL so it's ONE tap for her — no dialer to
     * confront. If the call permission isn't granted yet we ask once; if she (or
     * ColorOS) denies it, we fall back to the normal dialer so nothing ever dead-ends.
     */
    private fun call(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        } else {
            pendingNumber = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
        }
    }

    /** Everything the companion needs to work + announce, requested together on launch. */
    private fun requestStartupPermissions() {
        val needed = STARTUP_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_STARTUP)
        } else {
            askToRunPersistently()
        }
    }

    /** Ask ColorOS/Android to stop killing us for battery — so the companion stays alive. */
    private fun askToRunPersistently() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_STARTUP) {
            // Whatever they granted, next nudge Android to let us run persistently.
            askToRunPersistently()
            return
        }
        if (requestCode == REQ_CALL) {
            val number = pendingNumber
            pendingNumber = null
            if (number == null) return
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
            } else {
                // Denied: open the dialer pre-filled so she can still call.
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CALL = 101
        private const val REQ_STARTUP = 103

        // All the runtime permissions the companion needs, asked together on first run.
        private val STARTUP_PERMISSIONS = arrayOf(
            Manifest.permission.CALL_PHONE,          // one-tap calling
            Manifest.permission.READ_PHONE_STATE,    // announce who's calling
            Manifest.permission.RECEIVE_SMS,         // find-my-phone
            Manifest.permission.SEND_SMS,            // grandpa location reply
            Manifest.permission.ACCESS_FINE_LOCATION,// grandpa location reply
            Manifest.permission.RECORD_AUDIO         // talk companion (voice)
        )
    }
}
