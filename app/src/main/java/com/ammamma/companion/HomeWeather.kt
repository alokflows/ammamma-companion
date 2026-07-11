package com.ammamma.companion

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The tiny weather tile on the HOME screen ("31° ఎండ"). This is deliberately
 * SEPARATE from [Weather]: that one builds an English fact sheet for the AI brain;
 * this one fetches just the current reading and phrases it in Telugu itself, with
 * no AI and no API key — the tile must work even when no AI is configured.
 *
 * Same town, same free Open-Meteo service, same plain-HttpURLConnection style.
 * Everything network happens on a background thread; the reading is cached for
 * ten minutes so re-opening the app doesn't hammer the network (or the battery).
 */
object HomeWeather {

    private const val TAG = "Ammamma"

    // Huzurabad, Telangana — fixed, like Weather.kt: her town, no location permission.
    private const val LAT = 18.20
    private const val LON = 79.03

    private const val URL_CURRENT =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$LAT&longitude=$LON" +
            "&current_weather=true&timezone=Asia%2FKolkata"

    // Refresh at most every 10 minutes — weather doesn't move faster than that.
    private const val CACHE_MS = 10 * 60 * 1000L

    data class Reading(val tempC: Int, val code: Int)

    // The cache: last good reading + WHEN we got it (elapsedRealtime — monotonic,
    // safe against the wall clock being changed). Survives across activities for
    // as long as the process lives; a cold start just fetches again.
    @Volatile private var cached: Reading? = null
    @Volatile private var fetchedAt = 0L
    private val fetching = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** Last good reading, if any — for painting the tile instantly on resume. */
    fun latest(): Reading? = cached

    /**
     * Deliver a fresh (or fresh-enough) reading to [onUpdate] ON THE MAIN THREAD.
     * If the cache is younger than ten minutes it answers immediately; otherwise a
     * background fetch runs and calls back only on success — on failure the tile
     * simply keeps whatever it showed (the tap explains "no internet" out loud).
     */
    fun refresh(onUpdate: (Reading) -> Unit) {
        val fresh = cached
        if (fresh != null && SystemClock.elapsedRealtime() - fetchedAt < CACHE_MS) {
            onUpdate(fresh)
            return
        }
        if (!fetching.compareAndSet(false, true)) return   // one fetch at a time
        Thread {
            val r = try { fetch() } finally { fetching.set(false) }
            if (r != null) {
                cached = r
                fetchedAt = SystemClock.elapsedRealtime()
                main.post { onUpdate(r) }
            }
        }.apply { isDaemon = true; name = "home-weather" }.start()
    }

    /** BLOCKING — background thread only. Null on any failure; never throws. */
    private fun fetch(): Reading? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(URL_CURRENT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HomeWeather HTTP $code")
                return null
            }
            val raw = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val cur = JSONObject(raw).getJSONObject("current_weather")
            Reading(cur.getDouble("temperature").toInt(), cur.getInt("weathercode"))
        } catch (e: Exception) {
            Log.w(TAG, "HomeWeather fetch failed", e)
            null   // offline / dead network / bad JSON — the tile copes, never crashes
        } finally {
            conn?.disconnect()
        }
    }

    /** The tile's text, e.g. "31° ఎండ". */
    fun tileText(r: Reading): String = "${r.tempC}° ${skyTile(r.code)}"

    /**
     * What a TAP on the tile says. With a reading: "ఇప్పుడు 31 డిగ్రీలు, ఎండగా ఉంది".
     * Without one (offline, fetch failed): the honest no-internet line — she deserves
     * to know WHY the phone can't tell her, not silence.
     */
    fun spokenLine(): String {
        val r = cached ?: return "ఇంటర్నెట్ లేదు, వాతావరణం తెలియడం లేదు"
        return "ఇప్పుడు ${r.tempC} డిగ్రీలు, ${skySpoken(r.code)}"
    }

    // WMO weather code → one short Telugu word for the tile. Buckets she cares
    // about: sun, cloud, fog, rain, thunder. Snow codes fold into rain (it never
    // snows in Telangana, but a sane word must come out for EVERY code).
    private fun skyTile(code: Int): String = when (code) {
        0, 1 -> "ఎండ"                                  // clear / mostly clear
        2, 3 -> "మబ్బు"                                 // partly cloudy / overcast
        45, 48 -> "పొగమంచు"                             // fog
        in 51..67, in 80..86 -> "వాన"                   // drizzle, rain, showers (+snow codes)
        in 71..77 -> "వాన"                              // snow — treat as precipitation
        in 95..99 -> "ఉరుములు"                          // thunderstorm
        else -> "మబ్బు"                                 // unknown code → mild, never wrong-sounding
    }

    // Same buckets as full spoken clauses.
    private fun skySpoken(code: Int): String = when (code) {
        0, 1 -> "ఎండగా ఉంది"
        2, 3 -> "మబ్బుగా ఉంది"
        45, 48 -> "పొగమంచుగా ఉంది"
        in 51..67, in 80..86 -> "వాన పడుతోంది"
        in 71..77 -> "వాన పడుతోంది"
        in 95..99 -> "ఉరుములతో వాన వస్తోంది"
        else -> "మబ్బుగా ఉంది"
    }
}
