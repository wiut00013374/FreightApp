package com.example.freightapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.freightapp.R
import com.example.freightapp.model.Message

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<MessageAdapter.MsgViewHolder>() {

    class MsgViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvMessageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MsgViewHolder(view)
    }

    override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
        val msg = messages[position]
        holder.tvMessage.text = msg.text
        // if you want to style "my" messages vs "others", you can check msg.senderUid
    }

    override fun getItemCount(): Int = messages.size
}
