package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
     * @param order The order details
     * @return true if notification was sent successfully
     */
    suspend fun sendDriverOrderNotification(
        fcmToken: String,
        orderId: String,
        driverId: String,
        order: Order
    ): Boolean {
        try {
            // Format price to two decimal places
            val formattedPrice = String.format("$%.2f", order.totalPrice)

            // Calculate distance
            val distance = calculateDistance(
                order.originLat, order.originLon,
                // Use driver's current location (you'll need to pass this)
                // For now, use a placeholder
                order.originLat, order.originLon
            )

            // Create comprehensive notification data payload
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
                    "order_id" to orderId, // Note the different key for BroadcastReceiver
                    "driverId" to driverId,
                    "type" to "order_request",
                    "originCity" to order.originCity,
                    "destinationCity" to order.destinationCity,
                    "price" to formattedPrice,
                    "truckType" to order.truckType,
                    "volume" to order.volume.toString(),
                    "weight" to order.weight.toString(),
                    "distance" to String.format("%.2f", distance),
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

    // Add a helper method to calculate distance
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371.0 // Earth's radius in kilometers
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
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
     * Notify a driver that a customer has cancelled an order
     */
    suspend fun sendOrderCancellationNotification(
        driverId: String,
        orderId: String,
        message: String
    ): Boolean {
        try {
            // Get driver FCM token
            val driverDoc = firestore.collection("users").document(driverId).get().await()
            val fcmToken = driverDoc.getString("fcmToken")

            if (fcmToken.isNullOrEmpty()) {
                Log.e(TAG, "Driver $driverId has no FCM token")
                return false
            }

            // Create notification data payload
            val notificationData = hashMapOf(
                "to" to fcmToken,
                "priority" to "high",
                "notification" to hashMapOf(
                    "title" to "Order Cancelled",
                    "body" to message,
                    "sound" to "default"
                ),
                "data" to hashMapOf(
                    "orderId" to orderId,
                    "type" to "order_cancelled",
                    "click_action" to "OPEN_ORDER_DETAIL"
                )
            )

            // Send notification
            return sendFcmNotification(notificationData)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending cancellation notification: ${e.message}")
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
        // In a real app, this would be retrieved securely from a server
        // For this implementation, return a placeholder
        // Replace with your actual FCM server key for testing
        return "YOUR_FCM_SERVER_KEY"
    }
}