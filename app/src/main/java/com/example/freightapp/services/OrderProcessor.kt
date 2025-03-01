package com.example.freightapp.services

import android.content.Context
import android.util.Log
import com.example.freightapp.model.Order
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OrderProcessor(private val context: Context) {
    private val TAG = "OrderProcessor"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val orderMatcher = OrderMatcher(context)

    suspend fun createOrder(order: Order): Pair<Boolean, String?> {
        return try {
            validateOrder(order)
            val orderRef = firestore.collection("orders").document()
            val processedOrder = order.copy(
                id = orderRef.id,
                uid = auth.currentUser?.uid ?: throw IllegalStateException("User not authenticated"),
                status = "Looking For Driver",
                timestamp = System.currentTimeMillis()
            )
            orderRef.set(processedOrder).await()
            Log.d(TAG, "Order created with ID: ${processedOrder.id}")

            // Try to find drivers for the order
            val driverAssigned = orderMatcher.matchOrderToDrivers(processedOrder)
            Log.d(TAG, "Driver matching process initialized: $driverAssigned")

            Pair(true, processedOrder.id)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating order: ${e.message}")
            Pair(false, null)
        }
    }

    private fun validateOrder(order: Order) {
        require(order.originCity.isNotBlank()) { "Origin city is required" }
        require(order.destinationCity.isNotBlank()) { "Destination city is required" }
        require(order.truckType.isNotBlank()) { "Truck type is required" }
        require(order.totalPrice > 0) { "Total price must be positive" }
        require(order.originLat != 0.0 && order.originLon != 0.0) { "Valid origin coordinates are required" }
        require(order.destinationLat != 0.0 && order.destinationLon != 0.0) { "Valid destination coordinates are required" }
    }
    private suspend fun notifyDriverAboutOrder(order: Order, driverId: String, fcmToken: String): Boolean {
        try {
            // Create FCM payload
            val payload = JSONObject().apply {
                put("to", fcmToken)
                put("priority", "high")

                // Add notification object
                put("notification", JSONObject().apply {
                    put("title", "New Order Request")
                    put("body", "From ${order.originCity} to ${order.destinationCity}")
                    put("sound", "default")
                })

                // Add data object
                put("data", JSONObject().apply {
                    put("orderId", order.id)
                    put("type", "order_request")
                    put("originCity", order.originCity)
                    put("destinationCity", order.destinationCity)
                    put("price", String.format("$%.2f", order.totalPrice))
                    put("click_action", "OPEN_ORDER_REQUEST")
                })
            }

            // Get FCM server key
            val serverKey = "YOUR_SERVER_KEY" // Replace with actual key retrieval method

            // Create OkHttp client for the request
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // Create request with headers and body
            val request = Request.Builder()
                .url("https://fcm.googleapis.com/fcm/send")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "key=$serverKey")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // Execute the request
            val response = client.newCall(request).execute()

            // Check if successful
            val success = response.isSuccessful
            val responseBody = response.body?.string()

            Log.d("FCM", "Response: $responseBody")

            return success
        } catch (e: Exception) {
            Log.e("FCM", "Error sending FCM notification", e)
            return false
        }
    }
}