package com.example.freightapp.model

data class User(
    val uid: String = "",                      // Firebase Auth UID (document ID)
    val email: String = "",                    // User email address
    val displayName: String = "",              // User's display name
    val phoneNumber: String = "",              // Contact phone number
    val userType: String = "customer",         // Either "customer" or "driver"
    val createdAt: Long = System.currentTimeMillis(), // Account creation timestamp
    val fcmToken: String = "",                 // Firebase Cloud Messaging token for notifications

    // Driver-specific fields (only used when userType = "driver")
    val available: Boolean = false,            // Driver availability status
    val truckType: String = "",                // Type of truck (Small, Medium, Large, etc.)
    val licensePlate: String = "",             // Vehicle license plate
    val location: GeoLocation? = null,         // Current driver location
    val heading: Float = 0f,                   // Direction (0-359 degrees)
    val speed: Float = 0f,                     // Speed in km/h
    val lastLocationUpdate: Long = 0,          // When location was last updated
    val lastStatusUpdate: Long = 0,            // When availability status was last updated
    val lastOnline: Long = 0,                  // When driver was last online
    val rating: Float = 0f,                    // Driver rating (0-5 stars)
    val completedOrders: Int = 0               // Number of completed orders
)

/**
 * GeoLocation data object for storing location coordinates
 */
data class GeoLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)