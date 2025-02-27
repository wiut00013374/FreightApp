//package com.example.freightapp.services
//
//import android.util.Log
//import com.example.freightapp.Order
//import com.google.firebase.firestore.FirebaseFirestore
//import com.google.firebase.firestore.GeoPoint
//import com.google.firebase.functions.FirebaseFunctions
//import com.google.firebase.firestore.QuerySnapshot
//import kotlinx.coroutines.tasks.await
//import kotlin.math.*
//
///**
// * Service to match orders with nearby available drivers
// */
//object DriverMatchingService {
//    private const val TAG = "DriverMatchingService"
//    private const val MAX_SEARCH_DISTANCE_KM = 30.0 // Maximum search radius in kilometers
//    private const val MAX_DRIVERS_TO_NOTIFY = 10 // Maximum number of drivers to notify for one order
//    private const val DRIVER_NOTIFICATION_TIMEOUT_MS = 60000L // 60 seconds before moving to next driver
//
//    private val firestore = FirebaseFirestore.getInstance()
//    private val functions = FirebaseFunctions.getInstance()
//
//    /**
//     * Main function to find and notify nearby drivers for a new order
//     */
//    suspend fun findNearbyDriversForOrder(orderId: String): Boolean {
//        try {
//            // Get the order details
//            val orderDoc = firestore.collection("orders").document(orderId).get().await()
//            val order = orderDoc.toObject(Order::class.java) ?: return false
//
//            // Check if order already has an assigned driver
//            if (!order.driverUid.isNullOrEmpty()) {
//                Log.d(TAG, "Order $orderId already has an assigned driver")
//                return true
//            }
//
//            // Get the origin location from order
//            val pickupLat = order.originLat
//            val pickupLon = order.originLon
//
//            // Find available drivers with the matching truck type
//            val drivers = findAvailableDrivers(order.truckType, pickupLat, pickupLon)
//
//            if (drivers.isEmpty()) {
//                Log.d(TAG, "No available drivers found for order $orderId")
//                return false
//            }
//
//            Log.d(TAG, "Found ${drivers.size} available drivers for order $orderId")
//
//            // Create or update the driversContacted field in the order
//            val driversToContact = drivers.take(MAX_DRIVERS_TO_NOTIFY)
//            setupDriverContactProcess(orderId, driversToContact)
//
//            // Start the notification process for the first driver
//            notifyNextDriverForOrder(orderId)
//            return true
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error finding drivers for order $orderId: ${e.message}")
//            return false
//        }
//    }
//
//    /**
//     * Find available drivers matching the order requirements
//     */
//    private suspend fun findAvailableDrivers(
//        truckType: String,
//        pickupLat: Double,
//        pickupLon: Double
//    ): List<Map<String, Any>> {
//        val result = mutableListOf<Map<String, Any>>()
//
//        // Query for available drivers with matching truck type
//        val driversQuery = firestore.collection("users")
//            .whereEqualTo("userType", "driver")
//            .whereEqualTo("available", true)
//            .whereEqualTo("truckType", truckType)
//            .get()
//            .await()
//
//        // Filter drivers based on distance and sort by closest first
//        for (doc in driversQuery.documents) {
//            val driverData = doc.data ?: continue
//            val driverId = doc.id
//
//            // Check if driver has location data
//            val location = driverData["location"] as? GeoPoint
//            if (location != null) {
//                val driverLat = location.latitude
//                val driverLon = location.longitude
//
//                // Calculate distance between driver and pickup location
//                val distanceKm = calculateDistance(pickupLat, pickupLon, driverLat, driverLon)
//
//                if (distanceKm <= MAX_SEARCH_DISTANCE_KM) {
//                    // Add driver to the list with their distance
//                    val driverInfo = driverData.toMutableMap()
//                    driverInfo["id"] = driverId
//                    driverInfo["distanceKm"] = distanceKm
//                    result.add(driverInfo)
//                }
//            }
//        }
//
//        // Sort drivers by distance (closest first)
//        return result.sortedBy { it["distanceKm"] as Double }
//    }
//
//    /**
//     * Setup the drivers to be contacted for this order
//     */
//    private suspend fun setupDriverContactProcess(orderId: String, drivers: List<Map<String, Any>>) {
//        // Create a map of driver IDs to contact status
//        val driversToContact = mutableMapOf<String, String>()
//
//        for (driver in drivers) {
//            val driverId = driver["id"] as String
//            driversToContact[driverId] = "pending" // Status: pending, notified, rejected, accepted
//        }
//
//        // Update the order with the list of drivers to contact
//        if (driversToContact.isNotEmpty()) {
//            firestore.collection("orders").document(orderId)
//                .update(
//                    mapOf(
//                        "driversContactList" to driversToContact,
//                        "currentDriverIndex" to 0,
//                        "lastDriverNotificationTime" to System.currentTimeMillis()
//                    )
//                )
//                .await()
//        }
//    }
//
//    /**
//     * Notify the next driver in the queue for this order
//     */
//    suspend fun notifyNextDriverForOrder(orderId: String): Boolean {
//        try {
//            // Get the current order data
//            val orderDoc = firestore.collection("orders").document(orderId).get().await()
//            val order = orderDoc.toObject(Order::class.java) ?: return false
//
//            // Check if order already has an assigned driver
//            if (!order.driverUid.isNullOrEmpty()) {
//                Log.d(TAG, "Order $orderId already has an assigned driver")
//                return true
//            }
//
//            // Get the drivers contact list and current index
//            val driversContactList = orderDoc.get("driversContactList") as? Map<String, String> ?: return false
//            val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0
//
//            // Check if we've gone through all drivers
//            if (currentIndex >= driversContactList.size) {
//                Log.d(TAG, "All drivers have been contacted for order $orderId")
//                return false
//            }
//
//            // Get the next driver to contact
//            val driverEntry = driversContactList.entries.toList()[currentIndex]
//            val driverId = driverEntry.key
//            val status = driverEntry.value
//
//            // Skip drivers that have already been notified or rejected
//            if (status != "pending") {
//                // Move to the next driver
//                firestore.collection("orders").document(orderId)
//                    .update("currentDriverIndex", currentIndex + 1)
//                    .await()
//
//                return notifyNextDriverForOrder(orderId)
//            }
//
//            // Send notification to the driver
//            val success = sendDriverNotification(driverId, orderId)
//
//            if (success) {
//                // Update the driver's status to "notified"
//                val updatedList = driversContactList.toMutableMap()
//                updatedList[driverId] = "notified"
//
//                firestore.collection("orders").document(orderId)
//                    .update(
//                        mapOf(
//                            "driversContactList" to updatedList,
//                            "lastDriverNotificationTime" to System.currentTimeMillis()
//                        )
//                    )
//                    .await()
//
//                Log.d(TAG, "Driver $driverId notified for order $orderId")
//                return true
//            } else {
//                // If notification failed, move to the next driver
//                firestore.collection("orders").document(orderId)
//                    .update("currentDriverIndex", currentIndex + 1)
//                    .await()
//
//                return notifyNextDriverForOrder(orderId)
//            }
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error notifying next driver for order $orderId: ${e.message}")
//            return false
//        }
//    }
//
//    /**
//     * Check if we need to move to the next driver (if current driver didn't respond in time)
//     */
//    suspend fun checkDriverResponseTimeout(orderId: String): Boolean {
//        try {
//            // Get the current order data
//            val orderDoc = firestore.collection("orders").document(orderId).get().await()
//
//            // Check if order already has an assigned driver
//            val driverUid = orderDoc.getString("driverUid")
//            if (driverUid != null) {
//                Log.d(TAG, "Order $orderId already has an assigned driver")
//                return true
//            }
//
//            // Get the last notification time
//            val lastNotificationTime = orderDoc.getLong("lastDriverNotificationTime") ?: return false
//            val currentTime = System.currentTimeMillis()
//
//            // Check if we've waited long enough
//            if (currentTime - lastNotificationTime > DRIVER_NOTIFICATION_TIMEOUT_MS) {
//                // Time's up for the current driver, move to the next one
//                val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0
//
//                // Update the index and move to the next driver
//                firestore.collection("orders").document(orderId)
//                    .update("currentDriverIndex", currentIndex + 1)
//                    .await()
//
//                return notifyNextDriverForOrder(orderId)
//            }
//
//            return false
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error checking driver response timeout: ${e.message}")
//            return false
//        }
//    }
//
//    /**
//     * Send notification to a driver about a new order
//     */
//    private suspend fun sendDriverNotification(driverId: String, orderId: String): Boolean {
//        try {
//            // Get the driver's FCM token
//            val driverDoc = firestore.collection("users").document(driverId).get().await()
//            val fcmToken = driverDoc.getString("fcmToken")
//
//            if (fcmToken.isNullOrEmpty()) {
//                Log.e(TAG, "Driver $driverId has no FCM token")
//                return false
//            }
//
//            // Get order info for the notification
//            val orderDoc = firestore.collection("orders").document(orderId).get().await()
//            val order = orderDoc.toObject(Order::class.java)
//
//            if (order == null) {
//                Log.e(TAG, "Order $orderId not found")
//                return false
//            }
//
//            // Call the Firebase Cloud Function to send the notification
//            // This requires setting up a Cloud Function in your Firebase project
//            val data = hashMapOf(
//                "token" to fcmToken,
//                "title" to "New Order Available",
//                "body" to "From ${order.originCity} to ${order.destinationCity} - $${order.totalPrice}",
//                "orderId" to orderId,
//                "driverId" to driverId
//            )
//
//            functions.getHttpsCallable("sendDriverNotification")
//                .call(data)
//                .await()
//
//            return true
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error sending notification to driver $driverId: ${e.message}")
//            return false
//        }
//    }
//
//    /**
//     * Process a driver's response to an order notification
//     */
//    suspend fun processDriverResponse(driverId: String, orderId: String, accepted: Boolean): Boolean {
//        try {
//            // Get the current order data
//            val orderDoc = firestore.collection("orders").document(orderId).get().await()
//
//            // Check if order already has an assigned driver
//            val currentDriverUid = orderDoc.getString("driverUid")
//            if (currentDriverUid != null && currentDriverUid != driverId) {
//                // Order was assigned to a different driver
//                Log.d(TAG, "Order $orderId already assigned to driver $currentDriverUid")
//                return false
//            }
//
//            // Get the drivers contact list
//            val driversContactList = orderDoc.get("driversContactList") as? Map<String, String> ?: return false
//
//            // Update the driver's status in the list
//            val updatedList = driversContactList.toMutableMap()
//            updatedList[driverId] = if (accepted) "accepted" else "rejected"
//
//            if (accepted) {
//                // Assign the driver to the order
//                firestore.collection("orders").document(orderId)
//                    .update(
//                        mapOf(
//                            "driverUid" to driverId,
//                            "status" to "Accepted",
//                            "driversContactList" to updatedList
//                        )
//                    )
//                    .await()
//
//                Log.d(TAG, "Driver $driverId accepted order $orderId")
//                return true
//            } else {
//                // Driver rejected, update the list and move to the next driver
//                firestore.collection("orders").document(orderId)
//                    .update(
//                        mapOf(
//                            "driversContactList" to updatedList,
//                            "currentDriverIndex" to (orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0) + 1
//                        )
//                    )
//                    .await()
//
//                // Notify the next driver
//                return notifyNextDriverForOrder(orderId)
//            }
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error processing driver response: ${e.message}")
//            return false
//        }
//    }
//
//    /**
//     * Calculate distance between two points using the Haversine formula
//     */
//    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
//        val R = 6371.0 // Earth's radius in km
//
//        val latDistance = Math.toRadians(lat2 - lat1)
//        val lonDistance = Math.toRadians(lon2 - lon1)
//
//        val a = sin(latDistance / 2) * sin(latDistance / 2) +
//                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
//                sin(lonDistance / 2) * sin(lonDistance / 2)
//
//        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
//
//        return R * c
//    }
//}