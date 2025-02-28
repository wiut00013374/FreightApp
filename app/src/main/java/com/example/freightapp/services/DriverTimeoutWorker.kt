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

    override suspend fun doWork(): Result {
        val orderId = inputData.getString("orderId") ?: return Result.failure()

        try {
            val orderDoc = firestore.collection("orders").document(orderId).get().await()

            if (!orderDoc.exists()) {
                return Result.success()
            }

            val driverUid = orderDoc.getString("driverUid")
            if (driverUid != null) {
                return Result.success()
            }

            val lastNotificationTime = orderDoc.getLong("lastDriverNotificationTime") ?: 0L
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastNotificationTime >= OrderMatcher.DRIVER_RESPONSE_TIMEOUT_SECONDS * 1000) {
                val currentIndex = orderDoc.getLong("currentDriverIndex")?.toInt() ?: 0
                firestore.collection("orders").document(orderId)
                    .update("currentDriverIndex" to currentIndex + 1)
                    .await()
                OrderMatcher().notifyNextDriver(orderId)
            }

            return Result.success()
        } catch (e: Exception) {
            return Result.failure()
        }
    }
}