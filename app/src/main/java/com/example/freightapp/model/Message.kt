package com.example.freightapp.model

import com.google.firebase.inappmessaging.model.MessageType

data class Message(
    var id: String = "",           // Firestore doc ID (assigned after push)
    val chatId: String = "",       // ID of the chat
    val senderUid: String = "",    // who sent it
    val text: String = "",         // message text
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false    // optional read receipt
)
