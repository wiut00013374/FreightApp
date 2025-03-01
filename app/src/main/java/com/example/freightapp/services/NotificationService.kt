package com.example.freightapp.services

import android.content.Context
import android.util.Log
import com.example.freightapp.model.Order
import com.example.freightapp.utils.FirebaseSecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import okhttp3.Call
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NotificationService {
    private const val TAG = "NotificationService"
    private const val FCM_API_URL = "https://fcm.googleapis.com/fcm/send"

    /**
     * Send a driver order notification with comprehensive error handling
     */
    suspend fun sendDriverOrderNotification(
        context: Context,
        fcmToken: String,
        orderId: String,
        driverId: String,
        order: Order
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Validate inputs
            require(fcmToken.isNotBlank()) { "FCM Token cannot be blank" }
            require(orderId.isNotBlank()) { "Order ID cannot be blank" }
            require(driverId.isNotBlank()) { "Driver ID cannot be blank" }

            // Get server key for FCM
            val serverKey = FirebaseSecretsManager.getFCMServerKey(context)

            // Format price
            val formattedPrice = String.format("$%.2f", order.totalPrice)

            // Create notification payload
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

            // Configure OkHttp client with timeout
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // Prepare request body
            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(FCM_API_URL)
                .post(requestBody)
                .addHeader("Authorization", "key=$serverKey")
                .addHeader("Content-Type", "application/json")
                .build()

            // Log request details
            Log.d(TAG, "Request URL: ${request.url}")
            Log.d(TAG, "Request Headers: ${request.headers}")
            Log.d(TAG, "Request Body: ${payload.toString(2)}")

            // Send notification and handle response
            val response = client.newCall(request).await()

            // Log response details for debugging
            Log.d(TAG, "Notification Response Code: ${response.code}")
            Log.d(TAG, "Notification Response Message: ${response.message}")

            // Check response success
            val isSuccessful = response.isSuccessful

            if (!isSuccessful) {
                Log.e(TAG, "Notification send failed. Body: ${response.body?.string()}")
            }

            isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Comprehensive FCM Notification Error", e)
            false
        }
    }

    /**
     * Send a customer order update notification
     */
    suspend fun sendCustomerOrderNotification(
        context: Context,
        customerId: String,
        orderId: String,
        title: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Retrieve customer's FCM token (implementation depends on your user data storage)
            val fcmToken = retrieveCustomerFCMToken(customerId)

            if (fcmToken.isNullOrBlank()) {
                Log.w(TAG, "No FCM token found for customer: $customerId")
                return@withContext false
            }

            val payload = JSONObject().apply {
                put("to", fcmToken)
                put("priority", "normal")
                put("notification", JSONObject().apply {
                    put("title", title)
                    put("body", message)
                })
                put("data", JSONObject().apply {
                    put("orderId", orderId)
                    put("type", "order_update")
                })
            }

            // Similar implementation to sendDriverOrderNotification
            val serverKey = FirebaseSecretsManager.getFCMServerKey(context)
            val client = OkHttpClient()

            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(FCM_API_URL)
                .post(requestBody)
                .addHeader("Authorization", "key=$serverKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).await()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Customer Notification Error", e)
            false
        }
    }

    /**
     * Retrieve customer's FCM token (implement based on your user data storage)
     */
    private suspend fun retrieveCustomerFCMToken(customerId: String): String? = withContext(Dispatchers.IO) {
        try {
            // Implement token retrieval from Firestore or your user database
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(customerId)
                .get()
                .await()
                .getString("fcmToken")
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving customer FCM token", e)
            null
        }
    }
    suspend fun Call.await(): okhttp3.Response {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancel()
            }
            enqueue(object : okhttp3.Callback {
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
        }
    }
}