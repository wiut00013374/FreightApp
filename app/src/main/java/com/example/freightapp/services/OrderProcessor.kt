package com.example.freightapp.services

import android.util.Log
import com.example.freightapp.Order
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class OrderProcessor {
    private val TAG = "OrderProcessor"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val orderMatcher = OrderMatcher()

    suspend fun createOrder(order: Order): Pair<Boolean, String?> {
        return try {
            validateOrder(order)

            val orderRef = firestore.collection("orders").document()

            val processedOrder = order.copy(
                id = orderRef.id,
                uid = auth.currentUser?.uid ?: throw IllegalStateException("User not authenticated"),
                status = "Looking For Driver",
                timestamp = System.currentTimeMillis()
            )

            orderRef.set(processedOrder).await()

            Log.d(TAG, "Order created with ID: ${processedOrder.id}")

            val driverAssigned = assignDriverToOrder(processedOrder)

            Pair(true, processedOrder.id)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating order: ${e.message}")
            Pair(false, null)
        }
    }

    private suspend fun assignDriverToOrder(order: Order): Boolean {
        return orderMatcher.matchOrderToDrivers(order)
    }

    private fun validateOrder(order: Order) {
        require(order.originCity.isNotBlank()) { "Origin city is required" }
        require(order.destinationCity.isNotBlank()) { "Destination city is required" }
        require(order.truckType.isNotBlank()) { "Truck type is required" }
        require(order.totalPrice > 0) { "Total price must be positive" }
        require(order.originLat != 0.0 && order.originLon != 0.0) { "Valid origin coordinates are required" }
        require(order.destinationLat != 0.0 && order.destinationLon != 0.0) { "Valid destination coordinates are required" }
    }
}