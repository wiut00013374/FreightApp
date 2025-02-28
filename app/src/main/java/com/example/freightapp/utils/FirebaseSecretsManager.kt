package com.example.freightapp.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object FirebaseSecretsManager {
    private const val TAG = "FirebaseSecretsManager"
    private const val SERVICE_ACCOUNT_FILE = "secrets/firebase-service-account.json"

    fun getFirebaseServerKey(context: Context): String {
        return try {
            val inputStream = context.assets.open(SERVICE_ACCOUNT_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.use { it.readText() }
            val jsonObject = JSONObject(jsonString)

            // Extract the server key from the service account JSON
            jsonObject.getString("private_key")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading service account key: ${e.message}")
            throw IllegalStateException("Firebase service account key not found or invalid")
        }
    }

    fun getServiceAccountCredentials(context: Context): String {
        return try {
            val inputStream = context.assets.open(SERVICE_ACCOUNT_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading service account file: ${e.message}")
            throw IllegalStateException("Firebase service account file not found")
        }
    }
}