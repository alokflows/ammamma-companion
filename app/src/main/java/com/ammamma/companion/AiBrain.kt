package com.ammamma.companion

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The companion's brain. Sends what Ammamma said to an OpenAI-compatible chat API
 * and gets back a short, warm Telugu reply.
 *
 * Provider-agnostic: the base URL, API key and model all come from Settings, so
 * the family can point it at OpenRouter, Groq, OpenAI — anything that speaks the
 * /chat/completions + /models protocol — without a rebuild. If no model is chosen
 * and the provider is OpenRouter, a built-in free-model fallback list is used.
 *
 * Text-in / text-out: speech->text (Android STT) happens BEFORE this, and text->speech
 * (Announcer/TTS) happens AFTER. Online only — the caller checks for internet.
 *
 * Uses plain HttpURLConnection — no networking library — to keep the APK tiny.
 */
object AiBrain {

    private const val TAG = "Ammamma"

    // OpenRouter-only fallback: free models get rate-limited (429), so we give
    // OpenRouter many to roll through, ending with "openrouter/free" which
    // auto-picks any that's available. Warm-at-Telugu ones first. Used ONLY when
    // the family hasn't picked a model in Settings.
    private val FALLBACK_MODELS = listOf(
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

    /**
     * [text] is what grandma hears (warm Telugu). [detail] is for the FAMILY:
     * the real HTTP status + response body, shown in the Settings "Test AI"
     * dialog and small on the Talk screen — so "busy" can never again hide a
     * wrong key or a dead model.
     */
    data class Result(val ok: Boolean, val text: String, val detail: String = "")

    /**
     * Ask the brain. BLOCKING network call — run OFF the main thread.
     * [extraContext] can carry facts (e.g. today's weather) for the model to phrase warmly.
     */
    fun ask(context: Context, userText: String, extraContext: String? = null): Result {
        val apiKey = Settings.aiKey(context)
        if (apiKey.isBlank()) {
            return Result(false, "AI సెటప్ కావాలి", "No API key saved in Settings")
        }
        val base = Settings.aiBaseUrl(context)
        val model = Settings.aiModel(context)

        val body = JSONObject()
        when {
            model.isNotBlank() -> body.put("model", model)
            // No model chosen: the multi-model "models" field is OpenRouter-only.
            base.contains("openrouter") -> body.put("models", JSONArray(FALLBACK_MODELS))
            else -> return Result(
                false, "AI మోడల్ ఎంచుకోవాలి",
                "No model selected. Tap 'Get models' in Settings and pick one — " +
                    "required for providers other than OpenRouter."
            )
        }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        if (!extraContext.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", extraContext))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        body.put("messages", messages)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                Log.e(TAG, "AI HTTP $code: ${raw.take(400)}")
                // Each failure gets its OWN spoken line: collapsing everything
                // into "busy" once hid a key/model problem for days.
                val spoken = when (code) {
                    401, 403 -> "AI తాళం సరిగ్గా లేదు, ఇంట్లో వాళ్ళు చూడాలి"       // key wrong
                    402 -> "AI ఖాతా నిండిపోయింది, ఇంట్లో వాళ్ళు చూడాలి"             // out of credits
                    400, 404 -> "ఈ AI మోడల్ పని చేయడం లేదు, ఇంట్లో వాళ్ళు చూడాలి"   // bad/missing model
                    429 -> "ఇప్పుడు అందరూ బిజీగా ఉన్నారు, కొద్దిసేపటి తర్వాత అడగండి" // truly busy
                    else -> "AI ఇప్పుడు పని చేయడం లేదు"
                }
                return Result(false, spoken, "HTTP $code from $base\n${raw.take(400)}")
            }

            val reply = JSONObject(raw)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            Log.i(TAG, "AI reply: $reply")
            Result(true, reply)
        } catch (e: Exception) {
            Log.e(TAG, "AI call failed", e)
            Result(false, "ఇప్పుడు మాట్లాడలేను", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** What the provider's /models endpoint returned. */
    data class Models(val ok: Boolean, val ids: List<String> = emptyList(), val error: String = "")

    /**
     * Ask the provider which models this key can use (GET /models — same shape on
     * OpenRouter, OpenAI, Groq…). BLOCKING — run off the main thread. Free models
     * are sorted first because that's what this family runs on.
     */
    fun fetchModels(context: Context): Models {
        val apiKey = Settings.aiKey(context)
        if (apiKey.isBlank()) return Models(false, error = "No API key saved yet — paste it first.")
        val base = Settings.aiBaseUrl(context)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                Log.e(TAG, "Models HTTP $code: ${raw.take(300)}")
                return Models(false, error = "HTTP $code from $base/models\n${raw.take(300)}")
            }
            val data = JSONObject(raw).getJSONArray("data")
            val ids = (0 until data.length())
                .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
                .sortedWith(compareBy({ !it.endsWith(":free") }, { it }))
            if (ids.isEmpty()) Models(false, error = "Provider returned an empty model list.")
            else Models(true, ids)
        } catch (e: Exception) {
            Log.e(TAG, "Model list failed", e)
            Models(false, error = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
