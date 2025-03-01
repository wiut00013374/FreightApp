package com.example.freightapp

import android.app.Application
import android.content.Context
import com.example.freightapp.utils.NotificationHandler
import com.example.freightapp.utils.PermissionManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import org.osmdroid.config.Configuration

/**
 * Application class for initializing app-wide components
 */
class FreightApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase
        FirebaseApp.initializeApp(this)

        // Initialize OSMDroid (for maps)
        Configuration.getInstance().userAgentValue = packageName

        // Create notification channels
        NotificationHandler.createNotificationChannels(this)



        // Initialize other app-wide components as needed
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // If you need to use MultiDex, initialize it here
    }
}