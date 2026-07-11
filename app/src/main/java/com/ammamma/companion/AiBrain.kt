package com.ammamma.companion

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The companion's brain. Sends what Ammamma said to an OpenAI-compatible chat API
 * and gets back a warm, complete Telugu reply — or a small command (call / open app /
 * show a video / ask a clarifying question).
 *
 * Provider-agnostic and MULTI-KEY: the family can paste one or more keys (Groq,
 * OpenRouter, OpenAI…). The provider is detected from each key's prefix, so no URL
 * to type. We try the keys in order and fall through to the next on a rate-limit or
 * failure — a second free key is a cheap safety net. If a key has no chosen model,
 * "Auto" picks the best chat model from that provider's live list.
 *
 * Uses plain HttpURLConnection — no networking library — to keep the APK tiny.
 */
object AiBrain {

    private const val TAG = "Ammamma"

    // Auto-pick is cached per base URL so we only pay the extra /models call once per
    // app run, not on every message. Cleared when the family edits the AI config.
    private val autoModelCache = HashMap<String, String>()

    fun forgetAutoModel() = autoModelCache.clear()

    /**
     * Plain-chat prompt. Ammamma is intelligent and deserves a real answer, not a
     * clipped one: a recipe or a story gets the FULL, complete reply (every step, in
     * order, with quantities and timing); a greeting gets one warm line. The model
     * must never pad a small answer and never truncate a real one, and must never
     * just be honest when it does not know or cannot do something.
     */
    private const val SYSTEM_PROMPT =
        "You are Ammamma's warm, intelligent companion — like a caring, respectful family member. " +
        "Ammamma is an elderly Telugu grandmother who cannot read and speaks only Telugu. She is " +
        "sharp and fully capable; never talk down to her and never sound like you are humoring a " +
        "child. ALWAYS reply ONLY in natural, spoken Telugu script — never English words, never " +
        "Latin letters, never markdown, never emoji, never numbered or bulleted lists (say them as " +
        "flowing spoken sentences instead, e.g. \"మొదట…, తరువాత…, ఆఖరున…\"). " +
        "Match your LENGTH to her question: a greeting or small remark gets one warm line; a recipe, " +
        "a story, or instructions get the FULL, complete answer with every step, quantity, order and " +
        "timing she needs. Never cut a real answer short to be brief, and never pad a simple answer " +
        "with filler. Be honest above all: if you do not know something, or something is impossible, " +
        "or a feature is not on this phone, say so plainly and warmly — never invent an answer, and " +
        "never claim you already did something you did not do. Never simply repeat her own words " +
        "back to her as your reply. You can talk about cooking, family, health, devotion, stories, " +
        "news, and daily life."

    /**
     * [text] is what grandma hears (warm Telugu). [detail] is for the FAMILY: the
     * real HTTP status + body, shown in Settings "Test AI" — so "busy" can never
     * again hide a wrong key or a dead model.
     */
    data class Result(val ok: Boolean, val text: String, val detail: String = "")

    // ---------------------------------------------------------------------------
    // Plain chat
    // ---------------------------------------------------------------------------

    /**
     * Ask the brain for a warm Telugu reply. BLOCKING — run OFF the main thread.
     * [history] is prior (role, content) turns of this session, oldest first — see
     * ChatStore.historyForApi — so a recipe half-explained a minute ago is still in
     * context. [extraContext] can carry facts (e.g. today's weather) for the model
     * to phrase.
     */
    fun ask(
        context: Context,
        userText: String,
        history: List<Pair<String, String>> = emptyList(),
        extraContext: String? = null
    ): Result {
        val messages = JSONArray()
        messages.put(msg("system", SYSTEM_PROMPT))
        if (!extraContext.isNullOrBlank()) messages.put(msg("system", extraContext))
        history.forEach { (role, content) -> messages.put(msg(role, content)) }
        messages.put(msg("user", userText))
        return runAcrossAccounts(context, messages)
    }

    /**
     * Voice-assistant turn. The model is told the available contact NAMES (never
     * their numbers — privacy), the app names, and the current date/time, and must
     * reply with ONE compact JSON:
     *   {"action":"call","name":"<closest contact>"}
     *   {"action":"call","number":"<digits she said/typed>"}
     *   {"action":"open","app":"<app>"}
     *   {"action":"video","query":"<what to search for>"}
     *   {"action":"ask","say":"<one short clarifying Telugu question>"}
     *   {"action":"chat","say":"<full warm Telugu reply>"}
     * The raw reply comes back in [Result.text]; CommandRouter parses it.
     */
    fun assistant(
        context: Context,
        userText: String,
        contactNames: List<String>,
        appNames: List<String>,
        history: List<Pair<String, String>> = emptyList(),
        extraContext: String? = null
    ): Result {
        val now = SimpleDateFormat("yyyy-MM-dd (EEEE) HH:mm", Locale.US).format(Date())
        val sys =
            "You are the decision layer for Ammamma's companion phone. Ammamma is an elderly " +
            "Telugu grandmother who cannot read and speaks only Telugu — she is intelligent and " +
            "capable; treat her with full respect, never condescension. The PHONE performs every " +
            "action; you only DECIDE and describe what should happen, in Telugu, for her to hear. " +
            "Current date/time: $now.\n" +
            "Read what she said and reply with ONLY one compact JSON object, nothing else, no " +
            "explanation, no markdown fences:\n" +
            "- Call a person by name: {\"action\":\"call\",\"name\":\"<closest name from her contacts list>\"}\n" +
            "- Call a specific number she said or typed: {\"action\":\"call\",\"number\":\"<digits only>\"}\n" +
            "- Open an app: {\"action\":\"open\",\"app\":\"<app name, lowercase english>\"}\n" +
            "- Show a video: {\"action\":\"video\",\"query\":\"<what to search for, in her words>\"}\n" +
            "- You need ONE more detail before acting, e.g. she hinted at wanting to watch " +
            "something but did not say to open a video: " +
            "{\"action\":\"ask\",\"say\":\"<one short, warm, spoken Telugu question>\"}\n" +
            "- Anything else — chat, questions, feelings, recipes, weather, stories: " +
            "{\"action\":\"chat\",\"say\":\"<your full warm spoken Telugu reply — complete step-by-step " +
            "instructions if she asked for a recipe or how to do something, one short line if it is " +
            "small talk>\"}\n" +
            "Her contacts: [" + contactNames.joinToString(", ") + "]. " +
            "Apps on this phone: [" + appNames.joinToString(", ") + "]. " +
            "Never invent a contact who is not in that list — if you are not sure which contact she " +
            "means, use \"ask\" instead of guessing. Never claim the phone already did something; " +
            "only describe what should happen next. If she asks for something impossible, unknown, " +
            "or not on this phone, say so honestly inside \"say\" — never bluff, never invent. Never " +
            "just repeat her own words back as the reply. Reply with the JSON object only."
        val messages = JSONArray()
        messages.put(msg("system", sys))
        if (!extraContext.isNullOrBlank()) messages.put(msg("system", extraContext))
        history.forEach { (role, content) -> messages.put(msg(role, content)) }
        messages.put(msg("user", userText))
        return runAcrossAccounts(context, messages)
    }

    /** Try each account in order; first success wins, else return the last failure. */
    private fun runAcrossAccounts(context: Context, messages: JSONArray): Result {
        val accounts = Settings.aiAccounts(context)
        if (accounts.isEmpty()) return Result(false, "AI సెటప్ కావాలి", "No API key saved in Settings")

        var last = Result(false, "AI ఇప్పుడు పని చేయడం లేదు", "No account attempted")
        for (acc in accounts) {
            val model = acc.model.ifBlank { autoPickModel(acc.key, acc.base) }
            if (model.isBlank()) {
                last = Result(
                    false, "AI మోడల్ ఎంచుకోవాలి",
                    "Couldn't reach ${acc.base} to auto-pick a model for the ${acc.providerLabel} key. " +
                        "Check the key, or tap 'Get models' and choose one."
                )
                continue
            }
            val r = chat(acc.base, acc.key, model, messages)
            if (r.ok) return r
            last = r   // remember why, then fall through to the next key
        }
        return last
    }

    private fun chat(base: String, apiKey: String, model: String, messages: JSONArray): Result {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 1024)   // a recipe or a story must never be cut off mid-sentence

        var conn: HttpURLConnection? = null
        return try {
            conn = open("$base/chat/completions", apiKey, "POST")
            conn.doOutput = true
            conn.readTimeout = 45000
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val raw = readBody(conn, code)
            if (code !in 200..299) {
                Log.e(TAG, "AI HTTP $code: ${raw.take(400)}")
                // Each failure gets its OWN spoken line: collapsing everything into
                // "busy" once hid a key/model problem for days.
                val spoken = when (code) {
                    401, 403 -> "AI తాళం సరిగ్గా లేదు, ఇంట్లో వాళ్ళు చూడాలి"       // key wrong
                    402 -> "AI ఖాతా నిండిపోయింది, ఇంట్లో వాళ్ళు చూడాలి"             // out of credits
                    400, 404 -> "ఈ AI మోడల్ పని చేయడం లేదు, ఇంట్లో వాళ్ళు చూడాలి"   // bad/missing model
                    429 -> "ఇప్పుడు అందరూ బిజీగా ఉన్నారు, కొద్దిసేపటి తర్వాత అడగండి" // truly busy
                    else -> "AI ఇప్పుడు పని చేయడం లేదు"
                }
                return Result(false, spoken, "HTTP $code from $base ($model)\n${raw.take(400)}")
            }

            val reply = JSONObject(raw)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            Log.i(TAG, "AI reply ($model): $reply")
            Result(true, reply)
        } catch (e: Exception) {
            Log.e(TAG, "AI call failed", e)
            Result(false, "ఇప్పుడు మాట్లాడలేను", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------------------
    // Model list + auto-pick
    // ---------------------------------------------------------------------------

    private fun autoPickModel(key: String, base: String): String {
        autoModelCache[base]?.let { return it }
        val models = fetchModels(key)
        val best = if (models.ok) bestChatModel(models.ids) else null
        if (best != null) {
            autoModelCache[base] = best
            Log.i(TAG, "Auto-picked model for $base: $best")
            return best
        }
        Log.w(TAG, "Auto-pick failed for $base: ${models.error}")
        return ""
    }

    /**
     * Models validated (or clearly sensible) for warm Telugu chat + reliable command
     * JSON, in preference order. A curated pick beats a raw score because we've
     * actually heard these talk; the score is the fallback when none are present.
     */
    private val PREFERRED = listOf(
        "llama-3.3-70b-versatile",   // Groq — warmest Telugu + best intent (tested)
        "llama-3.1-70b",
        "gpt-oss-120b",
        "qwen3.6-27b", "qwen3-32b",
        "llama-4-scout",
        "gemma-4-31b", "gemma-3-27b", "gemma-4",
        "llama-3.1-8b-instant",
    )

    /**
     * Best chat model for warm Telugu. Skips non-chat models (speech, embeddings,
     * safety classifiers). On OpenRouter-style lists (some ids end ":free") we stay
     * on the free tier. Then a curated preference wins; else a rough score.
     */
    fun bestChatModel(ids: List<String>): String? {
        val notChat = listOf(
            "whisper", "tts", "embed", "guard", "safeguard", "orpheus",
            "rerank", "moderation", "vision", "image", "audio", "prompt-guard"
        )
        var candidates = ids.filter { id -> notChat.none { id.lowercase().contains(it) } }
        if (candidates.isEmpty()) return null
        // If this provider exposes a free tier, only ever pick from it (budget).
        val free = candidates.filter { it.endsWith(":free") }
        if (free.isNotEmpty()) candidates = free

        for (want in PREFERRED) {
            candidates.firstOrNull { it.lowercase().contains(want) }?.let { return it }
        }
        return candidates.maxByOrNull { score(it) }
    }

    private fun score(id: String): Int {
        val s = id.lowercase()
        var n = 0
        if ("versatile" in s) n += 40
        if ("instruct" in s || s.endsWith("-it") || "-it:" in s || "chat" in s) n += 25
        if ("llama" in s || "gemma" in s || "qwen" in s || "gpt-oss" in s) n += 15
        Regex("(\\d+)b").findAll(s).map { it.groupValues[1].toIntOrNull() ?: 0 }
            .maxOrNull()?.let { n += it.coerceAtMost(200) }
        if (s.endsWith(":free")) n += 30
        return n
    }

    data class Models(val ok: Boolean, val ids: List<String> = emptyList(), val error: String = "")

    /**
     * Ask a provider which models this [apiKey] can use (GET /models — same shape on
     * OpenRouter, OpenAI, Groq…). BLOCKING — run off the main thread. The best chat
     * model is sorted first so the picker's top row is the one to tap.
     */
    fun fetchModels(apiKey: String): Models {
        val key = apiKey.trim()
        if (key.isEmpty()) return Models(false, error = "No API key — paste it first.")
        val base = Settings.baseForKey(key)

        var conn: HttpURLConnection? = null
        return try {
            conn = open("$base/models", key, "GET")
            val code = conn.responseCode
            val raw = readBody(conn, code)
            if (code !in 200..299) {
                Log.e(TAG, "Models HTTP $code: ${raw.take(300)}")
                return Models(false, error = "HTTP $code from $base/models\n${raw.take(300)}")
            }
            val data = JSONObject(raw).getJSONArray("data")
            val best = bestChatModel(
                (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
            )
            val ids = (0 until data.length())
                .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
                .sortedWith(compareByDescending<String> { it == best }.thenByDescending { score(it) }.thenBy { it })
            if (ids.isEmpty()) Models(false, error = "Provider returned an empty model list.")
            else Models(true, ids)
        } catch (e: Exception) {
            Log.e(TAG, "Model list failed", e)
            Models(false, error = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------------------
    // Small HTTP helpers
    // ---------------------------------------------------------------------------

    private fun msg(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    private fun open(url: String, apiKey: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            // Some providers front their API with a bot filter that rejects the default
            // Java user-agent; a plain, honest UA sails through.
            setRequestProperty("User-Agent", "AmmammaCompanion/1.0")
        }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
    }
}
