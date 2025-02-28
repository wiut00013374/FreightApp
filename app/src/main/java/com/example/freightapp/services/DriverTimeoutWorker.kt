package com.example.freightapp.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class DriverTimeoutWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val firestore = FirebaseFirestore.getInstance()
    private val orderMatcher = OrderMatcher(context)

    companion object {
        private const val DRIVER_RESPONSE_TIMEOUT_MS = 60000L // 60 seconds
    }

    override suspend fun doWork(): Result {
        val orderId = inputData.getString("orderId") ?: return Result.failure()

        try {
            val orderDoc = firestore.collection("orders").document(orderId).get().await()

            if (!orderDoc.exists()) {
                return Result.success()
            }

            val driverUid = orderDoc.getString("driverUid")
            if (driverUid != null) {
                // Order already has a driver assigned, nothing to do
                return Result.success()
            }

            val lastNotificationTime = orderDoc.getLong("lastDriverNotificationTime") ?: 0L
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastNotificationTime >= DRIVER_RESPONSE_TIMEOUT_MS) {
                val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0

                // Update the driver index to move to the next driver
                firestore.collection("orders").document(orderId)
                    .update(mapOf(
                        "currentDriverIndex" to currentIndex + 1
                    ))
                    .await()

                // Notify the next driver in the queue
                orderMatcher.notifyNextDriver(orderId)
            }

            return Result.success()
        } catch (e: Exception) {
            return Result.failure()
        }
    }
}