package com.ammamma.companion

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * One person Ammamma can call. [name] is the Telugu word she recognizes, [english]
 * a small helper label, [number] is dialed on tap, [ringColor] her per-person color.
 * [id] is a STABLE key that survives edits, deletes and reordering — recorded caller
 * clips (clips/caller_<id>) and face photos (faces/<id>.jpg) are named by it.
 *
 * Family flags (all default false so old data migrates safely — see [Contacts.load]):
 * [isFamily] — this person is EXPLICITLY LINKED family, not just a dial entry.
 * [getsSos] — receives the SOS blast (location/photos/alarm/SMS).
 * [smsControl] — allowed to send SMS commands to her phone (remote photo / find-phone).
 * [videoCall] — shown as a WhatsApp video-call tile on Home.
 */
data class Contact(
    val name: String,
    val english: String,
    val number: String,
    val ringColor: Int,
    val id: String = "",
    val isFamily: Boolean = false,
    val getsSos: Boolean = false,
    val smsControl: Boolean = false,
    val videoCall: Boolean = false
)

/**
 * Where the face-buttons live. Saved on the phone (SharedPreferences as JSON) so the
 * family can EDIT names and numbers with a long-press — no rebuild, no code change.
 * Seeded once with sample people; after that, whatever the family sets.
 */
object Contacts {
    private const val PREFS = "ammamma_settings"
    private const val KEY = "contacts"

    private val DEFAULTS = listOf(
        Contact("కూతురు", "Daughter", "", Color.parseColor("#E4572E")),
        Contact("కొడుకు", "Son", "", Color.parseColor("#E8A200")),
        Contact("మనవడు", "Grandson", "", Color.parseColor("#4C8C7D")),
        Contact("మనవరాలు", "Granddaughter", "", Color.parseColor("#C0507F")),
        Contact("చెల్లి", "Sister", "", Color.parseColor("#3F7CC9")),
        Contact("డాక్టర్", "Doctor", "", Color.parseColor("#8E5BB5"))
    )

    // Ring colours cycled through as new people are added.
    private val PALETTE = listOf(
        Color.parseColor("#E4572E"), Color.parseColor("#E8A200"),
        Color.parseColor("#4C8C7D"), Color.parseColor("#C0507F"),
        Color.parseColor("#3F7CC9"), Color.parseColor("#8E5BB5")
    )

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): List<Contact> {
        val raw = prefs(c).getString(KEY, null)
        // Set while parsing if ANY saved contact predates the family-flags feature
        // (no "isFamily" key at all) — used below to run the one-time migration.
        var predatesFlags = false
        val list: List<Contact> = if (raw == null) DEFAULTS else try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                if (!o.has("isFamily")) predatesFlags = true
                Contact(
                    o.getString("name"),
                    o.getString("english"),
                    o.getString("number"),
                    o.getInt("ringColor"),
                    o.optString("id"),
                    o.optBoolean("isFamily", false),
                    o.optBoolean("getsSos", false),
                    o.optBoolean("smsControl", false),
                    o.optBoolean("videoCall", false)
                )
            }
        } catch (e: Exception) {
            DEFAULTS
        }

        // Two independent, additive one-time migrations layered on top of whatever
        // was loaded. Each only touches its own concern; if either changed anything
        // we persist once at the end (never twice).
        var fixed = list
        var dirty = false

        // Older saves (and the DEFAULTS seed) have no id — assign one per person, so
        // clip/photo file names stay valid forever after.
        if (fixed.any { it.id.isEmpty() }) {
            fixed = fixed.map { if (it.id.isEmpty()) it.copy(id = nextId(c)) else it }
            dirty = true
        }

        // Saves from before family flags existed: anyone with a real number is a
        // person the family already deliberately added — they ARE family, and
        // should get the SOS blast. smsControl/videoCall stay OFF (opt-in, powerful).
        // Empty-number seed rows (never edited) stay non-family.
        if (predatesFlags) {
            fixed = fixed.map {
                if (it.number.isNotBlank()) it.copy(isFamily = true, getsSos = true) else it
            }
            dirty = true
        }

        if (dirty) save(c, fixed)
        return fixed
    }

    /** Monotonic counter so an id is never reused, even after deletes. */
    private fun nextId(c: Context): String {
        val n = prefs(c).getInt("contact_next_id", 1)
        prefs(c).edit().putInt("contact_next_id", n + 1).apply()
        return "c$n"
    }

    fun save(c: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("english", it.english)
                    .put("number", it.number)
                    .put("ringColor", it.ringColor)
                    .put("id", it.id)
                    .put("isFamily", it.isFamily)
                    .put("getsSos", it.getsSos)
                    .put("smsControl", it.smsControl)
                    .put("videoCall", it.videoCall)
            )
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** Edit one person (keeps their color). */
    fun update(c: Context, index: Int, name: String, english: String, number: String) {
        val list = load(c).toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(
            name = name.trim(),
            english = english.trim(),
            number = number.trim()
        )
        save(c, list)
    }

    /** Add a new person (gets the next colour in the palette). */
    fun add(c: Context, name: String, english: String, number: String) {
        val list = load(c).toMutableList()
        list.add(Contact(name.trim(), english.trim(), number.trim(), PALETTE[list.size % PALETTE.size], nextId(c)))
        save(c, list)
    }

    /** Remove a person. */
    fun remove(c: Context, index: Int) {
        val list = load(c).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(c, list)
        }
    }

    /**
     * Add a person with the full family/SOS/SMS/video flag set (used by
     * FamilyActivity). Returns the new person's STABLE id so the caller can
     * immediately attach a photo via Faces.save(ctx, id, bmp).
     */
    fun addFull(
        c: Context,
        name: String,
        english: String,
        number: String,
        isFamily: Boolean,
        getsSos: Boolean,
        smsControl: Boolean,
        videoCall: Boolean
    ): String {
        val list = load(c).toMutableList()
        val id = nextId(c)
        list.add(
            Contact(
                name.trim(), english.trim(), number.trim(),
                PALETTE[list.size % PALETTE.size], id,
                isFamily, getsSos, smsControl, videoCall
            )
        )
        save(c, list)
        return id
    }

    /**
     * Edit one person by their STABLE id (safer than a list index, which can
     * shift under a filtered/reordered view). Keeps their ring color.
     */
    fun updateFull(
        c: Context,
        id: String,
        name: String,
        english: String,
        number: String,
        isFamily: Boolean,
        getsSos: Boolean,
        smsControl: Boolean,
        videoCall: Boolean
    ) {
        val list = load(c).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return
        list[index] = list[index].copy(
            name = name.trim(),
            english = english.trim(),
            number = number.trim(),
            isFamily = isFamily,
            getsSos = getsSos,
            smsControl = smsControl,
            videoCall = videoCall
        )
        save(c, list)
    }

    /**
     * Remove a person by their STABLE id. A removed person leaves NOTHING
     * behind: their face photo and any recorded caller-clip go too. Ids are
     * never reused, so this can't touch anyone else's files.
     */
    fun removeById(c: Context, id: String) {
        if (id.isEmpty()) return
        val list = load(c).toMutableList()
        if (list.removeAll { it.id == id }) {
            save(c, list)
            Faces.delete(c, id)
            ClipStore.delete(c, "caller_$id")
        }
    }

    // --- Query helpers: the single source of truth other work packages read from ---

    /** Everyone explicitly linked as family (not just a plain dial entry). */
    fun family(c: Context): List<Contact> = load(c).filter { it.isFamily }

    /** Family members who receive the SOS blast (location/photos/alarm/SMS). */
    fun sosRecipients(c: Context): List<Contact> =
        load(c).filter { it.isFamily && it.getsSos && it.number.isNotBlank() }

    /** Family members allowed to send SMS commands to her phone. */
    fun smsControllers(c: Context): List<Contact> =
        load(c).filter { it.isFamily && it.smsControl && it.number.isNotBlank() }

    /** Family members shown as a WhatsApp video-call tile on Home. */
    fun videoTiles(c: Context): List<Contact> =
        load(c).filter { it.isFamily && it.videoCall && it.number.isNotBlank() }
}
