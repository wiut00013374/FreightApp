package com.example.freightapp.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.freightapp.OrderTrackingActivity
import com.example.freightapp.R
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
    private const val CHANNEL_ID_ORDER_UPDATES = "order_updates_channel"
    private const val CHANNEL_ID_PICKUP_UPDATES = "pickup_updates_channel"
    private const val CHANNEL_ID_DELIVERY_UPDATES = "delivery_updates_channel"


    /**
     * Get OAuth2 access token for FCM API v1
     */
    private suspend fun getAccessToken(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val credentials = GoogleCredentials
                .fromStream(context.assets.open("secrets/fcm_server_key.json"))
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
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Order updates channel
            val orderChannel = NotificationChannel(
                CHANNEL_ID_ORDER_UPDATES,
                "Order Status Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications about order status changes"
            }
            notificationManager.createNotificationChannel(orderChannel)

            // Pickup updates channel
            val pickupChannel = NotificationChannel(
                CHANNEL_ID_PICKUP_UPDATES,
                "Pickup Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when your order is picked up"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(pickupChannel)

            // Delivery updates channel
            val deliveryChannel = NotificationChannel(
                CHANNEL_ID_DELIVERY_UPDATES,
                "Delivery Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when your order is delivered"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(deliveryChannel)
        }
    }

    /**
     * Send a notification to the customer about a pickup update
     */
    fun sendPickupNotification(context: Context, orderId: String) {
        // Intent to open the order tracking activity
        val intent = Intent(context, OrderTrackingActivity::class.java).apply {
            putExtra("EXTRA_ORDER_ID", orderId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID_PICKUP_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Order Picked Up")
            .setContentText("Your order has been picked up and is on the way to delivery")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Show the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = orderId.hashCode() + 1000 // Use a unique ID
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    /**
     * Send a notification to the customer about a delivery update
     */
    fun sendDeliveryNotification(context: Context, orderId: String) {
        // Intent to open the order tracking activity
        val intent = Intent(context, OrderTrackingActivity::class.java).apply {
            putExtra("EXTRA_ORDER_ID", orderId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID_DELIVERY_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Order Delivered")
            .setContentText("Your order has been delivered successfully")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Show the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = orderId.hashCode() + 2000 // Use a unique ID
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    /**
     * Send a general notification about order status updates
     */
    fun sendOrderStatusNotification(context: Context, orderId: String, title: String, message: String) {
        // Intent to open the order tracking activity
        val intent = Intent(context, OrderTrackingActivity::class.java).apply {
            putExtra("EXTRA_ORDER_ID", orderId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID_ORDER_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Show the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = orderId.hashCode() // Use a unique ID
        notificationManager.notify(notificationId, notificationBuilder.build())
    }
}
