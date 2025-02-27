package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.*

/**
 * Service to match orders with drivers based on location and availability
 */
class OrderMatcher {
    private val TAG = "OrderMatcher"
    private val firestore = FirebaseFirestore.getInstance()

    // Configuration
    companion object {
        private const val MAX_SEARCH_DISTANCE_KM = 50.0
        private const val MAX_DRIVERS_TO_CONTACT = 10
        private const val DRIVER_RESPONSE_TIMEOUT_SECONDS = 60L
    }

    /**
     * Match an order to nearby drivers
     * @param order The order to match with drivers
     * @return Boolean indicating if matching process was initiated successfully
     */
    suspend fun matchOrderToDrivers(order: Order): Boolean {
        return try {
            // Update order status to reflect searching
            firestore.collection("orders").document(order.id)
                .update("status", "Looking For Driver")
                .await()

            // Find nearby available drivers
            val nearbyDrivers = findNearbyDrivers(order)

            if (nearbyDrivers.isEmpty()) {
                Log.d(TAG, "No available drivers found for order ${order.id}")
                firestore.collection("orders").document(order.id)
                    .update("status", "No Drivers Available")
                    .await()
                return false
            }

            Log.d(TAG, "Found ${nearbyDrivers.size} available drivers for order ${order.id}")

            // Create a contacts list with all drivers (limited to max number)
            val driversToContact = nearbyDrivers.take(MAX_DRIVERS_TO_CONTACT)

            // Create map of driver IDs to contact status
            val driversContactList = driversToContact.associate { driver ->
                driver.id to "pending" // Status: pending, notified, accepted, rejected
            }

            // Initialize driver contact process in Firestore
            firestore.collection("orders").document(order.id)
                .update(
                    mapOf(
                        "driversContactList" to driversContactList,
                        "currentDriverIndex" to 0,
                        "lastDriverNotificationTime" to System.currentTimeMillis()
                    )
                )
                .await()

            // Start contacting the first driver
            notifyNextDriver(order.id)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error matching order to drivers: ${e.message}")

            try {
                // Update order status to reflect failure
                firestore.collection("orders").document(order.id)
                    .update("status", "Driver Search Failed")
                    .await()
            } catch (e2: Exception) {
                Log.e(TAG, "Error updating order status: ${e2.message}")
            }

            false
        }
    }

    /**
     * Find nearby available drivers that match the order requirements
     */
    private suspend fun findNearbyDrivers(order: Order): List<DriverCandidate> {
        try {
            // Query for available drivers with matching truck type
            val driversQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .whereEqualTo("truckType", order.truckType)
                .get()
                .await()

            Log.d(TAG, "Found ${driversQuery.size()} potential drivers with matching truck type")

            val candidates = mutableListOf<DriverCandidate>()

            // Filter drivers based on location and sort by distance
            for (doc in driversQuery.documents) {
                val driverData = doc.data ?: continue
                val driverId = doc.id

                // Check for FCM token
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

                // Calculate distance to pickup point
                val distanceKm = calculateHaversineDistance(
                    order.originLat, order.originLon,
                    driverLat, driverLon
                )

                // Only include drivers within the search radius
                if (distanceKm <= MAX_SEARCH_DISTANCE_KM) {
                    // Get driver name
                    val driverName = driverData["displayName"] as? String ?: "Driver"

                    candidates.add(
                        DriverCandidate(
                            id = driverId,
                            name = driverName,
                            fcmToken = fcmToken,
                            distanceKm = distanceKm
                        )
                    )

                    Log.d(TAG, "Added driver $driverId ($driverName) at distance ${String.format("%.1f", distanceKm)} km")
                } else {
                    Log.d(TAG, "Driver $driverId too far (${String.format("%.1f", distanceKm)} km), skipping")
                }
            }

            // Sort by distance (closest first)
            return candidates.sortedBy { it.distanceKm }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding nearby drivers: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Notify the next driver in the queue for this order
     */
    suspend fun notifyNextDriver(orderId: String): Boolean {
        try {
            // Get the current order data
            val orderDoc = firestore.collection("orders").document(orderId).get().await()

            if (!orderDoc.exists()) {
                Log.d(TAG, "Order $orderId no longer exists")
                return false
            }

            // Check if order already has a driver
            val driverUid = orderDoc.getString("driverUid")
            if (driverUid != null) {
                Log.d(TAG, "Order $orderId already has a driver assigned: $driverUid")
                return true
            }

            // Get the drivers contact list
            @Suppress("UNCHECKED_CAST")
            val driversContactList = orderDoc.get("driversContactList") as? Map<String, String>
            if (driversContactList.isNullOrEmpty()) {
                Log.d(TAG, "No drivers contact list for order $orderId")
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available")
                    .await()
                return false
            }

            // Get current driver index
            val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0
            val driverIds = driversContactList.keys.toList()

            // Check if we've gone through all drivers
            if (currentIndex >= driverIds.size) {
                Log.d(TAG, "All drivers have been contacted for order $orderId")
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available")
                    .await()
                return false
            }

            // Get the next driver ID
            val driverId = driverIds[currentIndex]
            val status = driversContactList[driverId]

            // Skip drivers that have already been notified or rejected
            if (status != "pending") {
                // Move to the next driver
                firestore.collection("orders").document(orderId)
                    .update("currentDriverIndex", currentIndex + 1)
                    .await()

                return notifyNextDriver(orderId)
            }

            // Get driver's FCM token
            val driverDoc = firestore.collection("users").document(driverId).get().await()
            val fcmToken = driverDoc.getString("fcmToken")

            if (fcmToken.isNullOrBlank()) {
                Log.d(TAG, "Driver $driverId has no FCM token, skipping")

                // Mark driver as skipped and move to the next one
                val updatedList = driversContactList.toMutableMap()
                updatedList[driverId] = "skipped"

                firestore.collection("orders").document(orderId)
                    .update(
                        "driversContactList", updatedList,
                        "currentDriverIndex", currentIndex + 1
                    )
                    .await()

                return notifyNextDriver(orderId)
            }

            // Get the order details for the notification
            val order = orderDoc.toObject(Order::class.java)?.apply { id = orderId }

            if (order == null) {
                Log.d(TAG, "Order $orderId could not be converted to object")
                return false
            }

            // Send the notification
            val notificationSuccess = NotificationService.sendDriverOrderNotification(
                fcmToken = fcmToken,
                orderId = orderId,
                driverId = driverId,
                order = order
            )

            if (notificationSuccess) {
                // Update driver status to notified
                val updatedList = driversContactList.toMutableMap()
                updatedList[driverId] = "notified"

                firestore.collection("orders").document(orderId)
                    .update(
                        "driversContactList", updatedList,
                        "lastDriverNotificationTime", System.currentTimeMillis()
                    )
                    .await()

                Log.d(TAG, "Successfully notified driver $driverId for order $orderId")

                // Schedule a timeout check
                scheduleDriverResponseTimeout(orderId)

                return true
            } else {
                // If notification failed, mark as failed and move to next driver
                val updatedList = driversContactList.toMutableMap()
                updatedList[driverId] = "failed"

                firestore.collection("orders").document(orderId)
                    .update(
                        "driversContactList", updatedList,
                        "currentDriverIndex", currentIndex + 1
                    )
                    .await()

                Log.d(TAG, "Failed to notify driver $driverId, moving to next")

                return notifyNextDriver(orderId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying next driver: ${e.message}")
            return false
        }
    }

    /**
     * Schedule a timeout for driver response
     */
    private fun scheduleDriverResponseTimeout(orderId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Wait for timeout period
                delay(DRIVER_RESPONSE_TIMEOUT_SECONDS * 1000)

                // Check if the order still needs a driver
                val orderDoc = firestore.collection("orders").document(orderId).get().await()

                if (!orderDoc.exists()) {
                    Log.d(TAG, "Order $orderId no longer exists")
                    return@launch
                }

                // Check if already assigned
                val driverUid = orderDoc.getString("driverUid")
                if (driverUid != null) {
                    Log.d(TAG, "Order $orderId already has a driver assigned")
                    return@launch
                }

                // Check the last notification time
                val lastNotificationTime = orderDoc.getLong("lastDriverNotificationTime") ?: 0L
                val currentTime = System.currentTimeMillis()

                // Only proceed if the timeout period has elapsed since the last notification
                if (currentTime - lastNotificationTime >= DRIVER_RESPONSE_TIMEOUT_SECONDS * 1000) {
                    Log.d(TAG, "Driver response timeout for order $orderId")

                    // Move to the next driver
                    val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0

                    firestore.collection("orders").document(orderId)
                        .update("currentDriverIndex", currentIndex + 1)
                        .await()

                    // Notify the next driver
                    notifyNextDriver(orderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in driver response timeout: ${e.message}")
            }
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
}

/**
 * Data class for driver candidates during matching
 */
data class DriverCandidate(
    val id: String,
    val name: String,
    val fcmToken: String,
    val distanceKm: Double
)