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


    suspend fun createOrder(order: Order): Pair<Boolean, String?> {
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

            // Initiate driver matching process
            val driverAssigned = assignDriverToOrder(processedOrder)

            return Pair(true, processedOrder.id)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating order: ${e.message}")
            return Pair(false, null)
        }
    }


    private suspend fun assignDriverToOrder(order: Order): Boolean {
        try {
            // First, try to find drivers with the exact truck type
            var drivers = findNearbyDrivers(order.truckType, order.originLat, order.originLon)

            // If no drivers found with exact match, try with a more flexible approach
            if (drivers.isEmpty()) {
                Log.d(TAG, "No drivers with exact truck type match, trying any available driver")
                drivers = findAnyAvailableDrivers(order.originLat, order.originLon)
            }

            if (drivers.isEmpty()) {
                Log.d(TAG, "No available drivers found for order ${order.id}")
                // Update order status to indicate no drivers available
                firestore.collection("orders").document(order.id)
                    .update("status", "No Drivers Available").await()

                return false
            }

            Log.d(TAG, "Found ${drivers.size} available drivers for order ${order.id}")

            // Create a contact list with all available drivers
            val driversContactList = drivers.associateWith { "pending" }.toMutableMap()

            // Update the order with the contact list
            firestore.collection("orders").document(order.id)
                .update(
                    mapOf(
                        "driversContactList" to driversContactList,
                        "currentDriverIndex" to 0,
                        "lastDriverNotificationTime" to System.currentTimeMillis()
                    )
                ).await()
            // For testing purposes only - Auto-assign to the first driver
            // In production, you'd wait for driver acceptance
            if (drivers.isNotEmpty()) {
                val firstDriver = drivers.first()

                // Assign the order to this driver
                firestore.collection("orders").document(order.id)
                    .update(
                        mapOf(
                            "driverUid" to firstDriver,
                            "status" to "Accepted",
                            "acceptedAt" to System.currentTimeMillis()
                        )
                    ).await()

                Log.d(TAG, "Auto-assigned order ${order.id} to driver $firstDriver")

                // Update the driver's list of orders
                addOrderToDriverList(firstDriver, order.id)

                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error assigning driver to order: ${e.message}")
            return false
        }
    }


    private suspend fun addOrderToDriverList(driverId: String, orderId: String) {
        try {
            // Get the driver's current orders list
            val driverDoc = firestore.collection("users").document(driverId).get().await()

            @Suppress("UNCHECKED_CAST")
            val currentOrders = driverDoc.get("orders") as? MutableList<String> ?: mutableListOf()

            // Add the new order ID if it's not already in the list
            if (!currentOrders.contains(orderId)) {
                currentOrders.add(orderId)

                // Update the driver's document
                firestore.collection("users").document(driverId)
                    .update("orders", currentOrders).await()

                Log.d(TAG, "Added order $orderId to driver $driverId's orders list")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding order to driver's list: ${e.message}")
        }
    }


    private suspend fun findNearbyDrivers(truckType: String, originLat: Double, originLon: Double): List<String> {
        // Maximum distance in kilometers
        val MAX_SEARCH_DISTANCE = 50.0

        try {
            // Query for available drivers with matching truck type
            val driversQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .whereEqualTo("truckType", truckType)
                .get()
                .await()

            Log.d(TAG, "Found ${driversQuery.size()} drivers with matching truck type")

            // List to hold driver IDs
            val driverIds = mutableListOf<String>()

            // Process each driver
            for (doc in driversQuery.documents) {
                val driverId = doc.id
                val location = doc.get("location") as? GeoPoint

                // Check if driver has a valid location
                if (location != null) {
                    val distance = calculateDistance(
                        originLat, originLon,
                        location.latitude, location.longitude
                    )

                    // Only include drivers within the search radius
                    if (distance <= MAX_SEARCH_DISTANCE) {
                        driverIds.add(driverId)
                        Log.d(TAG, "Added driver $driverId at distance ${String.format("%.2f", distance)} km")
                    }
                } else {
                    // If no location, still include the driver as they may be newly available
                    driverIds.add(driverId)
                    Log.d(TAG, "Added driver $driverId (no location data)")}
            }

            return driverIds

        } catch (e: Exception) {
            Log.e(TAG, "Error finding nearby drivers: ${e.message}")
            return emptyList()
        }
    }


    private suspend fun findAnyAvailableDrivers(originLat: Double, originLon: Double): List<String> {
        try {
            // Query for all available drivers
            val driversQuery = firestore.collection("users")
                .whereEqualTo("userType", "driver")
                .whereEqualTo("available", true)
                .get()
                .await()

            Log.d(TAG, "Found ${driversQuery.size()} available drivers (any truck type)")

            // List to hold driver IDs - no distance filtering to ensure we get some drivers
            return driversQuery.documents.map { it.id }

        } catch (e: Exception) {
            Log.e(TAG, "Error finding any available drivers: ${e.message}")
            return emptyList()
        }
    }


    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth's radius in km
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
    }
}