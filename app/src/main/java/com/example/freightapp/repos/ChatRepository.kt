package com.example.freightapp.repos

import android.util.Log
import com.example.freightapp.model.Chat
import com.example.freightapp.model.Message
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

/**
 * Repository for managing all chat-related operations with Firestore
 */
object ChatRepository {

    private val TAG = "ChatRepository"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /**
     * Creates a new chat or gets an existing one between a driver and customer for a specific order
     */
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

        // Create a unique chat ID from order and participants
        val chatId = "${orderId}_${driverUid}_$customerUid"
        val docRef = firestore.collection("chats").document(chatId)

        docRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                // Chat already exists, just return its ID
                onComplete(chatId)
            } else {
                // Create a new chat document
                val participants = arrayListOf(driverUid, customerUid)

                // First get the driver's name if available
                getDriverName(driverUid) { driverName ->
                    // Create a data map that includes the participants array and driver name
                    val chat = Chat(
                        id = chatId,
                        orderId = orderId,
                        driverUid = driverUid,
                        driverName = driverName,  // Store driver name in chat document
                        customerUid = customerUid,
                        timestamp = System.currentTimeMillis()
                    )

                    val chatData = mapOf(
                        "id" to chat.id,
                        "orderId" to chat.orderId,
                        "driverUid" to chat.driverUid,
                        "driverName" to chat.driverName,
                        "customerUid" to chat.customerUid,
                        "timestamp" to chat.timestamp,
                        "lastMessage" to chat.lastMessage,
                        "unreadCount" to 0,
                        "participants" to participants
                    )

                    // Save to Firestore
                    docRef.set(chatData)
                        .addOnSuccessListener { onComplete(chatId) }
                        .addOnFailureListener {
                            Log.e(TAG, "Error creating chat: ${it.message}")
                            onComplete(null)
                        }
                }
            }
        }.addOnFailureListener {
            Log.e(TAG, "Error checking for existing chat: ${it.message}")
            onComplete(null)
        }
    }

    /**
     * Helper function to get a driver's name from their profile
     */
    private fun getDriverName(driverUid: String, onComplete: (String) -> Unit) {
        firestore.collection("users")
            .document(driverUid)
            .get()
            .addOnSuccessListener { doc ->
                // Try different possible field names for the driver name
                val name = doc.getString("displayName")
                    ?: doc.getString("driverName")
                    ?: doc.getString("fullName")
                    ?: "Driver"
                onComplete(name)
            }
            .addOnFailureListener {
                onComplete("Driver")
            }
    }

    /**
     * Listen for all chats involving the current user
     */
    fun listenForUserChats(userId: String, onChatsChanged: (List<Chat>) -> Unit) {
        // Query chats where user is either driver or customer
        val query = firestore.collection("chats")
            .whereArrayContains("participants", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)

        query.addSnapshotListener { snapshots, error ->
            if (error != null) {
                Log.e(TAG, "Error listening for chats: ${error.message}")
                onChatsChanged(emptyList())
                return@addSnapshotListener
            }

            val chats = snapshots?.documents?.mapNotNull { doc ->
                try {
                    doc.toObject(Chat::class.java)?.apply {
                        id = doc.id
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting chat document: ${e.message}")
                    null
                }
            } ?: emptyList()

            onChatsChanged(chats)
        }
    }

    /**
     * Send a message in a chat
     */
    fun sendMessage(chatId: String, text: String, onComplete: (Boolean) -> Unit) {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null || chatId.isBlank() || text.isBlank()) {
            onComplete(false)
            return
        }

        // Get a reference to the chat document and messages collection
        val chatDocRef = firestore.collection("chats").document(chatId)
        val messageCollectionRef = chatDocRef.collection("messages")

        // Create a new message document
        val messageDocRef = messageCollectionRef.document()
        val message = Message(
            id = messageDocRef.id,
            chatId = chatId,
            senderUid = currentUid,
            text = text,
            timestamp = System.currentTimeMillis(),
            isRead = false
        )

        // Use a batch to ensure atomicity for both operations
        firestore.runBatch { batch ->
            // Add the message
            batch.set(messageDocRef, message)

            // Update the chat document with last message and timestamp
            val chatUpdates = hashMapOf<String, Any>(
                "lastMessage" to text,
                "timestamp" to message.timestamp
            )

            // If the sender is not the recipient, increment unread count
            chatDocRef.get().addOnSuccessListener { chatDoc ->
                val chat = chatDoc.toObject(Chat::class.java)
                if (chat != null) {
                    // Determine who to increment unread count for
                    val recipientUid = if (currentUid == chat.driverUid) chat.customerUid else chat.driverUid

                    // Update the unread count only for the recipient
                    chatUpdates["unreadCount"] = FieldValue.increment(1)

                    // Update the chat document
                    chatDocRef.update(chatUpdates)
                        .addOnSuccessListener { onComplete(true) }
                        .addOnFailureListener {
                            Log.e(TAG, "Error updating chat: ${it.message}")
                            onComplete(false)
                        }
                }
            }
        }.addOnSuccessListener {
            onComplete(true)
        }.addOnFailureListener {
            Log.e(TAG, "Error sending message: ${it.message}")
            onComplete(false)
        }
    }

    /**
     * Listen for messages in a specific chat
     */
    fun listenForMessages(chatId: String, onMessagesChanged: (List<Message>) -> Unit) {
        firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening for messages: ${error.message}")
                    onMessagesChanged(emptyList())
                    return@addSnapshotListener
                }

                val messages = snapshots?.documents?.mapNotNull { doc ->
                    doc.toObject(Message::class.java)?.apply {
                        id = doc.id
                    }
                } ?: emptyList()

                onMessagesChanged(messages)
            }
    }

    fun getChatDetails(chatId: String, onComplete: (Chat?) -> Unit) {
        firestore.collection("chats")
            .document(chatId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val chat = document.toObject(Chat::class.java)?.apply {
                        id = document.id
                    }

                    // If chat exists but has no driver name, try to fetch the driver name
                    if (chat != null && chat.driverName.isBlank() && chat.driverUid.isNotBlank()) {
                        getDriverName(chat.driverUid) { driverName ->
                            chat.driverName = driverName
                            // Update the chat document with the driver name
                            firestore.collection("chats").document(chatId)
                                .update("driverName", driverName)
                            onComplete(chat)
                        }
                    } else {
                        onComplete(chat)
                    }
                } else {
                    onComplete(null)
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Error getting chat details: ${it.message}")
                onComplete(null)
            }
    }

    /**
     * Mark all unread messages in a chat as read
     */
    fun markMessagesAsRead(chatId: String) {
        val currentUid = auth.currentUser?.uid ?: return
        val chatDocRef = firestore.collection("chats").document(chatId)

        // Get the chat to check if current user is recipient with unread messages
        chatDocRef.get().addOnSuccessListener { chatDoc ->
            val chat = chatDoc.toObject(Chat::class.java)

            if (chat != null) {
                // Only reset unread count if the current user is the recipient
                val isRecipient = (currentUid == chat.customerUid && chat.driverUid != currentUid) ||
                        (currentUid == chat.driverUid && chat.customerUid != currentUid)

                if (isRecipient && chat.unreadCount > 0) {
                    // Reset unread count
                    chatDocRef.update("unreadCount", 0)

                    // Mark all messages addressed to current user as read
                    firestore.collection("chats")
                        .document(chatId)
                        .collection("messages")
                        .whereEqualTo("isRead", false)
                        .whereNotEqualTo("senderUid", currentUid)
                        .get()
                        .addOnSuccessListener { snapshot ->
                            val batch = firestore.batch()
                            snapshot.documents.forEach { doc ->
                                batch.update(doc.reference, "isRead", true)
                            }

                            // Commit the batch
                            if (snapshot.documents.isNotEmpty()) {
                                batch.commit()
                            }
                        }
                }
            }
        }
    }
}