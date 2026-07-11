package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
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
    private lateinit var weather: TextView
    private var pendingNumber: String? = null

    // What the grid was last built for (locked or unlocked) — so onResume can
    // rebuild it only when the family flipped the edit lock in Settings.
    private var lastBuiltLocked: Boolean? = null

    // Location-off warning: spoken at most ONCE per app-open (reset in onCreate),
    // but the banner stays visible for as long as location remains off.
    private var warnedLocationOff = false
    private var locationBanner: TextView? = null
    // The "can't announce callers" banner (missing phone/call-log/contacts perms).
    private var callerBanner: TextView? = null

    // One-time setup flags (first-run permission storm, overlay ask). Deliberately
    // NOT in Settings.kt — these are app plumbing, not family-editable settings.
    private val flags by lazy { getSharedPreferences("setup_flags", MODE_PRIVATE) }

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
        weather = findViewById(R.id.weather)
        buildFaceGrid()

        // Weather tile speaks on tap — real info, out loud, because she can't read
        // the number. Offline it honestly says the internet is down (never silence).
        weather.setOnClickListener {
            Announcer.get(this).say(HomeWeather.spokenLine())
        }

        // TAP-TO-SILENCE: if the phone is mid-sentence and she wants quiet, tapping
        // the clock or any blank part of the home stops the voice. No menu, no icon
        // to learn — the most natural gesture there is.
        val hush = View.OnClickListener { Announcer.get(this).stopSpeaking() }
        clock.setOnClickListener(hush)
        findViewById<View>(R.id.homeRoot).setOnClickListener(hush)

        // Speak a hello when she opens the app — but only once per 30 minutes (not
        // on every resume/re-open) AND only if the family left the greeting on.
        // The home screen used to be completely silent — for someone who can't read,
        // that's indistinguishable from a broken phone. Clip key so the family can
        // later record this greeting in their own voice.
        if (Settings.greetingEnabled(this) && Announcer.shouldGreetNow()) {
            Announcer.get(this).announce("home_greeting", "నమస్తే అమ్మమ్మ")
        }

        findViewById<View>(R.id.talkButton).setOnClickListener {
            startActivity(Intent(this, TalkActivity::class.java))
        }

        // Settings is family-only. A casual tap does nothing (a gentle Telugu hint);
        // it opens only on a long-press, so Ammamma can't wander into the technical /
        // API-key screen and get scared. (Kamala's #3.)
        val gear = findViewById<View>(R.id.settings)
        gear.setOnClickListener {
            Toast.makeText(this, "కుటుంబం కోసం — నొక్కి పట్టుకోండి", Toast.LENGTH_SHORT).show()
            // She can't read the toast — say it too (every state she can reach speaks),
            // unless the family switched off UI-tap lines in Settings.
            if (Settings.uiSoundsEnabled(this)) {
                Announcer.get(this).say("ఇది ఇంట్లో వాళ్ళ కోసం అమ్మమ్మ")
            }
        }
        gear.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }

        installMuteToggle(gear)
    }

    /**
     * A small speaker toggle (🔊/🔇) placed next to the gear, code-built so the home
     * layout XML stays untouched. Flips the master quick-mute; the icon itself is the
     * only feedback — no spoken line on mute (spec: muting must not talk about itself).
     */
    private fun installMuteToggle(gear: View) {
        val row = gear.parent as? LinearLayout ?: return
        val gearIndex = row.indexOfChild(gear)
        row.removeView(gear)

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        val muteToggle = TextView(this).apply {
            textSize = 22f
            setPadding(dp(6), dp(2), dp(10), dp(2))
            setTextColor(Color.parseColor("#8A7361"))
            isClickable = true
            isFocusable = true
        }
        fun renderMuteIcon() {
            muteToggle.text = if (Settings.voiceMuted(this)) "🔇" else "🔊"
        }
        renderMuteIcon()
        muteToggle.setOnClickListener {
            Settings.setVoiceMuted(this, !Settings.voiceMuted(this))
            renderMuteIcon()   // visually obvious state change; no spoken feedback
        }
        topRow.addView(muteToggle)
        topRow.addView(gear, LinearLayout.LayoutParams(dp(30), dp(30)))
        row.addView(
            topRow, gearIndex,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    override fun onResume() {
        super.onResume()
        // Escape hatch: if the find-phone alarm is sounding, opening the app must
        // ALWAYS land on the big STOP button — never a silent home screen over a
        // screaming phone.
        if (CompanionService.isFindAlarmActive) {
            startActivity(Intent(this, FindPhoneActivity::class.java))
        }
        clockTick.run()  // start ticking
        checkLocation()  // grandpa-finder needs location ON; nag if it's off
        checkCallerIdentity()  // caller announcement needs phone/call-log/contacts perms
        // The family may have just flipped the edit lock in Settings — the grid's
        // add-tile visibility depends on it, so rebuild if the state changed.
        if (lastBuiltLocked != Settings.editLocked(this)) buildFaceGrid()
        refreshWeather()
    }

    /** Paint whatever weather we have instantly, then fetch a fresh reading. */
    private fun refreshWeather() {
        // Before the first reading arrives the tile shows just the word "వాతావరణం";
        // tapping it then speaks the honest no-data line.
        weather.text = HomeWeather.latest()?.let { HomeWeather.tileText(it) } ?: "వాతావరణం"
        HomeWeather.refresh { weather.text = HomeWeather.tileText(it) }
    }

    override fun onPause() {
        ui.removeCallbacks(clockTick)
        super.onPause()
    }

    /**
     * LEAVE = SILENCE: once she's left the home screen, nothing should keep talking
     * behind her back — EXCEPT the find-my-phone alarm, which must keep sounding
     * however she navigates (that's the whole point of it).
     *
     * WHY NOT onStop(): onStop also fires whenever ANOTHER activity merely covers
     * this one — including our OWN ChargingActivity/AlertActivity (which appear at
     * the same moment as the "ఛార్జర్ పెట్టారు…" line) and the system incoming-call
     * screen (which appears while the caller's name is being spoken). Silencing
     * there chopped those announcements mid-word. onUserLeaveHint() fires only on a
     * deliberate leave (Home button) and — per the Android docs — deliberately does
     * NOT fire when an incoming call covers the activity. Back no longer leaves the
     * app at all (see [onBackPressed]), so Home is the one deliberate exit.
     */
    override fun onUserLeaveHint() {
        if (!CompanionService.isFindAlarmActive) {
            Announcer.get(this).stopSpeaking()
        }
        super.onUserLeaveHint()
    }

    /**
     * Back is CONSUMED on Home: this app IS the phone's front door for Ammamma, and
     * a stray Back press must never dump her onto the bare Android launcher — a
     * screen she can't read and can't get back from. So: no super call, nothing
     * happens. (Tap-to-silence covers the "make it stop talking" need instead.)
     */
    override fun onBackPressed() {
        // deliberately empty — Back does nothing on the home screen
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
        val locked = Settings.editLocked(this)
        lastBuiltLocked = locked

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

            // Family-set photo: fills the ring as a circle. No photo → the generic
            // person icon stays, so the grid never looks broken while photos trickle in.
            val photo = Faces.load(this, contact.id)
            if (photo != null) {
                card.findViewById<ImageView>(R.id.avatarImage).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }   // keep the ring visible
                    setImageBitmap(Faces.circle(photo))
                }
            }

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
                    // Say WHO is being called as the call goes out — she may have
                    // tapped by mistake, and hearing the name is her only feedback.
                    Announcer.get(this).say("${contact.name}కి ఫోన్ చేస్తున్నాను")
                    call(contact.number)
                } else {
                    Toast.makeText(this, "నంబర్ లేదు — కుటుంబాన్ని అడగండి", Toast.LENGTH_SHORT).show()
                    Announcer.get(this).say("నంబర్ లేదు, ఇంట్లో వాళ్ళను అడగండి")
                }
            }
            // Long press = edit (for family). When the family LOCKED editing in
            // Settings, an accidental long-press (she holds things) must not open an
            // English form — instead the phone explains, out loud, how to unlock.
            // Checked live at press time, so a Settings flip works without a rebuild.
            card.setOnLongClickListener {
                if (Settings.editLocked(this)) {
                    Announcer.get(this).say("మార్చాలంటే సెట్టింగ్స్‌లో అన్‌లాక్ చేయండి")
                } else {
                    showEditDialog(index, contact)
                }
                true
            }

            // two equal columns, each cell with a little margin
            grid.addView(card, cellParams(gap))
        }

        // A "+" tile at the end to ADD a new person — family-only, so it simply
        // doesn't exist while editing is locked (a dead-looking tile would confuse her).
        if (!locked) {
            val addCard = inflater.inflate(R.layout.item_face, grid, false)
            addCard.findViewById<TextView>(R.id.name).text = "＋"
            addCard.findViewById<TextView>(R.id.rel).text = "కొత్త వ్యక్తి · Add"
            addCard.findViewById<View>(R.id.callBadge).visibility = View.GONE
            addCard.findViewById<View>(R.id.noNumber).visibility = View.GONE
            addCard.findViewById<FrameLayout>(R.id.avatarFrame).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F3E7D3"))
                setStroke(dp(4), Color.parseColor("#B0857A"))
            }
            addCard.setOnClickListener { showEditDialog(-1, null) }
            grid.addView(addCard, cellParams(gap))
        }
    }

    private fun cellParams(gap: Int) = GridLayout.LayoutParams().apply {
        width = 0
        height = GridLayout.LayoutParams.WRAP_CONTENT
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(gap, gap, gap, gap)
    }

    /**
     * Family editor. [index] < 0 means ADD a new person; otherwise edit (with a
     * Delete option) the existing one.
     */
    private fun showEditDialog(index: Int, contact: Contact?) {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        val nameEt = EditText(this).apply { setText(contact?.name ?: ""); hint = "పేరు / Name (Telugu)" }
        val engEt = EditText(this).apply { setText(contact?.english ?: ""); hint = "Label (English)" }
        val numEt = EditText(this).apply {
            setText(contact?.number ?: ""); hint = "Phone number"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        box.addView(nameEt); box.addView(engEt); box.addView(numEt)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (index < 0) "కొత్త వ్యక్తి · New person" else "పేరు · నంబర్ మార్చండి")
            .setView(box)
            .setPositiveButton("సేవ్") { _, _ ->
                val n = nameEt.text.toString(); val e = engEt.text.toString(); val num = numEt.text.toString()
                if (index < 0) Contacts.add(this, n, e, num) else Contacts.update(this, index, n, e, num)
                buildFaceGrid()
            }
            .setNegativeButton("రద్దు", null)
        if (index >= 0 && contact != null) {
            builder.setNeutralButton("తీసివేయి") { _, _ ->   // delete person + their files
                removePersonFiles(contact.id)
                Contacts.remove(this, index)
                buildFaceGrid()
            }
        }
        val dialog = builder.create()

        // Photo controls (existing people only — a new person has no id yet).
        if (index >= 0 && contact != null && contact.id.isNotEmpty()) {
            box.addView(Button(this).apply {
                text = "ఫోటో పెట్టండి"
                setOnClickListener {
                    // Remember WHOSE photo is being picked in prefs, not a field —
                    // on 2 GB RAM the process can die while the gallery is open.
                    flags.edit().putString(FLAG_PENDING_PHOTO_ID, contact.id).apply()
                    val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    }
                    val ok = runCatching { startActivityForResult(pick, REQ_PICK_PHOTO) }.isSuccess
                    if (!ok) {
                        flags.edit().remove(FLAG_PENDING_PHOTO_ID).apply()
                        Toast.makeText(this@MainActivity, "గ్యాలరీ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
            })
            if (Faces.fileFor(this, contact.id).exists()) {
                box.addView(Button(this).apply {
                    text = "ఫోటో తీసేయండి"
                    setOnClickListener {
                        Faces.delete(this@MainActivity, contact.id)
                        buildFaceGrid()
                        dialog.dismiss()
                    }
                })
            }
        }
        dialog.show()
    }

    /**
     * A removed person must leave NOTHING behind: their face photo and any family
     * voice clip recorded for their calls ("caller_<id>.*") both go. Ids are never
     * reused, so this can't touch anyone else's files.
     */
    private fun removePersonFiles(id: String) {
        if (id.isEmpty()) return
        Faces.delete(this, id)
        File(filesDir, "clips").listFiles()?.forEach {
            if (it.nameWithoutExtension == "caller_$id") runCatching { it.delete() }
        }
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

    /**
     * Setup chain, each step at most once: runtime permissions (FIRST launch only —
     * never a repeat dialog-storm) → battery-optimization exemption → "display over
     * other apps" (ColorOS needs it so alert screens actually appear). The chain is
     * sequential so the family sees one clear ask at a time, not a pile of screens.
     */
    private fun requestStartupPermissions() {
        val needed = STARTUP_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty() && !flags.getBoolean(FLAG_PERMS_ASKED, false)) {
            flags.edit().putBoolean(FLAG_PERMS_ASKED, true).apply()
            requestPermissions(needed.toTypedArray(), REQ_STARTUP)
        } else {
            askToRunPersistently()
        }
    }

    /**
     * Ask ColorOS/Android to stop killing us for battery. Re-checked every launch
     * (persistence is the app's spine), but it's a no-op once granted.
     */
    private fun askToRunPersistently() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            val ok = runCatching {
                startActivityForResult(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ),
                    REQ_BATTERY_OPT
                )
            }.isSuccess
            if (ok) return   // overlay ask continues in onActivityResult
        }
        askForOverlay()
    }

    /**
     * "Display over other apps" — without it ColorOS suppresses our full-screen
     * alerts (the find-phone screen ringing with NO stop button bug). Sent to the
     * system toggle once; SETUP_PHONE.md covers doing it by hand if skipped.
     */
    private fun askForOverlay() {
        if (android.provider.Settings.canDrawOverlays(this)) return
        if (flags.getBoolean(FLAG_OVERLAY_ASKED, false)) return
        flags.edit().putBoolean(FLAG_OVERLAY_ASKED, true).apply()
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Battery-exemption dialog closed (either way) → next step of the chain.
        if (requestCode == REQ_BATTERY_OPT) askForOverlay()

        // Family picked a face photo from the gallery.
        if (requestCode == REQ_PICK_PHOTO) {
            val id = flags.getString(FLAG_PENDING_PHOTO_ID, null)
            flags.edit().remove(FLAG_PENDING_PHOTO_ID).apply()
            val uri = data?.data
            if (resultCode != RESULT_OK || id.isNullOrEmpty() || uri == null) return
            // Decode + shrink OFF the UI thread — a 12 MP photo can take a moment
            // on this phone, and the home screen must never freeze.
            Thread {
                val bmp = Faces.decodeScaled(this, uri)
                if (bmp != null) Faces.save(this, id, bmp)
                ui.post {
                    if (bmp != null) buildFaceGrid()
                    else Toast.makeText(this, "ఫోటో పెట్టలేకపోయాను", Toast.LENGTH_SHORT).show()
                }
            }.apply { isDaemon = true; name = "face-photo" }.start()
        }
    }

    /**
     * The find-my-phone reply needs location ON all the time. If someone turned it
     * off, say so out loud (once per app-open) and keep a tappable banner up top
     * until it's back on.
     */
    private fun checkLocation() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        val on = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        if (on) {
            locationBanner?.visibility = View.GONE
            return
        }
        ensureLocationBanner().visibility = View.VISIBLE
        if (!warnedLocationOff) {
            warnedLocationOff = true
            Announcer.get(this).say("లొకేషన్ ఆఫ్ అయ్యింది, ఆన్ చేయండి")
        }
    }

    /** Built in code (not XML) so the home layout stays untouched. */
    private fun ensureLocationBanner(): TextView {
        locationBanner?.let { return it }
        return makeBanner("📍 లొకేషన్ ఆన్ చేయండి · Turn location ON") {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }.also { locationBanner = it }
    }

    /**
     * The caller-announcement chain needs THREE permissions: READ_PHONE_STATE (a call
     * is happening), READ_CALL_LOG (the number itself on API 28+), READ_CONTACTS (the
     * name). Miss any and she hears "unknown" for everyone. Show a tappable red banner
     * — the tap grants the missing ones directly (an explicit tap is fine to re-ask on,
     * even after the one-time first-run storm). Hidden once all three are held.
     */
    private fun checkCallerIdentity() {
        val missing = CALLER_PERMISSIONS.any {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missing) {
            callerBanner?.visibility = View.GONE
            return
        }
        ensureCallerBanner().visibility = View.VISIBLE
    }

    private fun ensureCallerBanner(): TextView {
        callerBanner?.let { return it }
        return makeBanner("🔊 కాలర్ పేరు చెప్పలేను — నొక్కండి · Can't announce callers — tap to fix") {
            // Recompute the missing set at tap time so we only ask for what's still needed.
            val stillMissing = CALLER_PERMISSIONS.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (stillMissing.isNotEmpty()) requestPermissions(stillMissing.toTypedArray(), REQ_CALLER_PERMS)
        }.also { callerBanner = it }
    }

    /**
     * Shared red warning-banner builder. Inserted as the first row of the home column;
     * the location and caller-identity banners each use this and can coexist (two red
     * bars is fine). Built in code so the home XML stays untouched.
     */
    private fun makeBanner(label: String, onTap: () -> Unit): TextView {
        val banner = TextView(this).apply {
            text = label
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = android.view.Gravity.CENTER
            setOnClickListener { onTap() }
        }
        // Root column inside the ScrollView — insert the banner as the first row.
        val root = findViewById<View>(R.id.grid).parent as LinearLayout
        root.addView(
            banner, 0,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return banner
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
        private const val REQ_BATTERY_OPT = 104
        private const val REQ_CALLER_PERMS = 105   // re-ask from the caller-identity banner
        private const val REQ_PICK_PHOTO = 106     // family picking a face photo from the gallery

        // Whose face photo is being picked right now — kept in prefs (not a field)
        // so it survives the process being killed while the gallery is in front.
        private const val FLAG_PENDING_PHOTO_ID = "pending_photo_id"

        // The three permissions the caller announcement depends on; the banner nags
        // (and can re-grant) if any is missing.
        private val CALLER_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )

        private const val FLAG_PERMS_ASKED = "perms_requested"
        private const val FLAG_OVERLAY_ASKED = "overlay_requested"

        // All the runtime permissions the companion needs, asked together on first run.
        private val STARTUP_PERMISSIONS = arrayOf(
            Manifest.permission.CALL_PHONE,          // one-tap calling
            Manifest.permission.READ_PHONE_STATE,    // announce who's calling
            // On API 28+ the incoming number arrives null/empty UNLESS we also hold
            // READ_CALL_LOG — without it every caller becomes "unknown". Ask up front.
            Manifest.permission.READ_CALL_LOG,       // get the incoming number on newer Android
            Manifest.permission.READ_CONTACTS,       // announce names from the PHONE's contact book
            Manifest.permission.RECEIVE_SMS,         // find-my-phone
            Manifest.permission.SEND_SMS,            // grandpa location reply
            Manifest.permission.ACCESS_FINE_LOCATION,// grandpa location reply
            Manifest.permission.RECORD_AUDIO         // talk companion (voice)
        )
    }
}
