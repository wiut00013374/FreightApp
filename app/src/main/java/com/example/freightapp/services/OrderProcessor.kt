package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.*

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
                status = "Looking For Driver", // Update status to indicate we're searching
                timestamp = System.currentTimeMillis()
            )

            // Save order to Firestore
            orderRef.set(processedOrder).await()

            Log.d(TAG, "Order created with ID: ${processedOrder.id}")

            // Initiate driver matching process in a more robust way
            try {
                val nearbyDrivers = findNearbyDrivers(processedOrder)

                if (nearbyDrivers.isEmpty()) {
                    Log.d(TAG, "No available drivers found for order ${processedOrder.id}")
                    orderRef.update("status", "No Drivers Available").await()
                    notifyCustomerNoDriversFound(processedOrder)
                    return Pair(false, processedOrder.id)
                }

                Log.d(TAG, "Found ${nearbyDrivers.size} available drivers for order ${processedOrder.id}")

                // Create drivers contact list
                val driversContactList = nearbyDrivers.associate {
                    it.id to "pending" // Status: pending, notified, accepted, rejected
                }

                // Update order with drivers list and start notification process
                orderRef.update(
                    mapOf(
                        "driversContactList" to driversContactList,
                        "currentDriverIndex" to 0,
                        "lastDriverNotificationTime" to System.currentTimeMillis()
                    )
                ).await()

                // Notify first driver
                val firstDriver = nearbyDrivers.first()
                val notificationSent = notifyDriverAboutOrder(firstDriver.id, firstDriver.fcmToken, processedOrder)

                if (notificationSent) {
                    // Update the driver's status in the list
                    val updatedList = driversContactList.toMutableMap()
                    updatedList[firstDriver.id] = "notified"

                    orderRef.update(
                        "driversContactList", updatedList,
                        "lastDriverNotificationTime", System.currentTimeMillis()
                    ).await()

                    // Schedule a timeout check
                    scheduleDriverResponseTimeout(processedOrder.id)

                    // Notify customer about the search
                    notifyCustomerSearchingForDriver(processedOrder)

                    return Pair(true, processedOrder.id)
                } else {
                    // If notification failed, move to next driver
                    moveToNextDriver(processedOrder.id)
                    return Pair(true, processedOrder.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in driver matching process: ${e.message}")
                orderRef.update("status", "Driver Search Failed").await()
                return Pair(false, processedOrder.id)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating order: ${e.message}")
            return Pair(false, "")
        }
    }

    /**
     * Find nearby available drivers for an order
     */
    private suspend fun findNearbyDrivers(order: Order): List<DriverInfo> {
        // Max distance in kilometers to search for drivers
        val MAX_SEARCH_DISTANCE_KM = 50.0
        val result = mutableListOf<DriverInfo>()

        try {
            // Query for available drivers with matching truck type
            val driversQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .whereEqualTo("truckType", order.truckType)
                .get()
                .await()

            Log.d(TAG, "Found ${driversQuery.size()} drivers with matching truck type")

            // Filter drivers based on location and sort by distance
            for (doc in driversQuery.documents) {
                val driverData = doc.data ?: continue
                val driverId = doc.id

                // Check FCM token
                val fcmToken = driverData["fcmToken"] as? String
                if (fcmToken.isNullOrBlank()) {
                    Log.d(TAG, "Driver $driverId has no FCM token, skipping")
                    continue
                }

                // Extract driver location
                val locationObj = driverData["location"]
                val driverLat: Double
                val driverLon: Double

                when (locationObj) {
                    is GeoPoint -> {
                        driverLat = locationObj.latitude
                        driverLon = locationObj.longitude
                    }
                    is Map<*, *> -> {
                        driverLat = (locationObj["latitude"] as? Number)?.toDouble() ?: continue
                        driverLon = (locationObj["longitude"] as? Number)?.toDouble() ?: continue
                    }
                    else -> {
                        Log.d(TAG, "Driver $driverId has no valid location, skipping")
                        continue
                    }
                }

                // Calculate distance between driver and pickup point
                val distanceKm = calculateHaversineDistance(
                    order.originLat, order.originLon,
                    driverLat, driverLon
                )

                // Only include drivers within the search radius
                if (distanceKm <= MAX_SEARCH_DISTANCE_KM) {
                    val displayName = driverData["displayName"] as? String ?: "Driver"

                    result.add(
                        DriverInfo(
                            id = driverId,
                            name = displayName,
                            fcmToken = fcmToken,
                            distanceKm = distanceKm
                        )
                    )

                    Log.d(TAG, "Added driver $driverId ($displayName) at distance ${distanceKm.format(1)} km")
                } else {
                    Log.d(TAG, "Driver $driverId too far (${distanceKm.format(1)} km), skipping")
                }
            }

            // Sort drivers by distance (closest first)
            return result.sortedBy { it.distanceKm }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding nearby drivers: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Notify a driver about a new order
     */
    private suspend fun notifyDriverAboutOrder(driverId: String, fcmToken: String, order: Order): Boolean {
        return try {
            // Format price to two decimal places for better display
            val formattedPrice = String.format("$%.2f", order.totalPrice)

            // Send notification using NotificationService
            val success = NotificationService.sendDriverOrderNotification(
                fcmToken = fcmToken,
                orderId = order.id,
                driverId = driverId,
                order = order
            )

            if (success) {
                Log.d(TAG, "Notification sent successfully to driver $driverId for order ${order.id}")
                return true
            } else {
                Log.e(TAG, "Failed to send notification to driver $driverId")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying driver $driverId: ${e.message}")
            return false
        }
    }

    /**
     * Schedule a timeout check for driver response
     */
    private fun scheduleDriverResponseTimeout(orderId: String) {
        // In a real app, you'd use WorkManager or a similar approach
        // For this implementation, use a simple delay with a coroutine
        kotlinx.coroutines.GlobalScope.launch {
            try {
                // Wait for a certain time (60 seconds)
                kotlinx.coroutines.delay(60000)

                // Check if order still needs a driver
                val orderDoc = firestore.collection("orders").document(orderId).get().await()

                if (orderDoc.exists()) {
                    val status = orderDoc.getString("status")

                    if (status == "Looking For Driver") {
                        // No driver has accepted yet, move to next driver
                        Log.d(TAG, "Driver response timeout for order $orderId, moving to next driver")
                        moveToNextDriver(orderId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in driver timeout check: ${e.message}")
            }
        }
    }

    /**
     * Move to the next driver in the list
     */
    private suspend fun moveToNextDriver(orderId: String) {
        try {
            // Get current order data
            val orderDoc = firestore.collection("orders").document(orderId).get().await()

            if (!orderDoc.exists()) {
                Log.d(TAG, "Order $orderId no longer exists")
                return
            }

            // Check if order already has a driver
            val driverUid = orderDoc.getString("driverUid")
            if (driverUid != null) {
                Log.d(TAG, "Order $orderId already has a driver assigned: $driverUid")
                return
            }

            // Get drivers list and current index
            @Suppress("UNCHECKED_CAST")
            val driversContactList = orderDoc.get("driversContactList") as? Map<String, String>
            if (driversContactList.isNullOrEmpty()) {
                Log.d(TAG, "No drivers contact list for order $orderId")
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available").await()
                return
            }

            val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0
            val driverIds = driversContactList.keys.toList()

            // Check if we've gone through all drivers
            if (currentIndex >= driverIds.size - 1) {
                Log.d(TAG, "No more drivers to contact for order $orderId")
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available").await()
                return
            }

            // Move to next driver
            val nextIndex = currentIndex + 1
            val nextDriverId = driverIds[nextIndex]

            // Get driver info
            val driverDoc = firestore.collection("users").document(nextDriverId).get().await()
            val fcmToken = driverDoc.getString("fcmToken")

            if (fcmToken.isNullOrBlank()) {
                Log.d(TAG, "Next driver $nextDriverId has no FCM token, skipping")

                // Skip this driver
                firestore.collection("orders").document(orderId)
                    .update("currentDriverIndex", nextIndex).await()

                // Try next driver recursively
                moveToNextDriver(orderId)
                return
            }

            // Update current index and notification time
            firestore.collection("orders").document(orderId)
                .update(
                    "currentDriverIndex", nextIndex,
                    "lastDriverNotificationTime", System.currentTimeMillis()
                ).await()

            // Get order details to send in notification
            val order = orderDoc.toObject(Order::class.java)?.apply { id = orderId }

            if (order != null) {
                // Notify next driver
                val notificationSent = notifyDriverAboutOrder(nextDriverId, fcmToken, order)

                if (notificationSent) {
                    // Update driver status to notified
                    val updatedList = driversContactList.toMutableMap()
                    updatedList[nextDriverId] = "notified"

                    firestore.collection("orders").document(orderId)
                        .update("driversContactList", updatedList).await()

                    // Schedule next timeout
                    scheduleDriverResponseTimeout(orderId)
                } else {
                    // If notification fails, move to next driver
                    moveToNextDriver(orderId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving to next driver: ${e.message}")
        }
    }

    /**
     * Calculate distance between two points using the Haversine formula
     */
    private fun calculateHaversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371.0 // Earth's radius in kilometers
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
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
     * Notify customer that we're searching for a driver
     */
    private suspend fun notifyCustomerSearchingForDriver(order: Order) {
        try {
            NotificationService.sendCustomerOrderNotification(
                customerId = order.uid,
                orderId = order.id,
                title = "Order Created - Searching for Driver",
                message = "We're searching for a driver for your order from ${order.originCity} to ${order.destinationCity}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying customer about driver search: ${e.message}")
        }
    }

    /**
     * Notify customer that no drivers were found
     */
    private suspend fun notifyCustomerNoDriversFound(order: Order) {
        try {
            NotificationService.sendCustomerOrderNotification(
                customerId = order.uid,
                orderId = order.id,
                title = "No Drivers Available",
                message = "We couldn't find any available drivers for your order. Please try again later."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying customer about no drivers: ${e.message}")
        }
    }

    // Helper function to format double to specified decimal places
    private fun Double.format(digits: Int): String = String.format("%.${digits}f", this)
}

/**
 * Data class to hold driver information for matching
 */
data class DriverInfo(
    val id: String,
    val name: String,
    val fcmToken: String,
    val distanceKm: Double
)