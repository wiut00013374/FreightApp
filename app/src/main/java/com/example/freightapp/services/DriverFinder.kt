package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.tasks.await
import kotlin.math.*

/**
 * Service to find and match orders with nearby drivers
 */
class DriverFinder {
    private val TAG = "DriverFinder"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Configuration
    companion object {
        private const val MAX_SEARCH_DISTANCE_KM = 30.0 // Maximum search radius in kilometers
        private const val MAX_DRIVERS_TO_CONTACT = 10 // Maximum number of drivers to notify for one order
        private const val DRIVER_NOTIFICATION_TIMEOUT_MS = 60000L // 60 seconds before moving to next driver
    }

    /**
     * Find and contact nearby drivers for a new order
     * @return true if at least one driver was found and notified
     */
    suspend fun findNearbyDriversForOrder(orderId: String): Boolean {
        try {
            // Get the order details
            val orderDoc = firestore.collection("orders").document(orderId).get().await()
            val order = orderDoc.toObject(Order::class.java) ?: run {
                Log.e(TAG, "Order not found: $orderId")
                return false
            }

            // Check if order already has an assigned driver
            if (!order.driverUid.isNullOrEmpty()) {
                Log.d(TAG, "Order $orderId already has an assigned driver")
                return true
            }

            // Get the origin location from order
            val pickupLat = order.originLat
            val pickupLon = order.originLon
            val truckType = order.truckType

            // Find available drivers with the matching truck type
            val drivers = findAvailableDriversByLocation(truckType, pickupLat, pickupLon)

            if (drivers.isEmpty()) {
                Log.d(TAG, "No available drivers found for order $orderId")

                // Update order status to indicate no drivers found
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available")

                return false
            }

            Log.d(TAG, "Found ${drivers.size} available drivers for order $orderId")

            // Create a list of drivers to contact, ordered by distance
            val driversToContact = drivers.take(MAX_DRIVERS_TO_CONTACT)

            // Store the driver contact list in the order document
            setupDriverContactProcess(orderId, driversToContact)

            // Start the notification process for the first driver
            notifyNextDriverForOrder(orderId)

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error finding drivers for order $orderId: ${e.message}")
            return false
        }
    }

    /**
     * Find available drivers matching the required truck type within a reasonable distance of pickup
     */
    private suspend fun findAvailableDriversByLocation(
        truckType: String,
        pickupLat: Double,
        pickupLon: Double
    ): List<DriverInfo> {
        val result = mutableListOf<DriverInfo>()

        // Query for available drivers with matching truck type
        val driversQuery = firestore.collection("users")
            .whereEqualTo("userType", "driver")
            .whereEqualTo("available", true)
            .whereEqualTo("truckType", truckType)
            .get()
            .await()

        // Filter drivers based on location and sort by distance
        for (doc in driversQuery.documents) {
            val driverData = doc.data ?: continue
            val driverId = doc.id

            // Check if driver has location data
            val locationObj = driverData["location"]
            val driverLat: Double
            val driverLon: Double

            // Handle different location formats
            when (locationObj) {
                is GeoPoint -> {
                    driverLat = locationObj.latitude
                    driverLon = locationObj.longitude
                }
                is Map<*, *> -> {
                    driverLat = (locationObj["latitude"] as? Number)?.toDouble() ?: continue
                    driverLon = (locationObj["longitude"] as? Number)?.toDouble() ?: continue
                }
                else -> continue // Skip drivers without valid location
            }

            // Calculate distance between driver and pickup location
            val distanceKm = calculateDistance(pickupLat, pickupLon, driverLat, driverLon)

            if (distanceKm <= MAX_SEARCH_DISTANCE_KM) {
                // Get driver's FCM token and name
                val fcmToken = driverData["fcmToken"] as? String ?: ""
                val displayName = driverData["displayName"] as? String ?: "Driver"

                // Skip drivers without FCM tokens
                if (fcmToken.isEmpty()) continue

                // Add driver to the list with their distance
                result.add(
                    DriverInfo(
                        id = driverId,
                        name = displayName,
                        fcmToken = fcmToken,
                        distanceKm = distanceKm
                    )
                )
            }
        }

        // Sort drivers by distance (closest first)
        return result.sortedBy { it.distanceKm }
    }

    /**
     * Set up the driver contact process and initialize the contact list
     */
    private suspend fun setupDriverContactProcess(orderId: String, drivers: List<DriverInfo>) {
        // Create a map of driver IDs to contact status
        val driversContactList = mutableMapOf<String, String>()

        for (driver in drivers) {
            driversContactList[driver.id] = "pending" // Status: pending, notified, rejected, accepted
        }

        // Update the order with the driver contact list
        val updates = mapOf(
            "driversContactList" to driversContactList,
            "currentDriverIndex" to 0,
            "lastDriverNotificationTime" to System.currentTimeMillis()
        )

        firestore.collection("orders").document(orderId)
            .update(updates)
            .await()

        Log.d(TAG, "Set up driver contact process for order $orderId with ${drivers.size} drivers")
    }

    /**
     * Notify the next driver in the queue for this order
     */
    suspend fun notifyNextDriverForOrder(orderId: String): Boolean {
        try {
            // Get the current order data
            val orderDoc = firestore.collection("orders").document(orderId).get().await()
            val orderData = orderDoc.data ?: run {
                Log.e(TAG, "Order not found: $orderId")
                return false
            }

            // Check if order already has an assigned driver
            if (orderData["driverUid"] != null) {
                Log.d(TAG, "Order $orderId already has a driver assigned")
                return true
            }

            // Get the drivers contact list and current index
            @Suppress("UNCHECKED_CAST")
            val driversContactList = orderData["driversContactList"] as? Map<String, String> ?: run {
                Log.e(TAG, "No driver contact list found for order $orderId")
                return false
            }

            val currentIndex = orderData["currentDriverIndex"] as? Long ?: 0

            // Get all driver IDs as a list
            val driverIds = driversContactList.keys.toList()

            // Check if we've gone through all drivers
            if (currentIndex >= driverIds.size) {
                Log.d(TAG, "All drivers have been contacted for order $orderId")

                // Update order status to indicate no drivers accepted
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Accepted")

                return false
            }

            // Get the next driver to contact
            val driverId = driverIds[currentIndex.toInt()]
            val status = driversContactList[driverId]

            // Skip drivers that have already been notified or rejected
            if (status != "pending") {
                // Move to the next driver
                firestore.collection("orders").document(orderId)
                    .update("currentDriverIndex", currentIndex + 1)
                    .await()

                return notifyNextDriverForOrder(orderId)
            }

            // Get driver's FCM token
            val driverDoc = firestore.collection("users").document(driverId).get().await()
            val fcmToken = driverDoc?.getString("fcmToken")

            if (fcmToken.isNullOrEmpty()) {
                Log.e(TAG, "Driver $driverId has no FCM token")

                // Mark driver as skipped and move to the next one
                val updatedList = driversContactList.toMutableMap()
                updatedList[driverId] = "skipped"

                firestore.collection("orders").document(orderId)
                    .update(
                        mapOf(
                            "driversContactList" to updatedList,
                            "currentDriverIndex" to currentIndex + 1
                        )
                    )
                    .await()

                return notifyNextDriverForOrder(orderId)
            }

            // Mark the driver as notified
            val updatedList = driversContactList.toMutableMap()
            updatedList[driverId] = "notified"

            firestore.collection("orders").document(orderId)
                .update(
                    mapOf(
                        "driversContactList" to updatedList,
                        "lastDriverNotificationTime" to System.currentTimeMillis()
                    )
                )
                .await()

            // Send notification to the driver using FCM
            val success = NotificationService.sendDriverOrderNotification(
                fcmToken = fcmToken,
                orderId = orderId,
                driverId = driverId,
                order = Order()
            )

            if (success) {
                Log.d(TAG, "Successfully notified driver $driverId for order $orderId")

                // Schedule a check for timeout
                DriverTimeoutManager.scheduleDriverResponseTimeout(orderId, DRIVER_NOTIFICATION_TIMEOUT_MS)

                return true
            } else {
                Log.e(TAG, "Failed to notify driver $driverId")

                // Mark as failed and move to next driver
                updatedList[driverId] = "failed"

                firestore.collection("orders").document(orderId)
                    .update(
                        mapOf(
                            "driversContactList" to updatedList,
                            "currentDriverIndex" to currentIndex + 1
                        )
                    )
                    .await()

                return notifyNextDriverForOrder(orderId)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error notifying next driver for order $orderId: ${e.message}")
            return false
        }
    }

    /**
     * Calculate distance between two points using the Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth radius in kilometers
        val latDistance = Math.toRadians(lat2 - lat1)
        val lonDistance = Math.toRadians(lon2 - lon1)

        val a = sin(latDistance / 2) * sin(latDistance / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(lonDistance / 2) * sin(lonDistance / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    /**
     * Data class to hold information about a driver
     */
    data class DriverInfo(
        val id: String,
        val name: String,
        val fcmToken: String,
        val distanceKm: Double
    )
}