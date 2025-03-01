package com.example.freightapp

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

class SignInActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        // Enable Firebase Auth persistence


        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        val etEmail = findViewById<EditText>(R.id.etSignInEmail)
        val etPassword = findViewById<EditText>(R.id.etSignInPassword)
        val btnSignIn = findViewById<Button>(R.id.btnSignIn)
        val tvSignUpLink = findViewById<TextView>(R.id.tvSignUpLink)

        btnSignIn.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                signInUser(email, password)
            } else {
                Toast.makeText(this, "Please enter Email and Password", Toast.LENGTH_SHORT).show()
            }
        }

        tvSignUpLink.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
    }

    private fun signInUser(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.let { checkUserProfile(it) }
                } else {
                    Toast.makeText(this, "Sign In Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun checkUserProfile(user: FirebaseUser) {
        firestore.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    // User profile exists, go to MainActivity
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                } else {
                    // No user profile, redirect to SignUpActivity
                    val intent = Intent(this, SignUpActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
                finish()
            }
            .addOnFailureListener {
                // Error checking profile, show error
                Toast.makeText(this, "Error accessing profile", Toast.LENGTH_SHORT).show()
            }
    }
}