package com.example.freightapp.model

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val userType: String = "" // "driver" or "customer"
)
