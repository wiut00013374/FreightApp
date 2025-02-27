package com.example.freightapp

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.*
import com.example.freightapp.model.UserProfile
import com.example.freightapp.utils.FirestoreUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest

class SignUpActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var etDisplayName: EditText
    private lateinit var radioCustomer: RadioButton
    private lateinit var radioDriver: RadioButton
    private lateinit var btnSignUp: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_up)

        auth = FirebaseAuth.getInstance()

        // Initialize views
        etEmail = findViewById(R.id.etSignUpEmail)
        etPassword = findViewById(R.id.etSignUpPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPasswordSignUp)
        etDisplayName = findViewById(R.id.etDisplayName)
        radioCustomer = findViewById(R.id.radioCustomer)
        radioDriver = findViewById(R.id.radioDriver)
        btnSignUp = findViewById(R.id.btnSignUp)
        progressBar = findViewById(R.id.progressBar)

        // Set customer as default role
        radioCustomer.isChecked = true

        btnSignUp.setOnClickListener {
            registerUser()
        }
    }

    private fun registerUser() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()
        val confirmPassword = etConfirmPassword.text.toString().trim()
        val displayName = etDisplayName.text.toString().trim()
        val userType = if (radioCustomer.isChecked) "customer" else "driver"

        // Validate inputs
        if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty() || displayName.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        if (password != confirmPassword) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.length < 6) {
            Toast.makeText(this, "Password should be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }

        // Show progress
        progressBar.visibility = ProgressBar.VISIBLE
        btnSignUp.isEnabled = false

        // Create user in Firebase Auth
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Set display name in Firebase Auth
                    val user = auth.currentUser
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(displayName)
                        .build()

                    user?.updateProfile(profileUpdates)
                        ?.addOnCompleteListener { profileTask ->
                            if (profileTask.isSuccessful) {
                                // Save user data to Firestore
                                val userProfile = UserProfile(
                                    uid = user.uid,
                                    email = email,
                                    userType = userType
                                )

                                // Add display name to the profile data for Firestore
                                val userData = hashMapOf(
                                    "uid" to user.uid,
                                    "email" to email,
                                    "displayName" to displayName,
                                    "userType" to userType,
                                    "createdAt" to System.currentTimeMillis()
                                )

                                // Save to Firestore
                                FirestoreUtils.createUserProfile(
                                    userProfile = userProfile,
                                    userData = userData,
                                    onSuccess = {
                                        progressBar.visibility = ProgressBar.GONE
                                        // Navigate to MainActivity
                                        val intent = Intent(this, MainActivity::class.java)
                                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        startActivity(intent)
                                        finish()
                                    },
                                    onFailure = { e ->
                                        progressBar.visibility = ProgressBar.GONE
                                        btnSignUp.isEnabled = true
                                        Toast.makeText(this, "Error creating profile: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } else {
                                progressBar.visibility = ProgressBar.GONE
                                btnSignUp.isEnabled = true
                                Toast.makeText(this, "Error setting display name", Toast.LENGTH_SHORT).show()
                            }
                        }
                } else {
                    progressBar.visibility = ProgressBar.GONE
                    btnSignUp.isEnabled = true
                    Toast.makeText(this, "Sign Up Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }
}