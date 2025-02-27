package com.example.freightapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.freightapp.model.UserProfile
import com.example.freightapp.utils.FirestoreUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore

/**
 * ProfileFragment handles displaying and editing the user's profile information,
 * as well as providing options to sign out.
 */
class ProfileFragment : Fragment() {

    private lateinit var tvEmail: TextView
    private lateinit var etDisplayName: EditText
    private lateinit var etPhoneNumber: EditText
    private lateinit var btnSaveProfile: Button
    private lateinit var btnSignOut: Button

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        tvEmail = view.findViewById(R.id.tvProfileEmail)
        etDisplayName = view.findViewById(R.id.etDisplayName)
        etPhoneNumber = view.findViewById(R.id.etPhoneNumber)
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile)
        btnSignOut = view.findViewById(R.id.btnSignOut)

        // Load current user data
        loadUserProfile()

        // Set up click listeners
        btnSaveProfile.setOnClickListener {
            saveUserProfile()
        }

        btnSignOut.setOnClickListener {
            signOut()
        }
    }

    private fun loadUserProfile() {
        val currentUser = auth.currentUser

        if (currentUser != null) {
            // Display email
            tvEmail.text = currentUser.email ?: "No email available"

            // Load additional profile information from Firestore
            firestore.collection("users")
                .document(currentUser.uid)
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        // Display name
                        val displayName = document.getString("displayName") ?: currentUser.displayName ?: ""
                        etDisplayName.setText(displayName)

                        // Phone number
                        val phoneNumber = document.getString("phoneNumber") ?: ""
                        etPhoneNumber.setText(phoneNumber)
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Error loading profile: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun saveUserProfile() {
        val currentUser = auth.currentUser

        if (currentUser != null) {
            val displayName = etDisplayName.text.toString().trim()
            val phoneNumber = etPhoneNumber.text.toString().trim()

            // Update Firebase Auth display name
            val profileUpdates = UserProfileChangeRequest.Builder()
                .setDisplayName(displayName)
                .build()

            currentUser.updateProfile(profileUpdates)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        // Now update Firestore with additional information
                        val userUpdates = hashMapOf<String, Any>(
                            "displayName" to displayName,
                            "phoneNumber" to phoneNumber
                        )

                        firestore.collection("users")
                            .document(currentUser.uid)
                            .update(userUpdates)
                            .addOnSuccessListener {
                                Toast.makeText(requireContext(), "Profile updated successfully", Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { e ->
                                // If update fails, try to set the document (it might not exist yet)
                                firestore.collection("users")
                                    .document(currentUser.uid)
                                    .set(userUpdates)
                                    .addOnSuccessListener {
                                        Toast.makeText(requireContext(), "Profile created successfully", Toast.LENGTH_SHORT).show()
                                    }
                                    .addOnFailureListener { e2 ->
                                        Toast.makeText(requireContext(), "Error saving profile: ${e2.message}", Toast.LENGTH_SHORT).show()
                                    }
                            }
                    } else {
                        Toast.makeText(requireContext(), "Error updating profile", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun signOut() {
        auth.signOut()

        // Navigate to SignInActivity
        val intent = Intent(requireContext(), SignInActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        requireActivity().finish()
    }
}