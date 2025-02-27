package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Service to process orders, including creating new orders and handling driver responses
 */
class OrderProcessor {
    private val TAG = "OrderProcessor"
    private val firestore = FirebaseFirestore.getInstance()

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
                true
            } else {
                Log.d(TAG, "No drivers found for order ${order.id}")

                // Update order status to indicate no drivers were found
                orderRef.update("status", "No Drivers").await()
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
}