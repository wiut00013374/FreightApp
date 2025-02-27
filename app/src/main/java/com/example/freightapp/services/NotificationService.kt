package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Service for sending push notifications to drivers and customers
 */
object NotificationService {
    private const val TAG = "NotificationService"
    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    /**
     * Send an order notification to a driver
     * @param fcmToken The driver's FCM token
     * @param orderId The ID of the order
     * @param driverId The ID of the driver
     * @return true if notification was sent successfully
     */
    suspend fun sendDriverOrderNotification(
        fcmToken: String,
        orderId: String,
        driverId: String
    ): Boolean {
        try {
            // Get order details to include in notification
            val orderDoc = firestore.collection("orders").document(orderId).get().await()
            val order = orderDoc.toObject(Order::class.java)

            if (order == null) {
                Log.e(TAG, "Order $orderId not found")
                return false
            }

            // Format price to two decimal places
            val formattedPrice = String.format("$%.2f", order.totalPrice)

            // Create notification data payload
            val notificationData = hashMapOf(
                "to" to fcmToken,
                "priority" to "high",
                "notification" to hashMapOf(
                    "title" to "New Order Request",
                    "body" to "From ${order.originCity} to ${order.destinationCity} - $formattedPrice",
                    "sound" to "default"
                ),
                "data" to hashMapOf(
                    "orderId" to orderId,
                    "driverId" to driverId,
                    "type" to "order_request",
                    "click_action" to "OPEN_ORDER_DETAIL"
                )
            )

            // Send notification using FCM API directly
            return sendFcmNotification(notificationData)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending driver notification: ${e.message}")
            return false
        }
    }

    /**
     * Send a notification to a customer about their order
     */
    suspend fun sendCustomerOrderNotification(
        customerId: String,
        orderId: String,
        title: String,
        message: String
    ): Boolean {
        try {
            // Get customer FCM token
            val customerDoc = firestore.collection("users").document(customerId).get().await()
            val fcmToken = customerDoc.getString("fcmToken")

            if (fcmToken.isNullOrEmpty()) {
                Log.e(TAG, "Customer $customerId has no FCM token")
                return false
            }

            // Create notification data payload
            val notificationData = hashMapOf(
                "to" to fcmToken,
                "priority" to "high",
                "notification" to hashMapOf(
                    "title" to title,
                    "body" to message,
                    "sound" to "default"
                ),
                "data" to hashMapOf(
                    "orderId" to orderId,
                    "type" to "order_update",
                    "click_action" to "OPEN_ORDER_DETAIL"
                )
            )

            // Send notification
            return sendFcmNotification(notificationData)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending customer notification: ${e.message}")
            return false
        }
    }

    /**
     * Send a chat message notification
     */
    suspend fun sendChatMessageNotification(
        recipientId: String,
        chatId: String,
        senderId: String,
        senderName: String,
        message: String
    ): Boolean {
        try {
            // Get recipient FCM token
            val recipientDoc = firestore.collection("users").document(recipientId).get().await()
            val fcmToken = recipientDoc.getString("fcmToken")

            if (fcmToken.isNullOrEmpty()) {
                Log.e(TAG, "User $recipientId has no FCM token")
                return false
            }

            // Create notification data payload
            val notificationData = hashMapOf(
                "to" to fcmToken,
                "priority" to "high",
                "notification" to hashMapOf(
                    "title" to "New message from $senderName",
                    "body" to message,
                    "sound" to "default"
                ),
                "data" to hashMapOf(
                    "chatId" to chatId,
                    "senderId" to senderId,
                    "type" to "chat_message",
                    "click_action" to "OPEN_CHAT"
                )
            )

            // Send notification
            return sendFcmNotification(notificationData)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending chat notification: ${e.message}")
            return false
        }
    }

    /**
     * Send a notification using the FCM HTTP v1 API
     */
    private suspend fun sendFcmNotification(notificationData: Map<String, Any>): Boolean {
        return try {
            // Get FCM server key from Firebase console
            val fcmServerKey = getFcmServerKey()

            // Convert notification data to JSON
            val jsonPayload = JSONObject(notificationData).toString()

            // FCM API endpoint
            val url = URL("https://fcm.googleapis.com/fcm/send")
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "key=$fcmServerKey")
            connection.doOutput = true

            // Write JSON payload to the connection
            val outputStream = connection.outputStream
            outputStream.write(jsonPayload.toByteArray())
            outputStream.flush()
            outputStream.close()

            // Check response
            val responseCode = connection.responseCode
            if (responseCode == HttpsURLConnection.HTTP_OK || responseCode == HttpsURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "FCM Response: $response")
                true
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                Log.e(TAG, "FCM Error: $errorResponse")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending FCM notification: ${e.message}")
            false
        }
    }

    /**
     * Get the FCM server key from secure storage
     * In a production app, this would be stored securely or accessed via a backend API
     */
    private fun getFcmServerKey(): String {
        // WARNING: This is just a placeholder. In a real app, don't hardcode the FCM server key.
        // Use Firebase Cloud Functions or your own backend server to send notifications securely.
        return "YOUR_FCM_SERVER_KEY" // Replace with actual key or retrieval method
    }
}