package com.ammamma.companion

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * One person Ammamma can call. [name] is the Telugu word she recognizes, [english]
 * a small helper label, [number] is dialed on tap, [ringColor] her per-person color.
 */
data class Contact(
    val name: String,
    val english: String,
    val number: String,
    val ringColor: Int
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

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): List<Contact> {
        val raw = prefs(c).getString(KEY, null) ?: return DEFAULTS.also { save(c, it) }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Contact(
                    o.getString("name"),
                    o.getString("english"),
                    o.getString("number"),
                    o.getInt("ringColor")
                )
            }
        } catch (e: Exception) {
            DEFAULTS
        }
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
}
