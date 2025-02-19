package com.example.freightapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.model.Chat

class ChatAdapter(private val chats: List<Chat>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDriverName: TextView = itemView.findViewById(R.id.tvDriverName)
        val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chat = chats[position]
        holder.tvDriverName.text = chat.driverName
        holder.tvLastMessage.text = chat.lastMessage
        // Format the timestamp (in milliseconds) into a readable date/time
        holder.tvTimestamp.text = android.text.format.DateFormat.format("dd MMM, HH:mm", chat.timestamp)
    }

    override fun getItemCount(): Int = chats.size
}
