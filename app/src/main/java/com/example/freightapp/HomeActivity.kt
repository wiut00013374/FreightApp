package com.example.freightapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * HomeActivity serves as an alternative entry point to the app.
 * It provides the same bottom navigation functionality as MainActivity
 * but can be used in specific navigation flows or deep links.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var bottomNavigation: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Initialize bottom navigation
        bottomNavigation = findViewById(R.id.bottom_navigation)

        // Start with HomeFragment by default
        loadFragment(HomeFragment())
        bottomNavigation.selectedItemId = R.id.navigation_home

        // Handle navigation item selection
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
                    loadFragment(ChatsFragment.newInstance())
                    true
                }
                R.id.navigation_profile -> {
                    loadFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }

        // Check if we need to navigate to a specific fragment
        handleNavigationIntent()
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }

    /**
     * Handle different navigation intents.
     * This can be used to navigate to specific screens based on
     * notifications, deep links, etc.
     */
    private fun handleNavigationIntent() {
        // Extract navigation parameters from intent
        val navigateTo = intent.getStringExtra("navigate_to")

        when (navigateTo) {
            "orders" -> {
                loadFragment(OrdersFragment())
                bottomNavigation.selectedItemId = R.id.navigation_orders
            }
            "chat" -> {
                // Check if we have a specific chat ID to navigate to
                val chatId = intent.getStringExtra("chat_id")
                if (chatId != null) {
                    loadFragment(ChatsFragment.newInstance(chatId))
                } else {
                    loadFragment(ChatsFragment.newInstance())
                }
                bottomNavigation.selectedItemId = R.id.navigation_chats
            }
            "profile" -> {
                loadFragment(ProfileFragment())
                bottomNavigation.selectedItemId = R.id.navigation_profile
            }
        }
    }

    /**
     * Handle back button presses - if inside a child fragment,
     * return to the main fragment of the current section before exiting
     */
    override fun onBackPressed() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)

        // If we're in a nested fragment, pop back to the parent
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }
}