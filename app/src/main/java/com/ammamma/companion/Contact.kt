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
 */
data class Contact(
    val name: String,
    val english: String,
    val number: String,
    val ringColor: Int,
    val id: String = ""
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
        val list: List<Contact> = if (raw == null) DEFAULTS else try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Contact(
                    o.getString("name"),
                    o.getString("english"),
                    o.getString("number"),
                    o.getInt("ringColor"),
                    o.optString("id")
                )
            }
        } catch (e: Exception) {
            DEFAULTS
        }
        // Older saves (and the DEFAULTS seed) have no id — assign one per person and
        // persist immediately, so clip/photo file names stay valid forever after.
        if (list.any { it.id.isEmpty() }) {
            val fixed = list.map { if (it.id.isEmpty()) it.copy(id = nextId(c)) else it }
            save(c, fixed)
            return fixed
        }
        return list
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
}
