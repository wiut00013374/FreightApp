package com.example.freightapp

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.freightapp.utils.PermissionManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is authenticated
        val currentUser = auth.currentUser
        if (currentUser == null) {
            // No user signed in, redirect to SignInActivity
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
            return
        }

        // User is signed in, check their profile and continue
        checkUserProfile(currentUser.uid)

        // User is signed in, continue with MainActivity setup
        setContentView(R.layout.activity_main)
    }

    private fun checkUserProfile(uid: String) {
        FirebaseFirestore.getInstance().collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    // User profile exists, set content view and continue
                    setContentView(R.layout.activity_main)
                    setupBottomNavigation()
                } else {
                    // No user profile, redirect to SignUpActivity
                    startActivity(Intent(this, SignUpActivity::class.java))
                    finish()
                }
            }
            .addOnFailureListener {
                // Error checking profile, redirect to SignIn
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SignInActivity::class.java))
                finish()
            }
    }

    private fun setupBottomNavigation() {
        val bottomNavigation: BottomNavigationView = findViewById(R.id.bottom_navigation)

        // Determine which fragment to load on startup
        val selectedTab = intent.getStringExtra("selectedTab")
        if (selectedTab == "orders") {
            loadFragment(OrdersFragment())
            bottomNavigation.selectedItemId = R.id.navigation_orders
        } else {
            loadFragment(HomeFragment())
            bottomNavigation.selectedItemId = R.id.navigation_home
        }

        bottomNavigation.setOnNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.navigation_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.navigation_orders -> {
                    loadFragment(OrdersFragment())
                    true
                }
                R.id.navigation_chats -> {
                    loadFragment(ChatsFragment())
                    true
                }
                R.id.navigation_profile -> {
                    loadFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }
}