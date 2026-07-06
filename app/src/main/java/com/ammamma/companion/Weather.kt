package com.ammamma.companion

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Today's weather for Ammamma's town (Huzurabad, Telangana), from Open-Meteo.
 *
 * WHY Open-Meteo: it is free and needs NO API key, so nothing to set up or leak —
 * unlike the AI key. WHY plain HttpURLConnection: same as AiBrain, no library = tiny APK.
 *
 * We do NOT phrase the answer here. We fetch plain FACTS (temperature, rain chance,
 * sky) and hand them to AiBrain as extra context, so the model speaks them back in
 * warm, simple Telugu — one brain, one voice. Online only; caller checks internet.
 */
object Weather {

    private const val TAG = "Ammamma"
    // Huzurabad, Telangana. Fixed — this is her town; no location permission needed.
    private const val LAT = 18.20
    private const val LON = 79.03

    private const val URL_FORECAST =
        "https://api.open-meteo.com/v1/forecast" +
        "?latitude=$LAT&longitude=$LON" +
        "&current=temperature_2m,relative_humidity_2m,weather_code" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=Asia%2FKolkata&forecast_days=2"

    /** Does what she said sound like a weather question? (Telugu + a little English.) */
    fun isWeatherQuestion(text: String): Boolean {
        val t = text.lowercase()
        return WEATHER_WORDS.any { t.contains(it) }
    }

    private val WEATHER_WORDS = listOf(
        "వర్షం", "వాన", "వాతావరణం", "ఎండ", "వేడి", "చలి", "ఉక్క", "మబ్బు", "గాలి",
        "weather", "rain", "hot", "cold", "temperature"
    )

    /**
     * BLOCKING network call — run OFF the main thread (AiBrain already runs on a bg thread).
     * Returns a short English fact sheet for the model, or null if offline / it failed
     * (in which case the companion just chats normally, never errors).
     */
    fun factsForBrain(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(URL_FORECAST).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Weather HTTP $code")
                return null
            }
            val raw = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(raw)

            val cur = json.getJSONObject("current")
            val nowTemp = cur.getDouble("temperature_2m").toInt()
            val humidity = cur.getInt("relative_humidity_2m")
            val nowSky = describe(cur.getInt("weather_code"))

            val daily = json.getJSONObject("daily")
            val hi = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()
            val lo = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()
            val rainToday = daily.getJSONArray("precipitation_probability_max").getInt(0)
            val rainTomorrow = daily.getJSONArray("precipitation_probability_max").getInt(1)

            // A plain-English fact sheet. The system prompt already forces a warm Telugu reply.
            "WEATHER FACTS for Ammamma's town today — answer her using these, in simple spoken Telugu, " +
            "one or two short sentences: right now it is $nowTemp degrees, humidity $humidity percent, " +
            "sky is $nowSky. Today's high is $hi and low is $lo degrees. " +
            "Chance of rain today is $rainToday percent, tomorrow $rainTomorrow percent."
                .also { Log.i(TAG, "Weather facts: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** WMO weather code → plain words the model understands. */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "light drizzle"
        61, 63, 65, 66, 67 -> "raining"
        71, 73, 75, 77 -> "snowy"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "changing"
    }
}
