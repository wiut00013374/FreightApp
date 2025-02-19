package com.example.freightapp.utils

import com.example.freightapp.model.UserProfile
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

object FirestoreUtils {
    private val db = Firebase.firestore

    fun createUserProfile(
        userProfile: UserProfile,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        db.collection("users")
            .document(userProfile.uid)
            .set(userProfile)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener {
                onFailure(it)
            }
    }

    // You can add more utility functions here (e.g., getUserProfile, updateLocation, etc.)
}
