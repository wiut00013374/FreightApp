package com.example.freightapp.services

import android.content.Context
import android.util.Log
import com.example.freightapp.model.Order
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object NotificationService {
    private const val TAG = "NotificationService"
    private val firestore = FirebaseFirestore.getInstance()
    private const val FCM_API_URL = "https://fcm.googleapis.com/fcm/send"

    // Server key should be stored securely - here it's a placeholder
    private const val SERVER_KEY = "YOUR_SERVER_KEY" // This should be replaced with proper secure storage

    suspend fun sendDriverOrderNotification(
        context: Context,
        fcmToken: String,
        orderId: String,
        driverId: String,
        order: Order
    ): Boolean {
        return try {
            val formattedPrice = String.format("$%.2f", order.totalPrice)

            val notification = JSONObject().apply {
                put("title", "New Order Request")
                put("body", "From ${order.originCity} to ${order.destinationCity} - $formattedPrice")
                put("sound", "default")
            }

            val data = JSONObject().apply {
                put("orderId", orderId)
                put("order_id", orderId)
                put("driverId", driverId)
                put("type", "order_request")
                put("originCity", order.originCity)
                put("destinationCity", order.destinationCity)
                put("price", formattedPrice)
                put("truckType", order.truckType)
                put("volume", order.volume.toString())
                put("weight", order.weight.toString())
                put("click_action", "OPEN_ORDER_DETAIL")
            }

            val payload = JSONObject().apply {
                put("to", fcmToken)
                put("priority", "high")
                put("notification", notification)
                put("data", data)
            }

            sendFcmNotification(payload.toString())
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

            val notification = JSONObject().apply {
                put("title", title)
                put("body", message)
                put("sound", "default")
            }

            val data = JSONObject().apply {
                put("orderId", orderId)
                put("type", "order_update")
                put("click_action", "OPEN_ORDER_DETAIL")
            }

            val payload = JSONObject().apply {
                put("to", fcmToken)
                put("priority", "high")
                put("notification", notification)
                put("data", data)
            }

            sendFcmNotification(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending customer notification: ${e.message}")
            false
        }
    }

    private fun sendFcmNotification(jsonPayload: String): Boolean {
        return try {
            val url = URL(FCM_API_URL)
            val connection = url.openConnection() as HttpsURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "key=$SERVER_KEY")
            connection.doOutput = true

            val outputStream = OutputStreamWriter(connection.outputStream)
            outputStream.write(jsonPayload)
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            val success = responseCode == HttpsURLConnection.HTTP_OK ||
                    responseCode == HttpsURLConnection.HTTP_CREATED

            if (success) {
                Log.d(TAG, "Notification sent successfully")
            } else {
                Log.e(TAG, "Notification send failed with response code: $responseCode")
            }

            connection.disconnect()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error sending FCM notification: ${e.message}")
            false
        }
    }
}