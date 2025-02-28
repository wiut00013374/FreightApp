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
            val orderDoc = firestore.collection("orders").document(orderId).get().await()

            if (!orderDoc.exists()) return false

            val driverUid = orderDoc.getString("driverUid")
            if (driverUid != null) return true

            @Suppress("UNCHECKED_CAST")
            val driversContactList = orderDoc.get("driversContactList") as? Map<String, String>
            if (driversContactList.isNullOrEmpty()) {
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available")
                    .await()
                return false
            }

            val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0
            val driverIds = driversContactList.keys.toList()

            if (currentIndex >= driverIds.size) {
                firestore.collection("orders").document(orderId)
                    .update("status", "No Drivers Available")
                    .await()
                return false
            }

            val driverId = driverIds[currentIndex]
            val status = driversContactList[driverId]

            if (status != "pending") {
                firestore.collection("orders").document(orderId)
                    .update("currentDriverIndex", currentIndex + 1)
                    .await()
                return notifyNextDriver(orderId)
            }

            val driverDoc = firestore.collection("users").document(driverId).get().await()
            val fcmToken = driverDoc.getString("fcmToken")

            if (fcmToken.isNullOrBlank()) {
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

            val order = orderDoc.toObject(Order::class.java)?.apply { id = orderId } ?: return false

            val notificationSuccess = NotificationService.sendDriverOrderNotification(
                context = context,
                fcmToken = fcmToken,
                orderId = orderId,
                driverId = driverId,
                order = order
            )

            if (notificationSuccess) {
                val updatedList = driversContactList.toMutableMap()
                updatedList[driverId] = "notified"
                firestore.collection("orders").document(orderId)
                    .update(
                        "driversContactList", updatedList,
                        "lastDriverNotificationTime", System.currentTimeMillis()
                    )
                    .await()
                scheduleDriverResponseTimeout(orderId)
                return true
            } else {
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