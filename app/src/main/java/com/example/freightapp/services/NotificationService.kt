package com.example.freightapp.services

import android.content.Context
import android.util.Log
import com.example.freightapp.model.Order
import com.example.freightapp.utils.FirebaseSecretsManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object NotificationService {
    private const val TAG = "NotificationService"
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun sendDriverOrderNotification(
        context: Context,
        fcmToken: String,
        orderId: String,
        driverId: String,
        order: Order
    ): Boolean {
        return try {
            val formattedPrice = String.format("$%.2f", order.totalPrice)
            val notificationData = mapOf(
                "to" to fcmToken,
                "priority" to "high",
                "notification" to mapOf(
                    "title" to "New Order Request",
                    "body" to "From ${order.originCity} to ${order.destinationCity} - $formattedPrice",
                    "sound" to "default"
                ),
                "data" to mapOf(
                    "orderId" to orderId,
                    "order_id" to orderId,
                    "driverId" to driverId,
                    "type" to "order_request",
                    "originCity" to order.originCity,
                    "destinationCity" to order.destinationCity,
                    "price" to formattedPrice,
                    "truckType" to order.truckType,
                    "volume" to order.volume.toString(),
                    "weight" to order.weight.toString(),
                    "click_action" to "OPEN_ORDER_DETAIL"
                )
            )
            sendFcmNotification(context, notificationData)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending driver notification: ${e.message}")
            false
        }
    }

    suspend fun sendCustomerOrderNotification(
        context: Context,
        customerId: String,
        orderId: String,
        title: String,
        message: String
    ): Boolean {
        return try {
            val customerDoc = firestore.collection("users").document(customerId).get().await()
            val fcmToken = customerDoc.getString("fcmToken")

            if (fcmToken.isNullOrEmpty()) return false

            val notificationData = mapOf(
                "to" to fcmToken,
                "priority" to "high",
                "notification" to mapOf(
                    "title" to title,
                    "body" to message,
                    "sound" to "default"
                ),
                "data" to mapOf(
                    "orderId" to orderId,
                    "type" to "order_update",
                    "click_action" to "OPEN_ORDER_DETAIL"
                )
            )
            sendFcmNotification(context, notificationData)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending customer notification: ${e.message}")
            false
        }
    }

    private suspend fun sendFcmNotification(
        context: Context,
        notificationData: Map<String, Any>
    ): Boolean {
        return try {
            // Securely retrieve FCM server key
            val fcmServerKey = FirebaseSecretsManager.getFirebaseServerKey(context)

            val jsonPayload = JSONObject(notificationData).toString()

            val url = URL("https://fcm.googleapis.com/fcm/send")
            val connection = url.openConnection() as HttpsURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "key=$fcmServerKey")
            connection.doOutput = true

            connection.outputStream.use { outputStream ->
                outputStream.write(jsonPayload.toByteArray())
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpsURLConnection.HTTP_OK || responseCode == HttpsURLConnection.HTTP_CREATED) {
                Log.d(TAG, "Notification sent successfully")
                true
            } else {
                Log.e(TAG, "Notification send failed with response code: $responseCode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending FCM notification: ${e.message}")
            false
        }
    }
}