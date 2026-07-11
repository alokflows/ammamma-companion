package com.ammamma.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every past Talk chat, newest first: title + a relative date, tap to reopen it,
 * a per-row ✕ to delete just that one (with a confirm — deleting one chat never
 * touches any other, per ChatStore.delete). Family/verification screen mostly —
 * Ammamma reaches this only via "చాట్లు" in the Talk header.
 */
class ChatListActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var sessions: List<ChatStore.Session> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_list)
        listView = findViewById(R.id.sessionList)
        emptyView = findViewById(R.id.empty)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        reload()
    }

    private fun reload() {
        sessions = ChatStore.listSessions(this)
        emptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        listView.adapter = SessionAdapter()
    }

    private fun confirmDelete(session: ChatStore.Session) {
        AlertDialog.Builder(this)
            .setMessage("ఈ చాట్ తీసేయాలా?")
            .setPositiveButton("తీసేయి") { _, _ ->
                ChatStore.delete(this, session.id)
                reload()
            }
            .setNegativeButton("వద్దు", null)
            .show()
    }

    private inner class SessionAdapter : BaseAdapter() {
        override fun getCount() = sessions.size
        override fun getItem(position: Int) = sessions[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@ChatListActivity).inflate(R.layout.item_chat_session, parent, false)
            val session = sessions[position]
            view.findViewById<TextView>(R.id.sessionTitle).text =
                session.title.ifBlank { "పేరు లేని చాట్" }
            view.findViewById<TextView>(R.id.sessionDate).text = relativeLabel(session.updatedAt)
            view.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION_ID, session.id))
                finish()
            }
            view.findViewById<View>(R.id.sessionDelete).setOnClickListener { confirmDelete(session) }
            return view
        }
    }

    private fun relativeLabel(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < 2 * minute -> "ఇప్పుడే"
            diff < hour -> "${diff / minute} నిమిషాల క్రితం"
            diff < day -> "${diff / hour} గంటల క్రితం"
            diff < 2 * day -> "నిన్న"
            diff < 30 * day -> "${diff / day} రోజుల క్రితం"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(ts))
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
