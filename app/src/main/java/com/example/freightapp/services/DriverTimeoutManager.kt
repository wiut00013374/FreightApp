package com.example.freightapp.services

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks.await
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager that handles scheduling and checking driver response timeouts
 */
object DriverTimeoutManager {
    private const val TAG = "DriverTimeoutManager"
    private val firestore = FirebaseFirestore.getInstance()
    private val handler = Handler(Looper.getMainLooper())

    // Track active timeouts by orderId
    private val activeTimeouts = ConcurrentHashMap<String, Runnable>()

    /**
     * Schedule a timeout check for a driver's response to an order notification
     */
    fun scheduleDriverResponseTimeout(orderId: String, timeoutMs: Long) {
        // Cancel any existing timeout for this order
        cancelTimeout(orderId)

        // Create a new timeout runnable
        val timeoutRunnable = Runnable {
            CoroutineScope(Dispatchers.IO).launch {
                checkResponseTimeout(orderId)
                // Remove from active timeouts after checking
                activeTimeouts.remove(orderId)
            }
        }

        // Store and schedule the timeout
        activeTimeouts[orderId] = timeoutRunnable
        handler.postDelayed(timeoutRunnable, timeoutMs)

        Log.d(TAG, "Scheduled response timeout for order $orderId in ${timeoutMs/1000} seconds")
    }

    /**
     * Cancel a scheduled timeout
     */
    fun cancelTimeout(orderId: String) {
        activeTimeouts[orderId]?.let { runnable ->
            handler.removeCallbacks(runnable)
            activeTimeouts.remove(orderId)
            Log.d(TAG, "Cancelled timeout for order $orderId")
        }
    }

    /**
     * Check if the driver has responded to the order notification
     * If not, move to the next driver
     */
    private suspend fun checkResponseTimeout(orderId: String) {
        try {
            Log.d(TAG, "Checking response timeout for order $orderId")

            // Get the order data
            val orderDoc = firestore.collection("orders").document(orderId).get().await()

            // Skip if order doesn't exist
            if (!orderDoc.exists()) {
                Log.d(TAG, "Order $orderId no longer exists")
                return
            }

            // Skip if order already has a driver
            val driverUid = orderDoc.getString("driverUid")
            if (!driverUid.isNullOrEmpty()) {
                Log.d(TAG, "Order $orderId already has a driver assigned: $driverUid")
                return
            }

            // Get driver contact info
            val driversContactList = orderDoc.get("driversContactList") as? Map<String, String>
            val currentIndex = orderDoc.getLong("currentDriverIndex") ?: 0
            val lastNotificationTime = orderDoc.getLong("lastDriverNotificationTime") ?: 0L

            // Check if we should move to the next driver
            val currentTime = System.currentTimeMillis()

            if (driversContactList != null &&
                currentIndex < driversContactList.size &&
                currentTime - lastNotificationTime >= 55000) { // slightly less than our timeout to account for processing time

                Log.d(TAG, "Driver didn't respond in time for order $orderId, moving to next driver")

                // Move to the next driver
                await(firestore.collection("orders").document(orderId)
                    .update("currentDriverIndex", currentIndex + 1))

                // Notify the next driver
                val driverFinder = DriverFinder()
                driverFinder.notifyNextDriverForOrder(orderId)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking response timeout: ${e.message}")
        }
    }
}