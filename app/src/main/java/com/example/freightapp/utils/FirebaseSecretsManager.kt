package com.example.freightapp.utils

import android.content.Context
import android.util.Log
import com.google.firebase.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader

object FirebaseSecretsManager {
    private const val TAG = "FirebaseSecretsManager"
    private const val FCM_SECRETS_FILE = "secrets/fcm_server_key.json"

    fun getFCMServerKey(context: Context): String {
        return try {
            val inputStream = context.assets.open(FCM_SECRETS_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val serverKey = reader.readLine().trim()

            // Optional: Add build config check for debug/release
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "FCM Server Key loaded")
            }

            serverKey
        } catch (e: Exception) {
            Log.e(TAG, "Error reading FCM server key: ${e.message}")
            throw IllegalStateException("FCM server key not found")
        }
    }
}