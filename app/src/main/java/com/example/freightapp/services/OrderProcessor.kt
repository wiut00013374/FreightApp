package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Service to process orders, including creating new orders and handling driver responses
 */
class OrderProcessor {
    private val TAG = "OrderProcessor"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /**
     * Update an order's status
     */
    suspend fun updateOrderStatus(orderId: String, status: String): Boolean {
        return try {
            firestore.collection("orders").document(orderId)
                .update("status", status)
                .await()

            // If this is a completion status, notify the customer
            if (status == "Delivered" || status == "Completed") {
                val orderDoc = firestore.collection("orders").document(orderId).get().await()
                val customerId = orderDoc.getString("uid")

                if (customerId != null) {
                    NotificationService.sendCustomerOrderNotification(
                        customerId = customerId,
                        orderId = orderId,
                        title = "Order $status",
                        message = "Your order has been $status. Thank you for using our service!"
                    )
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating order status: ${e.message}")
            false
        }
    }

    /**
     * Cancel an order by a customer
     */
    suspend fun cancelOrder(orderId: String): Boolean {
        return try {
            val orderDoc = firestore.collection("orders").document(orderId).get().await()
            val driverUid = orderDoc.getString("driverUid")

            // Update order status
            firestore.collection("orders").document(orderId)
                .update(
                    mapOf(
                        "status" to "Cancelled",
                        "cancelledAt" to System.currentTimeMillis()
                    )
                )
                .await()

            // Notify the driver if one is assigned
            if (driverUid != null) {
                val driverDoc = firestore.collection("users").document(driverUid).get().await()
                val fcmToken = driverDoc.getString("fcmToken")

                if (!fcmToken.isNullOrEmpty()) {
                    val orderObj = orderDoc.toObject(Order::class.java)
                    val originCity = orderObj?.originCity ?: "unknown"
                    val destinationCity = orderObj?.destinationCity ?: "unknown"

                    NotificationService.sendDriverOrderCancellation(
                        fcmToken = fcmToken,
                        orderId = orderId,
                        message = "Order from $originCity to $destinationCity has been cancelled by the customer."
                    )
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling order: ${e.message}")
            false
        }
    }

    /**
     * Process a new order and start finding drivers
     */
    suspend fun processNewOrder(order: Order): Boolean {
        try {
            // Create a new document in Firestore for the order
            val orderRef = if (order.id.isBlank()) {
                firestore.collection("orders").document()
            } else {
                firestore.collection("orders").document(order.id)
            }

            // Set order ID if not already set
            if (order.id.isBlank()) {
                order.id = orderRef.id
            }

            // Save the order to Firestore
            orderRef.set(order).await()
            Log.d(TAG, "Created new order with ID: ${order.id}")

            // Start finding drivers for this order
            val driverFinder = DriverFinder()
            val driversFound = driverFinder.findNearbyDriversForOrder(order.id)

            return if (driversFound) {
                Log.d(TAG, "Started driver search process for order ${order.id}")

                // Update order status to indicate search is in progress
                orderRef.update("status", "Finding Driver").await()

                true
            } else {
                Log.d(TAG, "No drivers found for order ${order.id}")

                // Update order status to indicate no drivers were found
                orderRef.update("status", "No Drivers Available").await()
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing new order: ${e.message}")
            return false
        }
    }

    /**
     * Process a driver's response to an order notification
     */
    suspend fun processDriverResponse(driverId: String, orderId: String, accepted: Boolean): Boolean {
        try {
            Log.d(TAG, "Processing driver $driverId response for order $orderId: ${if (accepted) "accepted" else "rejected"}")

            // Get the current order
            val orderDoc = firestore.collection("orders").document(orderId).get().await()

            // Order must exist
            if (!orderDoc.exists()) {
                Log.e(TAG, "Order $orderId not found")
                return false
            }

            // Check if order already has a driver
            val currentDriverUid = orderDoc.getString("driverUid")
            if (currentDriverUid != null && currentDriverUid != driverId) {
                Log.d(TAG, "Order $orderId already assigned to driver $currentDriverUid")
                return false
            }

            // Get the drivers contact list
            @Suppress("UNCHECKED_CAST")
            val driversContactList = orderDoc.get("driversContactList") as? Map<String, String>

            if (driversContactList == null || !driversContactList.containsKey(driverId)) {
                Log.e(TAG, "Driver $driverId not in contact list for order $orderId")
                return false
            }

            // Update the driver's status in the list
            val updatedList = driversContactList.toMutableMap()
            updatedList[driverId] = if (accepted) "accepted" else "rejected"

            if (accepted) {
                // Assign the driver to the order
                val updates = mapOf(
                    "driverUid" to driverId,
                    "status" to "Accepted",
                    "driversContactList" to updatedList,
                    "acceptedAt" to System.currentTimeMillis()
                )

                firestore.collection("orders").document(orderId)
                    .update(updates)
                    .await()

                Log.d(TAG, "Driver $driverId accepted order $orderId")

                // Cancel any pending timeouts
                DriverTimeoutManager.cancelTimeout(orderId)

                // Notify the customer
                val customerId = orderDoc.getString("uid")
                if (customerId != null) {
                    NotificationService.sendCustomerOrderNotification(
                        customerId = customerId,
                        orderId = orderId,
                        title = "Driver Found!",
                        message = "A driver has accepted your order and will pick up your freight soon."
                    )
                }

                return true

            } else {
                // Driver rejected, update the list and move to the next driver
                val currentIndex = orderDoc.getLong("currentDriverIndex") ?: 0

                firestore.collection("orders").document(orderId)
                    .update(
                        mapOf(
                            "driversContactList" to updatedList,
                            "currentDriverIndex" to currentIndex + 1
                        )
                    )
                    .await()

                Log.d(TAG, "Driver $driverId rejected order $orderId, moving to next driver")

                // Notify the next driver
                val driverFinder = DriverFinder()
                driverFinder.notifyNextDriverForOrder(orderId)

                return true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing driver response: ${e.message}")
            return false
        }
    }

/**