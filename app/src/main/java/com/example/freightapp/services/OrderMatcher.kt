package com.example.freightapp.services

import android.content.Context
import android.util.Log
import com.example.freightapp.model.Order
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.*

class OrderMatcher(private val context: Context) {
    private val TAG = "OrderMatcher"
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        const val MAX_SEARCH_DISTANCE_KM = 50.0
        const val MAX_DRIVERS_TO_CONTACT = 10
        const val DRIVER_RESPONSE_TIMEOUT_SECONDS = 60L
    }

    suspend fun matchOrderToDrivers(order: Order): Boolean {
        try {
            firestore.collection("orders").document(order.id)
                .update("status", "Looking For Driver")
                .await()

            val nearbyDrivers = findNearbyDrivers(order)

            if (nearbyDrivers.isEmpty()) {
                Log.d(TAG, "No available drivers found for order ${order.id}")
                firestore.collection("orders").document(order.id)
                    .update("status", "No Drivers Available")
                    .await()
                return false
            }

            Log.d(TAG, "Found ${nearbyDrivers.size} available drivers for order ${order.id}")
            val driversToContact = nearbyDrivers.take(MAX_DRIVERS_TO_CONTACT)
            val driversContactList = driversToContact.associate { driver ->
                driver.id to "pending"
            }

            firestore.collection("orders").document(order.id)
                .update(
                    mapOf(
                        "driversContactList" to driversContactList,
                        "currentDriverIndex" to 0,
                        "lastDriverNotificationTime" to System.currentTimeMillis()
                    )
                )
                .await()

            notifyNextDriver(order.id)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error matching order to drivers: ${e.message}")
            try {
                firestore.collection("orders").document(order.id)
                    .update("status", "Driver Search Failed")
                    .await()
            } catch (e2: Exception) {
                Log.e(TAG, "Error updating order status: ${e2.message}")
            }
            return false
        }
    }

    private suspend fun findNearbyDrivers(order: Order): List<DriverCandidate> {
        try {
            val driversQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .whereEqualTo("truckType", order.truckType)
                .get()
                .await()

            val candidates = mutableListOf<DriverCandidate>()

            for (doc in driversQuery.documents) {
                val driverData = doc.data ?: continue
                val driverId = doc.id
                val fcmToken = driverData["fcmToken"] as? String
                if (fcmToken.isNullOrBlank()) continue

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

                val distanceKm = calculateHaversineDistance(
                    order.originLat, order.originLon,
                    driverLat, driverLon
                )

                if (distanceKm <= MAX_SEARCH_DISTANCE_KM) {
                    val driverName = driverData["displayName"] as? String ?: "Driver"
                    candidates.add(
                        DriverCandidate(
                            id = driverId,
                            name = driverName,
                            fcmToken = fcmToken,
                            distanceKm = distanceKm
                        )
                    )
                }
            }
            return candidates.sortedBy { it.distanceKm }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding nearby drivers: ${e.message}")
            return emptyList()
        }
    }

    suspend fun notifyNextDriver(orderId: String): Boolean {
        try {
            Log.d(TAG, "Attempting to notify next driver for order: $orderId")
            val orderDoc = firestore.collection("orders").document(orderId).get().await()

            if (!orderDoc.exists()) {
                Log.e(TAG, "Order not found: $orderId")
                return false
            }

            // Check if order already has an assigned driver
            if (orderDoc.getString("driverUid") != null) {
                Log.d(TAG, "Order $orderId already has a driver assigned")
                return true
            }

            // Get the drivers contact list and current index
            @Suppress("UNCHECKED_CAST")
            val driversContactList = orderDoc.get("driversContactList") as? Map<String, String>
            if (driversContactList.isNullOrEmpty()) {
                Log.d(TAG, "No drivers to contact for order $orderId")
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available")
                    .await()
                return false
            }

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

            // Get the next driver to contact
            val driverId = driverIds[currentIndex]
            val driverStatus = driversContactList[driverId]

            // Skip drivers that have already been notified or rejected
            if (driverStatus != "pending") {
                Log.d(TAG, "Driver $driverId status is $driverStatus, moving to next driver")
                firestore.collection("orders").document(orderId)
                    .update("currentDriverIndex", currentIndex + 1)
                    .await()
                return notifyNextDriver(orderId)
            }

            // Get driver's FCM token
            val driverDoc = firestore.collection("users").document(driverId).get().await()
            val fcmToken = driverDoc?.getString("fcmToken")

            if (fcmToken.isNullOrBlank()) {
                Log.e(TAG, "Driver $driverId has no FCM token")

                // Mark driver as skipped and move to next
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

            // Get the order object
            val order = orderDoc.toObject(Order::class.java)?.apply { id = orderId } ?: return false

            // Use the direct FCM HTTP API for more reliability
            val notificationSuccess = notifyDriverAboutOrder(order, driverId, fcmToken)

            if (notificationSuccess) {
                Log.d(TAG, "Successfully notified driver $driverId for order $orderId")

                // Update driver status to notified
                val updatedList = driversContactList.toMutableMap()
                updatedList[driverId] = "notified"

                firestore.collection("orders").document(orderId)
                    .update(
                        "driversContactList", updatedList,
                        "lastDriverNotificationTime", System.currentTimeMillis()
                    )
                    .await()

                // Schedule timeout check
                scheduleDriverResponseTimeout(orderId)
                return true
            } else {
                Log.e(TAG, "Failed to notify driver $driverId")

                // Mark as failed and move to next driver
                val updatedList = driversContactList.toMutableMap()
                updatedList[driverId] = "failed"

                firestore.collection("orders").document(orderId)
                    .update(
                        "driversContactList", updatedList,
                        "currentDriverIndex", currentIndex + 1
                    )
                    .await()

                return notifyNextDriver(orderId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying next driver: ${e.message}")
            return false
        }
    }

    private suspend fun notifyDriverAboutOrder(order: Order, driverId: String, fcmToken: String): Boolean {
        try {
            Log.d(TAG, "Sending FCM notification to driver $driverId for order ${order.id}")

            // Format price
            val formattedPrice = String.format("$%.2f", order.totalPrice)

            // Create the data map for the notification
            val notificationData = mapOf(
                "orderId" to order.id,
                "type" to "order_request",
                "originCity" to order.originCity,
                "destinationCity" to order.destinationCity,
                "price" to formattedPrice,
                "truckType" to order.truckType,
                "volume" to order.volume.toString(),
                "weight" to order.weight.toString(),
                "click_action" to "OPEN_ORDER_REQUEST"
            )

            // Use NotificationService to send the FCM message
            return NotificationService.sendDriverOrderNotification(
                context = context,
                fcmToken = fcmToken,
                orderId = order.id,
                driverId = driverId,
                order = order
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error sending notification to driver: ${e.message}", e)
            return false
        }
    }


    private fun scheduleDriverResponseTimeout(orderId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(DRIVER_RESPONSE_TIMEOUT_SECONDS * 1000)
                val orderDoc = firestore.collection("orders").document(orderId).get().await()
                if (!orderDoc.exists()) return@launch

                val driverUid = orderDoc.getString("driverUid")
                if (driverUid != null) return@launch

                val lastNotificationTime = orderDoc.getLong("lastDriverNotificationTime") ?: 0L
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastNotificationTime >= DRIVER_RESPONSE_TIMEOUT_SECONDS * 1000) {
                    val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0
                    firestore.collection("orders").document(orderId)
                        .update("currentDriverIndex", currentIndex + 1)
                        .await()
                    notifyNextDriver(orderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in driver response timeout: ${e.message}")
            }
        }
    }

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

data class DriverCandidate(
    val id: String,
    val name: String,
    val fcmToken: String,
    val distanceKm: Double
)