package com.ammamma.companion

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The companion's brain. Sends what Ammamma said to OpenRouter (free models) and
 * gets back a short, warm Telugu reply.
 *
 * Text-in / text-out: speech->text (Android STT) happens BEFORE this, and text->speech
 * (Announcer/TTS) happens AFTER. Online only — the caller checks for internet.
 *
 * We pass a fallback list of models, so if the first is momentarily rate-limited on
 * the free tier, OpenRouter automatically tries the next. Verified good at Telugu:
 * gemma-4-31b (warm, fast), gpt-oss-120b (reliable backup).
 *
 * Uses plain HttpURLConnection — no networking library — to keep the APK tiny.
 */
object AiBrain {

    private const val TAG = "Ammamma"
    private const val URL_CHAT = "https://openrouter.ai/api/v1/chat/completions"

    // A long fallback list: free models get rate-limited (429), so we give OpenRouter
    // many to roll through, ending with "openrouter/free" which auto-picks any that's
    // available. Warm-at-Telugu ones first.
    private val MODELS = listOf(
        "google/gemma-4-31b-it:free",
        "openai/gpt-oss-120b:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        "google/gemma-4-26b-a4b-it:free",
        "openai/gpt-oss-20b:free",
        "openrouter/free"
    )

    private const val SYSTEM_PROMPT =
        "You are Ammamma's warm companion, like a caring family member. " +
        "Ammamma is an elderly Telugu grandmother who cannot read and only speaks Telugu. " +
        "ALWAYS reply ONLY in simple, spoken Telugu, in one or two short sentences. " +
        "Never use English words, never use technical words. Be affectionate, gentle and clear. " +
        "You can chat about cooking, family, stories, devotion, and daily life."

    data class Result(val ok: Boolean, val text: String)

    /**
     * Ask the brain. BLOCKING network call — run OFF the main thread.
     * [extraContext] can carry facts (e.g. today's weather) for the model to phrase warmly.
     */
    fun ask(apiKey: String, userText: String, extraContext: String? = null): Result {
        if (apiKey.isBlank()) return Result(false, "AI సెటప్ కావాలి")

        var conn: HttpURLConnection? = null
        return try {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            if (!extraContext.isNullOrBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", extraContext))
            }
            messages.put(JSONObject().put("role", "user").put("content", userText))

            val body = JSONObject()
                .put("models", JSONArray(MODELS))
                .put("messages", messages)
                .toString()

            conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream.bufferedReader().use(BufferedReader::readText)

            if (code !in 200..299) {
                Log.e(TAG, "AI HTTP $code: ${raw.take(200)}")
                // 429 = all free models busy right now.
                return Result(false, "ఇప్పుడు అందరూ బిజీగా ఉన్నారు, కొద్దిసేపటి తర్వాత అడగండి")
            }

            val reply = JSONObject(raw)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            Log.i(TAG, "AI reply: $reply")
            Result(true, reply)
        } catch (e: Exception) {
            Log.e(TAG, "AI call failed", e)
            Result(false, "ఇప్పుడు మాట్లాడలేను")
        } finally {
            conn?.disconnect()
        }
    }
}
