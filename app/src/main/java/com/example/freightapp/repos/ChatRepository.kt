package com.example.freightapp.repos

import com.example.freightapp.model.Message
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

object ChatRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // 1) Create or get existing chat doc
    fun createOrGetChat(
        orderId: String,
        driverUid: String,
        customerUid: String,
        onComplete: (String?) -> Unit
    ) {
        if (orderId.isBlank() || driverUid.isBlank() || customerUid.isBlank()) {
            onComplete(null)
            return
        }
        // build a chatId from these IDs so both apps get same doc
        val chatId = "${orderId}_${driverUid}_$customerUid"
        val docRef = firestore.collection("chats").document(chatId)

        docRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                onComplete(chatId)
            } else {
                // create a new doc if doesn't exist
                val data = mapOf(
                    "id" to chatId,
                    "orderId" to orderId,
                    "driverUid" to driverUid,
                    "customerUid" to customerUid
                )
                docRef.set(data)
                    .addOnSuccessListener { onComplete(chatId) }
                    .addOnFailureListener { onComplete(null) }
            }
        }.addOnFailureListener {
            onComplete(null)
        }
    }

    // 2) Listen for messages in real time
    fun listenForMessages(chatId: String, onMessagesChanged: (List<Message>) -> Unit) {
        firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    // error handling
                    return@addSnapshotListener
                }
                val messages = snapshots?.documents?.mapNotNull { doc ->
                    doc.toObject(Message::class.java)?.apply { id = doc.id }
                } ?: emptyList()
                onMessagesChanged(messages)
            }
    }

    // 3) Send a message to Firestore
    fun sendMessage(chatId: String, text: String, onComplete: (Boolean) -> Unit) {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null || chatId.isBlank() || text.isBlank()) {
            onComplete(false)
            return
        }
        val docRef = firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .document() // new doc
        val msg = Message(
            id = docRef.id,
            chatId = chatId,
            senderUid = currentUid,
            text = text,
            timestamp = System.currentTimeMillis(),
            isRead = false
        )
        docRef.set(msg)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    // 4) Optionally mark messages as read
    // (if you want read receipts)
    fun markMessagesAsRead(chatId: String) {
        val currentUid = auth.currentUser?.uid ?: return
        firestore.runTransaction { transaction ->
            val msgsQuery = firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .whereEqualTo("isRead", false)
                .whereNotEqualTo("senderUid", currentUid)
                .get()
                .result
            msgsQuery?.documents?.forEach { docSnap ->
                transaction.update(docSnap.reference, "isRead", true)
            }
        }
    }
}
