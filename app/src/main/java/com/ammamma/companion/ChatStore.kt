package com.ammamma.companion

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stored chat sessions for the Talk companion.
 *
 * Every conversation is one small JSON file in filesDir/chats/<id>.json:
 *   { id, title, createdAt, updatedAt, messages:[ {role, content, ts}, … ] }
 *
 * Plain org.json only — no libraries (the APK must stay tiny). Sessions give the
 * brain MEMORY (prior turns are replayed) and let the family scroll back through
 * what was said. Old chats auto-expire (see [pruneOlderThan]) if the family leaves
 * that setting on.
 */
object ChatStore {

    private const val TAG = "Ammamma"
    private const val DIR = "chats"
    private const val TITLE_MAX = 40

    /** One line of a conversation. role is "user" (Ammamma) or "assistant" (companion). */
    data class Message(val role: String, val content: String, val ts: Long)

    /** A whole conversation. [messages] is mutable so a live screen can append to it. */
    class Session(
        val id: String,
        var title: String,
        val createdAt: Long,
        var updatedAt: Long,
        val messages: MutableList<Message>
    )

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!isDirectory) mkdirs() }

    private fun fileFor(context: Context, id: String) = File(dir(context), "$id.json")

    /** A brand-new, empty session held only in memory until the first message is saved. */
    fun newSession(): Session {
        val now = System.currentTimeMillis()
        val id = "${now}_${(1000..9999).random()}"
        return Session(id, "", now, now, mutableListOf())
    }

    /**
     * Append one line, refresh the timestamp, and (for her first line) title the chat.
     * Does NOT write to disk — call [save] after, so a burst of edits is one write.
     */
    fun addMessage(session: Session, role: String, content: String) {
        val now = System.currentTimeMillis()
        session.messages.add(Message(role, content, now))
        session.updatedAt = now
        if (session.title.isBlank() && role == "user" && content.isNotBlank()) {
            session.title = content.trim().let {
                if (it.length > TITLE_MAX) it.take(TITLE_MAX).trim() + "…" else it
            }
        }
    }

    /**
     * The recent history to send to the model, oldest first, capped so a long chat
     * never blows past the model's context: at most [maxMessages] messages AND at
     * most [maxChars] characters, dropping the OLDEST first. Returned as (role,
     * content) pairs ready to drop into the messages array.
     */
    fun historyForApi(
        session: Session,
        maxMessages: Int = 16,
        maxChars: Int = 6000
    ): List<Pair<String, String>> {
        val recent = ArrayList<Message>()
        var chars = 0
        // Walk newest → oldest, keep until a cap is hit, then reverse to oldest → newest.
        for (m in session.messages.asReversed()) {
            if (recent.size >= maxMessages) break
            if (chars + m.content.length > maxChars && recent.isNotEmpty()) break
            recent.add(m)
            chars += m.content.length
        }
        return recent.asReversed().map { it.role to it.content }
    }

    fun save(context: Context, session: Session) {
        try {
            val msgs = JSONArray()
            session.messages.forEach {
                msgs.put(
                    JSONObject()
                        .put("role", it.role)
                        .put("content", it.content)
                        .put("ts", it.ts)
                )
            }
            val obj = JSONObject()
                .put("id", session.id)
                .put("title", session.title)
                .put("createdAt", session.createdAt)
                .put("updatedAt", session.updatedAt)
                .put("messages", msgs)
            fileFor(context, session.id).writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Chat save failed for ${session.id}", e)
        }
    }

    fun load(context: Context, id: String): Session? {
        val f = fileFor(context, id)
        if (!f.isFile) return null
        return try { parse(JSONObject(f.readText())) } catch (e: Exception) {
            Log.w(TAG, "Chat load failed for $id", e); null
        }
    }

    private fun parse(obj: JSONObject): Session {
        val msgs = mutableListOf<Message>()
        val arr = obj.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            msgs.add(Message(m.optString("role"), m.optString("content"), m.optLong("ts")))
        }
        val created = obj.optLong("createdAt", System.currentTimeMillis())
        return Session(
            obj.optString("id"),
            obj.optString("title"),
            created,
            obj.optLong("updatedAt", created),
            msgs
        )
    }

    /** All saved sessions, newest activity first. Corrupt files are skipped, not fatal. */
    fun listSessions(context: Context): List<Session> {
        val files = dir(context).listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            try { parse(JSONObject(f.readText())) } catch (e: Exception) { null }
        }.sortedByDescending { it.updatedAt }
    }

    /** Delete ONE chat. Never touches any other file. */
    fun delete(context: Context, id: String) {
        try { fileFor(context, id).delete() } catch (e: Exception) {
            Log.w(TAG, "Chat delete failed for $id", e)
        }
    }

    /** Remove sessions untouched for longer than [maxAgeMillis]. Returns how many went. */
    fun pruneOlderThan(context: Context, maxAgeMillis: Long): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        var removed = 0
        (dir(context).listFiles { f -> f.extension == "json" } ?: return 0).forEach { f ->
            val old = try {
                JSONObject(f.readText()).optLong("updatedAt", Long.MAX_VALUE) < cutoff
            } catch (e: Exception) {
                // Unreadable/truncated (e.g. a write that hit a full disk) — it can never
                // be parsed to prove its age, so drop it instead of leaking it forever.
                Log.w(TAG, "Corrupt chat ${f.name} — removing", e)
                if (f.delete()) removed++
                false
            }
            if (old && f.delete()) removed++
        }
        if (removed > 0) Log.i(TAG, "Pruned $removed old chat(s)")
        return removed
    }
}
