package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

            // Create data payload for notification
            val notificationData = hashMapOf(
                "token" to fcmToken,
                "title" to "New Order Request",
                "body" to "From ${order.originCity} to ${order.destinationCity} - $formattedPrice",
                "orderId" to orderId,
                "driverId" to driverId,
                "type" to "order_request",
                "click_action" to "OPEN_ORDER_DETAIL"
            )

            // Call the Firebase Cloud Function to send notification
            return withContext(Dispatchers.IO) {
                try {
                    val result = functions.getHttpsCallable("sendDriverNotification")
                        .call(notificationData)
                        .await()

                    // Check if notification was sent successfully
                    val resultData = result.data as? Map<*, *>
                    val success = resultData?.get("success") as? Boolean ?: false

                    if (success) {
                        Log.d(TAG, "Successfully sent notification to driver $driverId for order $orderId")
                        true
                    } else {
                        Log.e(TAG, "Failed to send notification via cloud function")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling sendDriverNotification function: ${e.message}")

                    // Try alternative method if cloud function fails
                    sendFcmDirectly(fcmToken, order, orderId, driverId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing notification data: ${e.message}")
            return false
        }
    }

    /**
     * Send FCM notification directly using the FCM HTTP v1 API
     * This is a fallback method in case the Cloud Function fails
     */
    private suspend fun sendFcmDirectly(
        fcmToken: String,
        order: Order,
        orderId: String,
        driverId: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Get your FCM server key from Firebase console
                val fcmServerKey = "YOUR_FCM_SERVER_KEY" // Store securely, not hardcoded

                // Format price to two decimal places
                val formattedPrice = String.format("$%.2f", order.totalPrice)

                // Create FCM payload using the HTTP v1 API format
                val jsonPayload = JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("token", fcmToken)
                        put("notification", JSONObject().apply {
                            put("title", "New Order Request")
                            put("body", "From ${order.originCity} to ${order.destinationCity} - $formattedPrice")
                        })
                        put("data", JSONObject().apply {
                            put("orderId", orderId)
                            put("driverId", driverId)
                            put("type", "order_request")
                            put("click_action", "OPEN_ORDER_DETAIL")
                        })
                        put("android", JSONObject().apply {
                            put("priority", "high")
                            put("notification", JSONObject().apply {
                                put("click_action", "OPEN_ORDER_DETAIL")
                                put("channel_id", "order_requests_channel")
                            })
                        })
                    })
                }

                // FCM HTTP v1 API endpoint
                val url = URL("https://fcm.googleapis.com/v1/projects/your-project-id/messages:send")
                val connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $fcmServerKey")
                connection.doOutput = true

                // Write JSON payload to the connection
                val outputStream = connection.outputStream
                outputStream.write(jsonPayload.toString().toByteArray())
                outputStream.flush()
                outputStream.close()

                // Check response
                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    Log.d(TAG, "Successfully sent FCM directly to driver $driverId")
                    true
                } else {
                    val errorResponse = connection.errorStream.bufferedReader().use { it.readText() }
                    Log.e(TAG, "FCM direct send failed with code $responseCode: $errorResponse")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending FCM directly: ${e.message}")
                false
            }
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

            // Create data payload for notification
            val notificationData = hashMapOf(
                "token" to fcmToken,
                "title" to title,
                "body" to message,
                "orderId" to orderId,
                "type" to "order_update",
                "click_action" to "OPEN_ORDER_DETAIL"
            )

            // Call the Firebase Cloud Function to send notification
            val result = functions.getHttpsCallable("sendCustomerNotification")
                .call(notificationData)
                .await()

            // Check if notification was sent successfully
            val resultData = result.data as? Map<*, *>
            val success = resultData?.get("success") as? Boolean ?: false

            return success

        } catch (e: Exception) {
            Log.e(TAG, "Error sending customer notification: ${e.message}")
            return false
        }
    }
}