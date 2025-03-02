package com.example.freightapp.model

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.GeoPoint

data class Order(
    var id: String = "",
    val uid: String = "",            // Customer ID
    var driverUid: String? = null,   // Driver ID (null means no driver assigned yet)
    var originCity: String = "",
    var destinationCity: String = "",
    var originLat: Double = 0.0,
    var originLon: Double = 0.0,
    var destinationLat: Double = 0.0,
    var destinationLon: Double = 0.0,
    var totalPrice: Double = 0.0,
    var truckType: String = "",
    var volume: Double = 0.0,
    var weight: Double = 0.0,
    var status: String = "Pending",   // Pending, Looking For Driver, Accepted, In Progress, Picked Up, Delivered, Completed, Cancelled
    val timestamp: Long = System.currentTimeMillis(),
    var driversContactList: MutableMap<String, String> = mutableMapOf(),  // Map of driver IDs to contact status
    var lastDriverNotificationTime: Long = 0L,
    var currentDriverIndex: Int = 0,
    var acceptedAt: Long = 0L,
    var pickedUpAt: Long = 0L,
    var deliveredAt: Long = 0L,
    var cancelledAt: Long = 0L,
    var cancelledBy: String = "",
    var driverLocation: GeoPoint? = null,
    var driverHeading: Float = 0f,
    var driverSpeed: Double = 0.0,
    var driverLastUpdate: Long = 0L,
    var distanceToPickup: Double = 0.0,
    var distanceToDelivery: Double = 0.0,
    var driverCanPickup: Boolean = false,
    var driverCanDeliver: Boolean = false
)