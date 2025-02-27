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

class OrderMatcher {
    private val TAG = "OrderMatcher"
    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Match order to nearby drivers
     * @param order The order to match
     * @return Boolean indicating if matching process was initiated successfully
     */
    suspend fun matchOrderToDrivers(order: Order): Boolean {
        return try {
            // Update order status to finding drivers
            updateOrderStatus(order.id, "Finding Driver")

            // Find nearby available drivers
            val nearbyDrivers = findNearbyDrivers(order)

            if (nearbyDrivers.isEmpty()) {
                Log.d(TAG, "No drivers found for order ${order.id}")
                updateOrderStatus(order.id, "No Drivers Available")
                return false
            }

            // Create drivers contact list
            val driversContactList = nearbyDrivers.associate { driver ->
                driver.id to "pending"
            }

            // Update order with drivers contact list
            updateOrderWithDriversList(order.id, driversContactList)

            // Start notifying drivers using coroutines
            CoroutineScope(Dispatchers.IO).launch {
                notifyDrivers(order, nearbyDrivers)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error matching order to drivers: ${e.message}")
            updateOrderStatus(order.id, "Matching Failed")
            false
        }
    }

    /**
     * Find nearby available drivers for an order
     */
    private suspend fun findNearbyDrivers(order: Order): List<DriverCandidate> {
        // Query for available drivers
        val driversQuery = firestore.collection("users")
            .whereEqualTo("userType", "driver")
            .whereEqualTo("available", true)
            .whereEqualTo("truckType", order.truckType)
            .get()
            .await()

        val candidates = mutableListOf<DriverCandidate>()

        // Process each driver
        for (doc in driversQuery.documents) {
            val driverData = doc.data ?: continue
            val driverId = doc.id

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
                else -> continue
            }

            // Calculate distance between driver and order origin
            val distanceKm = calculateHaversineDistance(
                order.originLat, order.originLon,
                driverLat, driverLon
            )

            // Optional: Set max search radius (e.g., 50 km)
            if (distanceKm <= 50.0) {
                candidates.add(
                    DriverCandidate(
                        id = driverId,
                        name = driverData["displayName"] as? String ?: "Driver",
                        fcmToken = driverData["fcmToken"] as? String ?: "",
                        distanceKm = distanceKm,
                        location = GeoPoint(driverLat, driverLon)
                    )
                )
            }
        }

        // Sort candidates by distance
        return candidates.sortedBy { it.distanceKm }
    }

    /**
     * Notify drivers about the order
     */
    private suspend fun notifyDrivers(order: Order, drivers: List<DriverCandidate>) {
        // Limit to first 5 nearest drivers
        val driversToNotify = drivers.take(5)

        // Notify drivers sequentially
        notifyNextDriver(order, driversToNotify, 0)
    }

    /**
     * Notify the next driver in the sequence
     */
    private suspend fun notifyNextDriver(
        order: Order,
        drivers: List<DriverCandidate>,
        currentIndex: Int
    ) {
        // Check if we've exhausted all drivers
        if (currentIndex >= drivers.size) {
            Log.d(TAG, "No drivers accepted order ${order.id}")
            updateOrderStatus(order.id, "No Drivers Available")
            return
        }

        val currentDriver = drivers[currentIndex]

        // Validate FCM token
        if (currentDriver.fcmToken.isBlank()) {
            // Skip driver without FCM token
            notifyNextDriver(order, drivers, currentIndex + 1)
            return
        }

        // Send notification
        val notificationSuccess = try {
            NotificationService.sendDriverOrderNotification(
                fcmToken = currentDriver.fcmToken,
                orderId = order.id,
                driverId = currentDriver.id
            )
        } catch (e: Exception) {
            Log.e(TAG, "Notification error: ${e.message}")
            false
        }

        if (notificationSuccess) {
            // Update order to show this driver was notified
            updateDriverContactStatus(order.id, currentDriver.id, "notified")

            // Schedule timeout for driver response using coroutine
            CoroutineScope(Dispatchers.IO).launch {
                delay(60000) // 60 seconds timeout
                checkAndMoveToNextDriver(order, drivers, currentIndex)
            }
        } else {
            // If notification fails, try next driver
            notifyNextDriver(order, drivers, currentIndex + 1)
        }
    }

    /**
     * Check order status and move to next driver if no response
     */
    private suspend fun checkAndMoveToNextDriver(
        order: Order,
        drivers: List<DriverCandidate>,
        currentIndex: Int
    ) {
        // Check if order is still in "Finding Driver" status
        val orderDoc = firestore.collection("orders").document(order.id).get().await()
        val currentStatus = orderDoc.getString("status")

        if (currentStatus == "Finding Driver") {
            // No driver accepted, move to next
            notifyNextDriver(order, drivers, currentIndex + 1)
        }
    }

    /**
     * Update order status in Firestore
     */
    private suspend fun updateOrderStatus(orderId: String, status: String) {
        firestore.collection("orders").document(orderId)
            .update("status", status)
            .await()
    }

    /**
     * Update drivers contact list in order document
     */
    private suspend fun updateOrderWithDriversList(
        orderId: String,
        driversContactList: Map<String, String>
    ) {
        firestore.collection("orders").document(orderId)
            .update(
                mapOf(
                    "driversContactList" to driversContactList,
                    "currentDriverIndex" to 0
                )
            )
            .await()
    }

    /**
     * Update a specific driver's contact status
     */
    private suspend fun updateDriverContactStatus(
        orderId: String,
        driverId: String,
        status: String
    ) {
        val orderRef = firestore.collection("orders").document(orderId)

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(orderRef)

            @Suppress("UNCHECKED_CAST")
            val currentContactList = snapshot.get("driversContactList") as? MutableMap<String, String>

            currentContactList?.let {
                it[driverId] = status
                transaction.update(orderRef, "driversContactList", it)
            }
        }.await()
    }

    /**
     * Calculate Haversine distance between two geographical points
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
 * Data class representing a driver candidate for order matching
 */
data class DriverCandidate(
    val id: String,
    val name: String,
    val fcmToken: String,
    val distanceKm: Double,
    val location: GeoPoint
)