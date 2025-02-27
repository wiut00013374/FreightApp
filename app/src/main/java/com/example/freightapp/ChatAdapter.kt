package com.example.freightapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.model.Chat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for displaying chat previews in the chat list.
 */
class ChatAdapter(
    private val chats: List<Chat>,
    private val onChatClicked: (Chat) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDriverName: TextView = itemView.findViewById(R.id.tvDriverName)
        val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        val tvUnreadCount: TextView = itemView.findViewById(R.id.tvUnreadCount)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChatClicked(chats[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chat = chats[position]

        // Display driver or customer name based on what's available
        holder.tvDriverName.text = chat.driverName.ifEmpty { "Driver" }

        // Display last message or placeholder
        holder.tvLastMessage.text = chat.lastMessage ?: "No messages yet"

        // Format timestamp
        holder.tvTimestamp.text = formatTimestamp(chat.timestamp)

        // Show unread count if there are unread messages
        if (chat.unreadCount > 0) {
            holder.tvUnreadCount.visibility = View.VISIBLE
            holder.tvUnreadCount.text = chat.unreadCount.toString()
        } else {
            holder.tvUnreadCount.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = chats.size

    /**
     * Format timestamp to show either time for today's messages,
     * or date for older messages
     */
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return ""

        val date = Date(timestamp)
        val now = Date()

        return if (isSameDay(date, now)) {
            // Today, show time
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        } else {
            // Different day, show date
            SimpleDateFormat("dd MMM", Locale.getDefault()).format(date)
        }
    }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return fmt.format(date1) == fmt.format(date2)
    }
}