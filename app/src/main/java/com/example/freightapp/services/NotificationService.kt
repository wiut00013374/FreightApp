package com.example.freightapp.services

import android.content.Context
import android.util.Log
import com.example.freightapp.model.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.google.auth.oauth2.GoogleCredentials
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Arrays

object NotificationService {
    private const val TAG = "NotificationService"
    private const val FCM_API_URL = "https://fcm.googleapis.com/v1/projects/asdasd-8e2b3/messages:send"
    private const val MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

    /**
     * Get OAuth2 access token for FCM API v1
     */
    private suspend fun getAccessToken(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val credentials = GoogleCredentials
                .fromStream(context.assets.open("firebase-service-account.json"))
                .createScoped(Arrays.asList(MESSAGING_SCOPE))

            credentials.refreshIfExpired()
            return@withContext credentials.accessToken.tokenValue
        } catch (e: IOException) {
            Log.e(TAG, "Error getting access token: ${e.message}", e)
            throw e
        }
    }

    /**
     * Send a notification to a driver about a new order
     */
    suspend fun sendDriverOrderNotification(
        context: Context,
        fcmToken: String,
        orderId: String,
        driverId: String,
        order: Order
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Preparing notification for driver $driverId")

            // Get OAuth2 access token
            val accessToken = getAccessToken(context)

            // Format price
            val formattedPrice = String.format("$%.2f", order.totalPrice)

            // Create message in FCM HTTP v1 format
            val payload = JSONObject().apply {
                put("message", JSONObject().apply {
                    // Target token
                    put("token", fcmToken)

                    // Notification content
                    put("notification", JSONObject().apply {
                        put("title", "New Order Request")
                        put("body", "From ${order.originCity} to ${order.destinationCity} - $formattedPrice")
                    })

                    // Android specific configuration
                    put("android", JSONObject().apply {
                        put("priority", "HIGH")

                        put("notification", JSONObject().apply {
                            put("channel_id", "order_requests_channel")
                            put("notification_priority", "PRIORITY_HIGH")
                            put("sound", "default")
                            put("default_vibrate_timings", true)
                            put("visibility", "PUBLIC")
                        })
                    })

                    // Data payload
                    put("data", JSONObject().apply {
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
                        put("click_action", "OPEN_ORDER_REQUEST")
                    })
                })
            }

            // Create OkHttp client
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // Create request with OAuth2 bearer token authentication
            val request = Request.Builder()
                .url(FCM_API_URL)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Sending FCM v1 request: ${payload.toString(2)}")

            // Execute request
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            val isSuccessful = response.isSuccessful
            Log.d(TAG, "FCM v1 response: $isSuccessful, code: ${response.code}, body: $responseBody")

            return@withContext isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Error sending FCM v1 notification: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Send a notification to a customer about order updates
     */
    suspend fun sendCustomerOrderNotification(
        context: Context,
        fcmToken: String,
        orderId: String,
        title: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get OAuth2 access token
            val accessToken = getAccessToken(context)

            // Create message payload
            val payload = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("token", fcmToken)

                    put("notification", JSONObject().apply {
                        put("title", title)
                        put("body", message)
                    })

                    put("android", JSONObject().apply {
                        put("priority", "HIGH")
                        put("notification", JSONObject().apply {
                            put("channel_id", "order_updates_channel")
                            put("sound", "default")
                        })
                    })

                    put("data", JSONObject().apply {
                        put("orderId", orderId)
                        put("type", "order_update")
                        put("click_action", "OPEN_ORDER_DETAIL")
                    })
                })
            }

            // Create OkHttp client
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // Create request
            val request = Request.Builder()
                .url(FCM_API_URL)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // Execute request
            val response = client.newCall(request).execute()

            return@withContext response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Error sending customer notification: ${e.message}", e)
            return@withContext false
        }
    }
}