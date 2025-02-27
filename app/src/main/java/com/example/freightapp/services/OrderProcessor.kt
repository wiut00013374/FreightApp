package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.tasks.await
import kotlin.math.*

/**
 * Service for processing customer orders and finding drivers
 */
class OrderProcessor {
    private val TAG = "OrderProcessor"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Configuration
    companion object {
        private const val MAX_SEARCH_DISTANCE_KM = 50.0
        private const val MAX_DRIVERS_TO_CONTACT = 10
    }

    /**
     * Create a new order and start the driver matching process
     * @return Pair<success, orderId>
     */
    suspend fun createOrder(order: Order): Pair<Boolean, String?> {
        return try {
            // Validate order details
            validateOrder(order)

            // Get current user ID
            val currentUserId = auth.currentUser?.uid
                ?: throw IllegalStateException("User not authenticated")

            // Create order document in Firestore
            val orderRef = firestore.collection("orders").document()

            // Set the ID and customer ID
            val processedOrder = order.copy(
                id = orderRef.id,
                uid = currentUserId,
                status = "Looking For Driver", // Initial status while searching for driver
                timestamp = System.currentTimeMillis()
            )

            // Save order to Firestore
            orderRef.set(processedOrder).await()

            Log.d(TAG, "Order created with ID: ${processedOrder.id}")

            // Find and match with nearby drivers
            findDriversForOrder(processedOrder)

            return Pair(true, processedOrder.id)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating order: ${e.message}")
            return Pair(false, null)
        }
    }

    /**
     * Find drivers for a newly created order
     */
    private suspend fun findDriversForOrder(order: Order): Boolean {
        try {
            // Find nearby available drivers with the matching truck type
            val availableDrivers = findNearbyDrivers(
                order.truckType,
                order.originLat,
                order.originLon
            )

            if (availableDrivers.isEmpty()) {
                Log.d(TAG, "No available drivers found for order ${order.id}")

                // Update order status to indicate no drivers available
                firestore.collection("orders").document(order.id)
                    .update("status", "No Drivers Available")
                    .await()

                return false
            }

            Log.d(TAG, "Found ${availableDrivers.size} available drivers for order ${order.id}")

            // Create a contacts list with all available drivers (limited to max)
            val driversToContact = availableDrivers.take(MAX_DRIVERS_TO_CONTACT)

            // Create map of driver IDs to contact status
            val driversContactList = mutableMapOf<String, String>()
            driversToContact.forEach { driver ->
                driversContactList[driver.id] = "pending" // pending, notified, accepted, rejected
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

            // The actual notification of drivers will be handled by a Cloud Function
            // which will be triggered by the changes we just made to the order document

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error finding drivers for order: ${e.message}")

            // Update order status to indicate driver search failed
            try {
                firestore.collection("orders").document(order.id)
                    .update("status", "Driver Search Failed")
                    .await()
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to update order status: ${e2.message}")
            }

            return false
        }
    }

    /**
     * Find nearby available drivers that match the truck type
     */
    private suspend fun findNearbyDrivers(
        truckType: String,
        originLat: Double,
        originLon: Double
    ): List<DriverInfo> {
        try {
            // Query for available drivers with matching truck type
            val driversQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .whereEqualTo("truckType", truckType)
                .get()
                .await()

            Log.d(TAG, "Found ${driversQuery.documents.size} potential drivers with matching truck type")

            val drivers = mutableListOf<DriverInfo>()

            // Filter drivers based on location and sort by distance
            for (doc in driversQuery.documents) {
                val driverData = doc.data ?: continue
                val driverId = doc.id

                // Check for FCM token (needed for notifications)
                val fcmToken = driverData["fcmToken"] as? String ?: continue
                if (fcmToken.isBlank()) {
                    Log.d(TAG, "Driver $driverId has no FCM token, skipping")
                    continue
                }

                // Extract driver location
                val locationObj = driverData["location"]
                val driverLat: Double
                val driverLon: Double
                var hasLocation = false

                when (locationObj) {
                    is GeoPoint -> {
                        driverLat = locationObj.latitude
                        driverLon = locationObj.longitude
                        hasLocation = true
                    }
                    is Map<*, *> -> {
                        val lat = (locationObj["latitude"] as? Number)?.toDouble()
                        val lon = (locationObj["longitude"] as? Number)?.toDouble()
                        if (lat != null && lon != null) {
                            driverLat = lat
                            driverLon = lon
                            hasLocation = true
                        } else {
                            continue
                        }
                    }
                    else -> {
                        // If no location data, add with null distance (will be sorted after drivers with known distance)
                        val driverName = driverData["displayName"] as? String ?: "Driver"
                        drivers.add(
                            DriverInfo(
                                id = driverId,
                                name = driverName,
                                fcmToken = fcmToken,
                                distanceKm = null
                            )
                        )
                        continue
                    }
                }

                // Calculate distance to pickup point
                if (hasLocation) {
                    val distanceKm = calculateDistance(
                        originLat, originLon,
                        driverLat, driverLon
                    )

                    // Only include drivers within the search radius
                    if (distanceKm <= MAX_SEARCH_DISTANCE_KM) {
                        val driverName = driverData["displayName"] as? String ?: "Driver"
                        drivers.add(
                            DriverInfo(
                                id = driverId,
                                name = driverName,
                                fcmToken = fcmToken,
                                distanceKm = distanceKm
                            )
                        )
                    }
                }
            }

            // If no drivers with matching truck type, try to find any available drivers
            if (drivers.isEmpty()) {
                return findAnyAvailableDrivers(originLat, originLon)
            }

            // Sort by distance (closest first, null distance last)
            return drivers.sortedWith(compareBy(
                // Sort nulls last
                { it.distanceKm == null },
                // Then sort by distance
                { it.distanceKm ?: Double.MAX_VALUE }
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Error finding nearby drivers: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Find any available drivers if no matching truck type is found
     */
    private suspend fun findAnyAvailableDrivers(
        originLat: Double,
        originLon: Double
    ): List<DriverInfo> {
        try {
            // Query for all available drivers
            val driversQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .get()
                .await()

            Log.d(TAG, "Searching for any available drivers, found ${driversQuery.documents.size}")

            val drivers = mutableListOf<DriverInfo>()

            // Process each driver (similar to findNearbyDrivers but without truck type filter)
            for (doc in driversQuery.documents) {
                val driverData = doc.data ?: continue
                val driverId = doc.id
                val fcmToken = driverData["fcmToken"] as? String ?: continue

                if (fcmToken.isBlank()) continue

                // Process location and calculate distance as in findNearbyDrivers
                val locationObj = driverData["location"]
                val driverName = driverData["displayName"] as? String ?: "Driver"

                if (locationObj == null) {
                    // Add without location data
                    drivers.add(
                        DriverInfo(
                            id = driverId,
                            name = driverName,
                            fcmToken = fcmToken,
                            distanceKm = null
                        )
                    )
                    continue
                }

                var driverLat = 0.0
                var driverLon = 0.0
                var hasLocation = false

                when (locationObj) {
                    is GeoPoint -> {
                        driverLat = locationObj.latitude
                        driverLon = locationObj.longitude
                        hasLocation = true
                    }
                    is Map<*, *> -> {
                        val lat = (locationObj["latitude"] as? Number)?.toDouble()
                        val lon = (locationObj["longitude"] as? Number)?.toDouble()
                        if (lat != null && lon != null) {
                            driverLat = lat
                            driverLon = lon
                            hasLocation = true
                        }
                    }
                }

                if (hasLocation) {
                    val distanceKm = calculateDistance(
                        originLat, originLon,
                        driverLat, driverLon
                    )

                    if (distanceKm <= MAX_SEARCH_DISTANCE_KM) {
                        drivers.add(
                            DriverInfo(
                                id = driverId,
                                name = driverName,
                                fcmToken = fcmToken,
                                distanceKm = distanceKm
                            )
                        )
                    }
                } else {
                    // Add without distance
                    drivers.add(
                        DriverInfo(
                            id = driverId,
                            name = driverName,
                            fcmToken = fcmToken,
                            distanceKm = null
                        )
                    )
                }
            }

            // Sort as before
            return drivers.sortedWith(compareBy(
                { it.distanceKm == null },
                { it.distanceKm ?: Double.MAX_VALUE }
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Error finding any available drivers: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Calculate distance between two points using the Haversine formula
     */
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371.0 // Earth's radius in kilometers
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
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
        require(order.volume > 0) { "Volume must be positive" }
        require(order.weight > 0) { "Weight must be positive" }
    }
}

/**
 * Data class for driver information during matching process
 */
data class DriverInfo(
    val id: String,
    val name: String,
    val fcmToken: String,
    val distanceKm: Double?  // null if distance is unknown
)