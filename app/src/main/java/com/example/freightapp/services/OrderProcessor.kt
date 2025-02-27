package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class OrderProcessor {
    private val TAG = "OrderProcessor"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Services for order matching
    private val orderMatcher = OrderMatcher()

    /**
     * Create a new order and initiate the matching process
     * @param order The order to be processed
     * @return Pair of (success status, order ID)
     */
    suspend fun createOrder(order: Order): Pair<Boolean, String> {
        return try {
            // Validate order details
            validateOrder(order)

            // Create order document in Firestore
            val orderRef = firestore.collection("orders").document()

            // Set the ID and create timestamp
            val processedOrder = order.copy(
                id = orderRef.id,
                uid = auth.currentUser?.uid ?: throw IllegalStateException("User not authenticated"),
                status = "Pending",
                timestamp = System.currentTimeMillis()
            )

            // Save order to Firestore
            orderRef.set(processedOrder).await()

            // Initiate driver matching process
            val matchingResult = orderMatcher.matchOrderToDrivers(processedOrder)

            if (matchingResult) {
                // Notify customer that order is being processed
                notifyCustomerOrderCreated(processedOrder)

                Pair(true, processedOrder.id)
            } else {
                // If no drivers found, update order status
                orderRef.update("status", "No Drivers Available").await()
                Pair(false, processedOrder.id)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating order: ${e.message}")
            Pair(false, "")
        }
    }

    /**
     * Update an existing order
     * @param orderId The ID of the order to update
     * @param updates Map of fields to update
     * @return Success status
     */
    suspend fun updateOrder(orderId: String, updates: Map<String, Any>): Boolean {
        return try {
            // Validate user is the order owner
            val currentUserId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not authenticated")

            // Get current order to verify ownership
            val orderDoc = firestore.collection("orders").document(orderId).get().await()
            val orderOwnerId = orderDoc.getString("uid")

            if (orderOwnerId != currentUserId) {
                throw SecurityException("User not authorized to update this order")
            }

            // Check if order can be modified based on status
            val currentStatus = orderDoc.getString("status")
            if (currentStatus !in listOf("Pending", "Draft")) {
                throw IllegalStateException("Cannot modify order with status: $currentStatus")
            }

            // Validate and sanitize updates
            val sanitizedUpdates = sanitizeOrderUpdates(updates)

            // Update order
            firestore.collection("orders").document(orderId)
                .update(sanitizedUpdates)
                .await()

            // If truck type or location changed, restart driver matching
            if (sanitizedUpdates.keys.any { it in listOf("truckType", "originLat", "originLon") }) {
                val updatedOrder = orderDoc.toObject(Order::class.java)?.copy(
                    truckType = sanitizedUpdates["truckType"] as? String
                        ?: orderDoc.getString("truckType") ?: "",
                    originLat = sanitizedUpdates["originLat"] as? Double
                        ?: orderDoc.getDouble("originLat") ?: 0.0,
                    originLon = sanitizedUpdates["originLon"] as? Double
                        ?: orderDoc.getDouble("originLon") ?: 0.0
                )

                updatedOrder?.let {
                    orderMatcher.matchOrderToDrivers(it)
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating order: ${e.message}")
            false
        }
    }

    /**
     * Cancel an existing order
     * @param orderId The ID of the order to cancel
     * @return Success status
     */
    suspend fun cancelOrder(orderId: String): Boolean {
        return try {
            // Validate user is the order owner
            val currentUserId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not authenticated")

            // Get current order to verify ownership
            val orderDoc = firestore.collection("orders").document(orderId).get().await()
            val orderOwnerId = orderDoc.getString("uid")

            if (orderOwnerId != currentUserId) {
                throw SecurityException("User not authorized to cancel this order")
            }

            // Check if order can be cancelled
            val currentStatus = orderDoc.getString("status")
            if (currentStatus !in listOf("Pending", "Draft", "Finding Driver")) {
                throw IllegalStateException("Cannot cancel order with status: $currentStatus")
            }

            // Update order status
            firestore.collection("orders").document(orderId)
                .update(
                    mapOf(
                        "status" to "Cancelled",
                        "cancelledAt" to System.currentTimeMillis(),
                        "cancelledBy" to currentUserId
                    )
                )
                .await()

            // Notify any assigned driver about cancellation
            notifyDriverAboutCancellation(orderId)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling order: ${e.message}")
            false
        }
    }

    /**
     * Validate order details before processing
     */
    private fun validateOrder(order: Order) {
        require(order.originCity.isNotBlank()) { "Origin city is required" }
        require(order.destinationCity.isNotBlank()) { "Destination city is required" }
        require(order.truckType.isNotBlank()) { "Truck type is required" }
        require(order.totalPrice > 0) { "Total price must be positive" }
        require(order.originLat != 0.0 && order.originLon != 0.0) { "Valid origin coordinates are required" }
        require(order.destinationLat != 0.0 && order.destinationLon != 0.0) { "Valid destination coordinates are required" }
    }

    /**
     * Sanitize and validate order updates
     */
    private fun sanitizeOrderUpdates(updates: Map<String, Any>): Map<String, Any> {
        val sanitized = mutableMapOf<String, Any>()

        // Allowed fields for update
        val allowedFields = setOf(
            "originCity",
            "destinationCity",
            "truckType",
            "volume",
            "weight",
            "originLat",
            "originLon",
            "destinationLat",
            "destinationLon"
        )

        updates.forEach { (key, value) ->
            if (key in allowedFields) {
                when (key) {
                    "originCity", "destinationCity", "truckType" ->
                        require(value is String && value.isNotBlank()) { "$key must be a non-empty string" }
                    "volume", "weight" ->
                        require(value is Number && value.toDouble() > 0) { "$key must be a positive number" }
                    "originLat", "originLon", "destinationLat", "destinationLon" ->
                        require(value is Number) { "$key must be a number" }
                }
                sanitized[key] = value
            }
        }

        return sanitized
    }

    /**
     * Notify customer that order is being processed
     */
    private suspend fun notifyCustomerOrderCreated(order: Order) {
        try {
            NotificationService.sendCustomerOrderNotification(
                customerId = order.uid,
                orderId = order.id,
                title = "Order Created",
                message = "Your order from ${order.originCity} to ${order.destinationCity} is being processed"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying customer about order creation: ${e.message}")
        }
    }

    /**
     * Notify driver about order cancellation
     */
    private suspend fun notifyDriverAboutCancellation(orderId: String) {
        try {
            // Get the order document
            val orderDoc = firestore.collection("orders").document(orderId).get().await()
            val driverUid = orderDoc.getString("driverUid")

            if (driverUid != null) {
                // Get driver's FCM token
                val driverDoc = firestore.collection("users").document(driverUid).get().await()
                val fcmToken = driverDoc.getString("fcmToken")

                if (fcmToken != null) {
                    NotificationService.sendDriverOrderCancellation(
                        fcmToken = fcmToken,
                        orderId = orderId,
                        message = "The order has been cancelled by the customer"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying driver about cancellation: ${e.message}")
        }
    }

    /**
     * Retrieve order details
     * @param orderId The ID of the order to retrieve
     * @return Order details or null if not found
     */
    suspend fun getOrderDetails(orderId: String): Order? {
        return try {
            val currentUserId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not authenticated")

            val orderDoc = firestore.collection("orders").document(orderId).get().await()
            val order = orderDoc.toObject(Order::class.java)

            // Ensure user can only access their own orders
            if (order?.uid != currentUserId) {
                throw SecurityException("User not authorized to access this order")
            }

            order
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving order details: ${e.message}")
            null
        }
    }

    /**
     * Retrieve user's orders
     * @param status Optional status filter
     * @return List of orders matching the criteria
     */
    suspend fun getUserOrders(status: String? = null): List<Order> {
        return try {
            val currentUserId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not authenticated")

            val query = firestore.collection("orders")
                .whereEqualTo("uid", currentUserId)
                .apply {
                    status?.let { whereEqualTo("status", it) }
                }

            query.get().await().toObjects(Order::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving user orders: ${e.message}")
            emptyList()
        }
    }
}