package com.example.freightapp

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class SignInActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        val etEmail = findViewById<EditText>(R.id.etSignInEmail)
        val etPassword = findViewById<EditText>(R.id.etSignInPassword)
        val btnSignIn = findViewById<Button>(R.id.btnSignIn)
        val tvSignUpLink = findViewById<TextView>(R.id.tvSignUpLink)

        // Sign In button
        btnSignIn.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            // Navigate to MainActivity instead of HomeFragment directly.
                            val intent = Intent(this, MainActivity::class.java)
                            startActivity(intent)
                            finish()
                        } else {
                            Toast.makeText(this, "Sign In Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(this, "Please enter Email and Password", Toast.LENGTH_SHORT).show()
            }
        }

        // Sign Up link
        tvSignUpLink.setOnClickListener {
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
        }
    }
}
