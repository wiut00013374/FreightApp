package com.example.freightapp.model

data class Order(
    var id: String = "",
    val uid: String = "",
    var driverUid: String? = null,
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
    var status: String = "Pending",
    val timestamp: Long = System.currentTimeMillis(),
    val driversContactList: MutableMap<String, String> = mutableMapOf(),
    val lastDriverNotificationTime: Long = 0L,
    val currentDriverIndex: Int = 0,
    val acceptedAt: Long = 0L
)