package com.example.freightapp.utils

import com.example.freightapp.model.UserProfile
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Utility class for Firestore operations
 */
object FirestoreUtils {
    private val db = FirebaseFirestore.getInstance()

    /**
     * Creates a user profile in Firestore
     */
    fun createUserProfile(
        userProfile: UserProfile,
        userData: Map<String, Any> = emptyMap(),
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Merge the UserProfile with any additional data
        val data = mutableMapOf<String, Any>()
        data["uid"] = userProfile.uid
        data["email"] = userProfile.email
        data["userType"] = userProfile.userType

        // Add any additional fields from userData
        data.putAll(userData)

        db.collection("users")
            .document(userProfile.uid)
            .set(data)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener {
                onFailure(it)
            }
    }

    /**
     * Gets a user profile from Firestore
     */
    fun getUserProfile(
        uid: String,
        onSuccess: (UserProfile?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        db.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val userProfile = document.toObject(UserProfile::class.java)
                    onSuccess(userProfile)
                } else {
                    onSuccess(null)
                }
            }
            .addOnFailureListener {
                onFailure(it)
            }
    }

    /**
     * Updates specific fields in a user profile
     */
    fun updateUserProfile(
        uid: String,
        updates: Map<String, Any>,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        db.collection("users")
            .document(uid)
            .update(updates)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener {
                onFailure(it)
            }
    }
}