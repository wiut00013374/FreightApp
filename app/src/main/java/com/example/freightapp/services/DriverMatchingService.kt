package com.example.freightapp.services

import android.content.Context
import android.util.Log
import com.example.freightapp.model.Order
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.tasks.await
import kotlin.math.*

class DriverMatchingService(private val context: Context) {
    private val TAG = "DriverMatchingService"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val orderMatcher = OrderMatcher(context)

    // Configuration
    companion object {
        private const val MAX_SEARCH_DISTANCE_KM = 100.0 // Increased distance
        private const val MAX_DRIVERS_TO_CONTACT = 5
    }

    suspend fun findDriversForOrder(order: Order): List<DriverFinder.DriverInfo> {
        try {
            Log.d(TAG, "Starting driver search for order ${order.id}")
            Log.d(TAG, "Order details: truckType=${order.truckType}, origin=[${order.originLat}, ${order.originLon}]")

            // First get ALL available drivers regardless of truck type to see what's available
            val initialQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .get()
                .await()

            Log.d(TAG, "Found ${initialQuery.documents.size} total available drivers")

            // Then filter for truck type match
            val driversQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .whereEqualTo("truckType", order.truckType)
                .get()
                .await()

            Log.d(TAG, "Found ${driversQuery.documents.size} drivers matching truck type: ${order.truckType}")

            val drivers = mutableListOf<DriverFinder.DriverInfo>()

            // Process each potential driver
            for (doc in driversQuery.documents) {
                val driverData = doc.data ?: continue
                val driverId = doc.id

                Log.d(TAG, "Processing driver $driverId: ${driverData["displayName"] ?: "Unknown Driver"}")

                // Check FCM token
                val fcmToken = driverData["fcmToken"] as? String
                if (fcmToken.isNullOrEmpty()) {
                    Log.d(TAG, "Driver $driverId has no FCM token, skipping")
                    continue
                }

                // Extract location
                val locationObj = driverData["location"]
                val driverLat: Double
                val driverLon: Double

                when (locationObj) {
                    is GeoPoint -> {
                        driverLat = locationObj.latitude
                        driverLon = locationObj.longitude
                        Log.d(TAG, "Driver location from GeoPoint: [$driverLat, $driverLon]")
                    }
                    is Map<*, *> -> {
                        driverLat = (locationObj["latitude"] as? Number)?.toDouble() ?: 0.0
                        driverLon = (locationObj["longitude"] as? Number)?.toDouble() ?: 0.0
                        Log.d(TAG, "Driver location from Map: [$driverLat, $driverLon]")
                    }
                    else -> {
                        Log.d(TAG, "Driver $driverId has invalid location format: $locationObj")
                        continue
                    }
                }

                // Calculate distance
                val distance = calculateDistance(
                    order.originLat, order.originLon,
                    driverLat, driverLon
                )

                Log.d(TAG, "Driver $driverId is ${String.format("%.2f", distance)} km from order origin")

                // Include driver even if somewhat far away (we'll sort by distance later)
                if (distance <= MAX_SEARCH_DISTANCE_KM) {
                    val driverName = driverData["displayName"] as? String ?: "Driver"
                    drivers.add(
                        DriverFinder.DriverInfo(
                            id = driverId,
                            name = driverName,
                            fcmToken = fcmToken,
                            distanceKm = distance
                        )
                    )
                    Log.d(TAG, "Added driver $driverId ($driverName) to candidates list")
                } else {
                    Log.d(TAG, "Driver $driverId too far (${String.format("%.2f", distance)} km), skipping")
                }
            }

            // Sort by distance
            val sortedDrivers = drivers.sortedBy { it.distanceKm }
            Log.d(TAG, "Final candidate drivers count: ${sortedDrivers.size}")

            return sortedDrivers.take(MAX_DRIVERS_TO_CONTACT)

        } catch (e: Exception) {
            Log.e(TAG, "Error finding drivers: ${e.message}", e)
            return emptyList()
        }
    }

    suspend fun setupDriverContactProcess(orderId: String, drivers: List<DriverFinder.DriverInfo>) {
        try {
            Log.d(TAG, "Setting up driver contact process for order $orderId with ${drivers.size} drivers")

            // Create a map of driver IDs to contact status
            val driversContactList = mutableMapOf<String, String>()

            for (driver in drivers) {
                driversContactList[driver.id] = "pending"
                Log.d(TAG, "Added driver ${driver.id} (${driver.name}) to contact list")
            }

            // Update the order with the driver contact list
            val updates = mapOf(
                "driversContactList" to driversContactList,
                "currentDriverIndex" to 0,
                "lastDriverNotificationTime" to System.currentTimeMillis(),
                "status" to "Looking For Driver"
            )

            firestore.collection("orders").document(orderId)
                .update(updates)
                .await()

            Log.d(TAG, "Contact process initialized for order $orderId")

            // Notify first driver if any are available
            if (drivers.isNotEmpty()) {
                orderMatcher.notifyNextDriver(orderId)
            } else {
                Log.d(TAG, "No drivers available for order $orderId")
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available")
                    .await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up driver contact: ${e.message}", e)
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth's radius in kilometers
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}