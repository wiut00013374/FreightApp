package com.example.freightapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.model.Message

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<MessageAdapter.MsgViewHolder>() {

    class MsgViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessageText: TextView = itemView.findViewById(R.id.tvMessageText)
        // if you want a tvSender, define it in item_message.xml as well
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MsgViewHolder(view)
    }

    override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
        val msg = messages[position]
        holder.tvMessageText.text = msg.text
        // optional: style if (msg.senderUid == currentUid) ...
    }

    override fun getItemCount(): Int = messages.size
}
