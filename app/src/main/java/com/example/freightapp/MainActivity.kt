package com.example.freightapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavigation: BottomNavigationView
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is authenticated
        if (auth.currentUser == null) {
            // User is not authenticated, navigate to SignInActivity and finish MainActivity
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
            return  // Prevent further execution
        }


        setContentView(R.layout.activity_main)
        bottomNavigation = findViewById(R.id.bottom_navigation)

        // Determine which fragment to load on startup. Optionally check for extras like "selectedTab".
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
