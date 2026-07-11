package com.ammamma.companion

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The Talk transcript: one bubble per message. Her lines sit on the right in a warm
 * accent bubble; the companion's sit on the left in a soft cream bubble. Backed
 * directly by the live [ChatStore.Session] messages list so a new line just needs
 * [notifyDataSetChanged].
 */
class ChatAdapter(
    private val context: Context,
    private val messages: List<ChatStore.Message>
) : BaseAdapter() {

    override fun getCount() = messages.size
    override fun getItem(position: Int) = messages[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_chat_bubble, parent, false)
        val bubble = view.findViewById<TextView>(R.id.bubbleText)
        val message = messages[position]
        val hers = message.role == "user"

        bubble.text = message.content
        val lp = FrameLayout.LayoutParams(bubble.layoutParams)
        lp.gravity = if (hers) android.view.Gravity.END else android.view.Gravity.START
        bubble.layoutParams = lp
        bubble.setBackgroundResource(if (hers) R.drawable.bubble_user else R.drawable.bubble_companion)
        bubble.setTextColor(Color.parseColor(if (hers) "#FFF4E8" else "#2A1A0F"))
        return view
    }
}
