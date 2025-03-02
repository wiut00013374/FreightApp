package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.model.Chat
import com.example.freightapp.model.Message
import com.example.freightapp.model.Order
import com.example.freightapp.utils.NotificationHandler
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Service for handling Firebase Cloud Messaging notifications
 */
class FirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "FirebaseMsgService"
    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Called when a new FCM token is generated
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")

        // Use coroutine to update token
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseV1ApiService.updateUserToken(token)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating FCM token: ${e.message}")
            }
        }
    }

    /**
     * Called when a message is received
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Message received from: ${remoteMessage.from}")

        // Check if the message contains data
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Message data: ${remoteMessage.data}")

            // Handle based on notification type
            when (val type = remoteMessage.data["type"]) {
                "chat_message" -> handleChatMessage(remoteMessage.data)
                "order_update" -> handleOrderUpdate(remoteMessage.data)
                "pickup_update" -> handlePickupUpdate(remoteMessage.data)
                "delivery_update" -> handleDeliveryUpdate(remoteMessage.data)
                else -> Log.d(TAG, "Unknown notification type: $type")
            }
        }

        // Check if the message contains a notification
        remoteMessage.notification?.let {
            Log.d(TAG, "Message notification body: ${it.body}")
            // You can handle the notification here if needed
        }
    }

    /**
     * Handle a chat message notification
     */
    private fun handleChatMessage(data: Map<String, String>) {
        val chatId = data["chatId"] ?: return
        val messageId = data["messageId"] ?: return

        // Fetch the chat and message details from Firestore
        firestore.collection("chats")
            .document(chatId)
            .get()
            .addOnSuccessListener { chatDoc ->
                val chat = chatDoc.toObject(Chat::class.java)

                if (chat != null) {
                    // Fetch the specific message
                    firestore.collection("chats")
                        .document(chatId)
                        .collection("messages")
                        .document(messageId)
                        .get()
                        .addOnSuccessListener { messageDoc ->
                            val message = messageDoc.toObject(Message::class.java)

                            if (message != null) {
                                // Show notification
                                NotificationHandler.showChatMessageNotification(
                                    context = applicationContext,
                                    message = message,
                                    chat = chat
                                )
                            }
                        }
                }
            }
    }

    /**
     * Handle an order update notification
     */
    private fun handleOrderUpdate(data: Map<String, String>) {
        val orderId = data["orderId"] ?: return
        val status = data["status"] ?: "Updated"

        // Fetch the order details from Firestore
        firestore.collection("orders")
            .document(orderId)
            .get()
            .addOnSuccessListener { orderDoc ->
                val order = orderDoc.toObject(Order::class.java)

                if (order != null) {
                    // Show notification
                    NotificationHandler.showOrderUpdateNotification(
                        context = applicationContext,
                        order = order
                    )

                    // If the status is "In Progress", also update the UI in real-time
                    if (status == "In Progress") {
                        NotificationService.sendOrderStatusNotification(
                            context = applicationContext,
                            orderId = orderId,
                            title = "Driver is on the way",
                            message = "Your driver has started the trip and is heading to pickup your freight"
                        )
                    }
                }
            }
    }

    /**
     * Handle a pickup update notification
     */
    private fun handlePickupUpdate(data: Map<String, String>) {
        val orderId = data["orderId"] ?: return

        // Send a pickup notification
        NotificationService.sendPickupNotification(
            context = applicationContext,
            orderId = orderId
        )
    }

    /**
     * Handle a delivery update notification
     */
    private fun handleDeliveryUpdate(data: Map<String, String>) {
        val orderId = data["orderId"] ?: return

        // Send a delivery notification
        NotificationService.sendDeliveryNotification(
            context = applicationContext,
            orderId = orderId
        )
    }
}