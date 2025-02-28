package com.example.freightapp.services

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.tasks.await

class FirebaseV1ApiService {
    companion object {
        private const val TAG = "FirebaseV1ApiService"
        private val firestore = FirebaseFirestore.getInstance()

        suspend fun sendV1Notification(
            token: String,
            title: String,
            body: String,
            data: Map<String, String>? = null
        ) {
            try {
                val messageBuilder = RemoteMessage.Builder(token)
                    .setMessageId(System.currentTimeMillis().toString())
                    .addData("title", title)
                    .addData("body", body)

                // Add additional data if provided
                data?.forEach { (key, value) ->
                    messageBuilder.addData(key, value)
                }

                FirebaseMessaging.getInstance().send(messageBuilder.build())
                Log.d(TAG, "Notification sent successfully to $token")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending notification: ${e.message}")
            }
        }

        suspend fun sendMulticastNotification(
            tokens: List<String>,
            title: String,
            body: String,
            data: Map<String, String>? = null
        ) {
            tokens.forEach { token ->
                sendV1Notification(token, title, body, data)
            }
        }

        suspend fun getFirebaseToken(): String? {
            return try {
                FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting Firebase token: ${e.message}")
                null
            }
        }

        suspend fun updateUserToken(token: String) {
            val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
            if (currentUserId != null) {
                try {
                    firestore.collection("users")
                        .document(currentUserId)
                        .update("fcmToken", token)
                        .await()
                    Log.d(TAG, "FCM token saved to Firestore")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving FCM token: ${e.message}")
                }
            }
        }
    }
}