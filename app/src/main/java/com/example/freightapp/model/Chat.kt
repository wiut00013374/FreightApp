package com.example.freightapp.model

data class Chat(
    var id: String = "",
    val orderId: String = "",
    val driverUid: String = "",
    var driverName: String = "",
    val customerUid: String = "",
    var lastMessage: String? = null,
    var timestamp: Long = 0L,
    var unreadCount: Int = 0,
    var driverProfileImage: String? = null,
    var customerProfileImage: String? = null
)