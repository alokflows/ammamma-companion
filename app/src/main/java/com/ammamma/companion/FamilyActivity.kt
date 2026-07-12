package com.ammamma.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.ammamma.companion.ui.GlowBackdrop
import com.ammamma.companion.ui.Press

/**
 * The family manager — the ONE screen where the family decides who counts as
 * family, who gets the SOS blast, who can send SMS commands to her phone, and
 * who shows up as a video-call tile on Home. [Contacts] is the single source
 * of truth this screen edits; every other work package (SOS, SMS remote
 * control, video tiles) just reads Contacts.sosRecipients / smsControllers /
 * videoTiles and never needs to know how the family set them up.
 *
 * This is a family-only screen (not for Ammamma), reached by a long-press on
 * the Settings gear → the "కుటుంబం · Family" button. WP-LUX styled: the same
 * ink backdrop + ambient GlowBackdrop + press-spring physics as Home.
 */
class FamilyActivity : Activity() {

    private lateinit var glow: GlowBackdrop
    private lateinit var listContainer: LinearLayout

    // Whose face photo is being picked right now — kept in prefs (not a field)
    // so it survives the process dying while the gallery is in front, same
    // reasoning as MainActivity's own pending-photo flag.
    private val flags by lazy { getSharedPreferences("family_setup_flags", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_family)

        // WP-LUX ambient mood for this screen: warm gold + rose + violet — the
        // same warm family as Home, tilted toward rose for "family" specifically.
        glow = findViewById<GlowBackdrop>(R.id.glow).also {
            it.setMood(Color.rgb(255, 176, 84), Color.rgb(192, 80, 127), Color.rgb(150, 120, 255))
        }

        listContainer = findViewById(R.id.listContainer)
        buildList()

        findViewById<View>(R.id.addButton).also {
            it.setOnClickListener { showForm(null) }
            Press.attach(it, cornerRadiusDp = 30f)
        }
    }

    override fun onStart() {
        super.onStart()
        glow.start()
    }

    override fun onStop() {
        glow.stop()
        super.onStop()
    }

    /** Rebuild every card from scratch — same "just rebuild the whole grid"
     *  pattern MainActivity uses after any edit; the list is small so this is
     *  cheap, and it means every card is always painted from fresh data. */
    private fun buildList() {
        listContainer.removeAllViews()
        val contacts = Contacts.load(this)
        contacts.forEach { contact ->
            listContainer.addView(
                buildCard(contact),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            )
        }
    }

    /** One luminous card: photo/initial circle, name + number, edit/delete
     *  icons, and a row of three quick pill toggles (SOS / SMS / Video). */
    private fun buildCard(contact: Contact): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_lux)
            setPadding(dp(16), dp(16), dp(14), dp(14))
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Photo (or a colored initial-less ring, matching MainActivity's no-photo
        // tile) — the family's face-recognition cue, same as Home's grid.
        val avatarSize = dp(56)
        val avatarFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#232A33"))
                setStroke(dp(3), contact.ringColor)
            }
        }
        val avatarImage = ImageView(this).apply {
            setColorFilter(Color.parseColor("#D8CDBE"))
            setImageResource(R.drawable.ic_person)
        }
        val photo = Faces.load(this, contact.id)
        if (photo != null) {
            avatarImage.clearColorFilter()
            avatarImage.setImageBitmap(Faces.circle(photo))
            avatarFrame.addView(
                avatarImage,
                FrameLayout.LayoutParams(avatarSize, avatarSize).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            )
        } else {
            avatarFrame.addView(
                avatarImage,
                FrameLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER }
            )
        }
        row1.addView(avatarFrame, LinearLayout.LayoutParams(avatarSize, avatarSize))

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = contact.name
            includeFontPadding = true
            setTextColor(Color.parseColor("#F4EEE3"))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = if (contact.number.isNotBlank()) {
                "${contact.english} · ${contact.number}"
            } else {
                "${contact.english} · నంబర్ లేదు"   // no number
            }
            includeFontPadding = true
            setTextColor(Color.parseColor("#A79E90"))
            textSize = 13f
        })
        row1.addView(
            textCol,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
        )

        row1.addView(iconButton("✏") { showForm(contact) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row1.addView(
            iconButton("🗑") { confirmDelete(contact) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) }
        )
        card.addView(row1)

        // Quick toggles: flip one and it saves immediately (no Save button),
        // same "instant apply" feel as SettingsActivity's switches.
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addPill(row2, "SOS", contact.getsSos) { newVal ->
            Contacts.updateFull(
                this, contact.id, contact.name, contact.english, contact.number,
                contact.isFamily, newVal, contact.smsControl, contact.videoCall
            )
            buildList()
        }
        addPill(row2, "SMS", contact.smsControl) { newVal ->
            Contacts.updateFull(
                this, contact.id, contact.name, contact.english, contact.number,
                contact.isFamily, contact.getsSos, newVal, contact.videoCall
            )
            buildList()
        }
        addPill(row2, "వీడియో", contact.videoCall) { newVal ->
            Contacts.updateFull(
                this, contact.id, contact.name, contact.english, contact.number,
                contact.isFamily, contact.getsSos, contact.smsControl, newVal
            )
            buildList()
        }
        card.addView(
            row2,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        // Tapping the card body (anywhere that isn't the icons or a pill, all of
        // which have their own listeners and intercept the touch first) opens Edit.
        card.setOnClickListener { showForm(contact) }
        Press.attach(card, cornerRadiusDp = 24f)
        return card
    }

    private fun iconButton(label: String, onTap: () -> Unit): View {
        val btn = TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D8CDBE"))
            isClickable = true
            isFocusable = true
        }
        btn.setOnClickListener { onTap() }
        Press.attach(btn, cornerRadiusDp = 20f)
        return btn
    }

    /** One SOS/SMS/Video pill: labelled toggle chip that saves the instant it's
     *  tapped. [onToggle] receives the NEW value; the caller persists it. */
    private fun addPill(row: LinearLayout, label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val pill = TextView(this).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setTextColor(if (checked) Color.parseColor("#0F1216") else Color.parseColor("#A79E90"))
            setBackgroundResource(if (checked) R.drawable.pill_on_lux else R.drawable.pill_off_lux)
            isClickable = true
            isFocusable = true
        }
        pill.setOnClickListener { onToggle(!checked) }
        // The pill drawables already carry their own ripple — no extra one needed.
        Press.attach(pill, cornerRadiusDp = 18f, addRipple = false)
        row.addView(
            pill,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
        )
    }

    private fun confirmDelete(contact: Contact) {
        AlertDialog.Builder(this)
            .setTitle("తీసివేయాలా? · Remove?")
            .setMessage("${contact.name}ను కుటుంబం నుండి తీసివేయాలా?")
            .setPositiveButton("తీసివేయి") { _, _ ->
                Contacts.removeById(this, contact.id)
                Announcer.get(this).say("${contact.name} తీసివేయబడ్డారు")
                buildList()
            }
            .setNegativeButton("రద్దు", null)
            .show()
    }

    /**
     * Add ([contact] null) or Edit form. Reuses MainActivity's exact edit-dialog
     * pattern: an AlertDialog holding plain EditTexts built in code. The photo
     * button only appears for an EXISTING person (has an id already) — a
     * brand-new person has nowhere to save the file until after the first Save,
     * same constraint MainActivity's own dialog has.
     *
     * Everyone added ON THIS SCREEN is family by default (isFamily + getsSos
     * start ON); smsControl/videoCall start OFF — they're powerful, opt-in only.
     */
    private fun showForm(contact: Contact?) {
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

        val familySwitch = Switch(this).apply {
            text = "కుటుంబం · Family member"
            isChecked = contact?.isFamily ?: true
        }
        val sosSwitch = Switch(this).apply {
            text = "అత్యవసర సందేశాలు · Gets SOS"
            isChecked = contact?.getsSos ?: true
        }
        val smsSwitch = Switch(this).apply {
            text = "SMS నియంత్రణ · SMS control"
            isChecked = contact?.smsControl ?: false
        }
        val videoSwitch = Switch(this).apply {
            text = "వీడియో టైల్ · Video tile"
            isChecked = contact?.videoCall ?: false
        }
        box.addView(familySwitch); box.addView(sosSwitch); box.addView(smsSwitch); box.addView(videoSwitch)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (contact == null) "కొత్త కుటుంబ సభ్యుడు · New" else "వివరాలు మార్చండి · Edit")
            .setView(box)
            .setPositiveButton("సేవ్", null)   // wired below so an invalid save can keep the dialog open
            .setNegativeButton("రద్దు", null)

        val dialog = builder.create()

        // Photo controls (existing people only — see doc comment above).
        if (contact != null && contact.id.isNotEmpty()) {
            box.addView(Button(this).apply {
                text = "ఫోటో పెట్టండి"
                setOnClickListener {
                    flags.edit().putString(FLAG_PENDING_PHOTO_ID, contact.id).apply()
                    val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    }
                    val ok = runCatching { startActivityForResult(pick, REQ_PICK_PHOTO) }.isSuccess
                    if (!ok) {
                        flags.edit().remove(FLAG_PENDING_PHOTO_ID).apply()
                        Toast.makeText(this@FamilyActivity, "గ్యాలరీ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
            })
            if (Faces.fileFor(this, contact.id).exists()) {
                box.addView(Button(this).apply {
                    text = "ఫోటో తీసేయండి"
                    setOnClickListener {
                        Faces.delete(this@FamilyActivity, contact.id)
                        buildList()
                        dialog.dismiss()
                    }
                })
            }
        }

        // Override the positive button's click AFTER show(), so an invalid entry
        // can refuse (speak + toast a Telugu hint) WITHOUT the dialog auto-closing
        // — the default setPositiveButton listener always dismisses regardless.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = nameEt.text.toString().trim()
                val e = engEt.text.toString().trim()
                val num = numEt.text.toString().trim()
                if (n.isEmpty() || num.isEmpty()) {
                    val hint = "పేరు, నంబర్ రెండూ పెట్టండి"   // both a name and a number are needed
                    Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                    Announcer.get(this).say(hint)
                    return@setOnClickListener   // stays open — never fail silently
                }
                if (contact == null) {
                    Contacts.addFull(
                        this, n, e, num,
                        familySwitch.isChecked, sosSwitch.isChecked, smsSwitch.isChecked, videoSwitch.isChecked
                    )
                } else {
                    Contacts.updateFull(
                        this, contact.id, n, e, num,
                        familySwitch.isChecked, sosSwitch.isChecked, smsSwitch.isChecked, videoSwitch.isChecked
                    )
                }
                buildList()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_PHOTO) return
        val id = flags.getString(FLAG_PENDING_PHOTO_ID, null)
        flags.edit().remove(FLAG_PENDING_PHOTO_ID).apply()
        val uri = data?.data
        if (resultCode != RESULT_OK || id.isNullOrEmpty() || uri == null) return
        // Decode + shrink OFF the UI thread — a full-size photo can take a moment
        // on this phone, and this screen must never freeze while it works.
        Thread {
            val bmp = Faces.decodeScaled(this, uri)
            if (bmp != null) Faces.save(this, id, bmp)
            runOnUiThread {
                if (bmp != null) {
                    buildList()
                } else {
                    val hint = "ఫోటో పెట్టలేకపోయాను"   // couldn't set the photo
                    Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                    Announcer.get(this).say(hint)
                }
            }
        }.apply { isDaemon = true; name = "family-photo" }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PICK_PHOTO = 301
        private const val FLAG_PENDING_PHOTO_ID = "pending_photo_id"
    }
}
