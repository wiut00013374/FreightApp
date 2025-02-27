package com.example.freightapp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.model.Message
import com.google.firebase.auth.FirebaseAuth

/**
 * Adapter for displaying messages in a chat conversation.
 * Handles different layouts for sent vs received messages.
 */
class MessageAdapter(
    private val messages: List<Message>,
    private val context: Context
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

    // Base view holder for messages
    abstract class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(message: Message)
    }

    // View holder for messages sent by the current user
    inner class SentMessageViewHolder(itemView: View) : MessageViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)
        private val readIndicator: View = itemView.findViewById(R.id.readIndicator)

        override fun bind(message: Message) {
            messageText.text = message.text
            timeText.text = formatTime(message.timestamp)
            readIndicator.visibility = if (message.isRead) View.VISIBLE else View.INVISIBLE

            // Style sent messages (right-aligned, different background color)
            itemView.background = ContextCompat.getDrawable(context, R.drawable.bg_message_sent)
        }
    }

    // View holder for messages received from other users
    inner class ReceivedMessageViewHolder(itemView: View) : MessageViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)

        override fun bind(message: Message) {
            messageText.text = message.text
            timeText.text = formatTime(message.timestamp)

            // Style received messages (left-aligned, different background color)
            itemView.background = ContextCompat.getDrawable(context, R.drawable.bg_message_received)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        return if (message.senderUid == currentUserId) {
            VIEW_TYPE_SENT
        } else {
            VIEW_TYPE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        return if (viewType == VIEW_TYPE_SENT) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message_sent, parent, false)
            SentMessageViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message_received, parent, false)
            ReceivedMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message)
    }

    override fun getItemCount(): Int = messages.size

    // Helper method to format timestamp into readable time
    private fun formatTime(timestamp: Long): String {
        val dateFormat = android.text.format.DateFormat.getTimeFormat(context)
        return dateFormat.format(timestamp)
    }

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }
}