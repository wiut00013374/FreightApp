package com.example.freightapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.freightapp.services.OrderProcessor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class OrderSummaryActivity : AppCompatActivity() {
    private val orderProcessor by lazy { OrderProcessor(this) }

    private lateinit var progressBar: ProgressBar
    private lateinit var tvSearchStatus: TextView
    private lateinit var btnConfirmOrder: Button

    // Firestore listener for order status updates
    private var orderStatusListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_summary)

        // Reference UI elements
        val tvOriginAddress = findViewById<TextView>(R.id.tvOriginAddress)
        val tvDestinationAddress = findViewById<TextView>(R.id.tvDestinationAddress)
        val tvTotalDistance = findViewById<TextView>(R.id.tvTotalDistance)
        val tvTruckType = findViewById<TextView>(R.id.tvTruckType)
        val tvVolume = findViewById<TextView>(R.id.tvVolume)
        val tvWeight = findViewById<TextView>(R.id.tvWeight)
        val tvTotalPrice = findViewById<TextView>(R.id.tvTotalPrice)

        // Reference new UI elements for driver search
        progressBar = findViewById(R.id.progressBarDriverSearch)
        tvSearchStatus = findViewById(R.id.tvSearchStatus)
        btnConfirmOrder = findViewById(R.id.btnConfirmOrder)

        // Initially hide progress bar and status text
        progressBar.visibility = View.GONE
        tvSearchStatus.visibility = View.GONE

        // Retrieve extras passed to this activity
        val originAddress = intent.getStringExtra("originAddress") ?: "Not provided"
        val destinationAddress = intent.getStringExtra("destinationAddress") ?: "Not provided"
        val totalDistance = intent.getDoubleExtra("totalDistance", 0.0)
        val truckType = intent.getStringExtra("truckType") ?: "Not provided"
        val volume = intent.getDoubleExtra("volume", 0.0)
        val weight = intent.getDoubleExtra("weight", 0.0)
        val totalPrice = intent.getDoubleExtra("totalPrice", 0.0)
        val originLat = intent.getDoubleExtra("originLat", 0.0)
        val originLon = intent.getDoubleExtra("originLon", 0.0)
        val destinationLat = intent.getDoubleExtra("destinationLat", 0.0)
        val destinationLon = intent.getDoubleExtra("destinationLon", 0.0)

        // Populate UI elements
        tvOriginAddress.text = originAddress
        tvDestinationAddress.text = destinationAddress
        tvTotalDistance.text = String.format("%.2f km", totalDistance / 1000.0)
        tvTruckType.text = truckType
        tvVolume.text = "$volume m³"
        tvWeight.text = "$weight kg"
        tvTotalPrice.text = "$${String.format("%.2f", totalPrice)}"

        btnConfirmOrder.setOnClickListener {
            // Show searching UI
            showSearchingForDriverUI()

            // Use CoroutineScope for asynchronous order creation
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Create the order
                    val order = com.example.freightapp.model.Order(
                        originCity = extractCity(originAddress),
                        destinationCity = extractCity(destinationAddress),
                        originLat = originLat,
                        originLon = originLon,
                        destinationLat = destinationLat,
                        destinationLon = destinationLon,
                        totalPrice = totalPrice,
                        truckType = truckType,
                        volume = volume,
                        weight = weight
                    )

                    // Process the order
                    val (success, orderId) = orderProcessor.createOrder(order)

                    if (success) {
                        Log.d("OrderSummary", "Order created with ID: $orderId")

                        // Set up listener for order status changes
                        if (orderId != null) {
                            setupOrderStatusListener(orderId)
                        }

                        // Button is already hidden by showSearchingForDriverUI()
                    } else {
                        updateSearchStatus("Failed to find a driver. Please try again later.")
                        btnConfirmOrder.isEnabled = true
                        btnConfirmOrder.text = "Try Again"
                        progressBar.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Log.e("OrderSummary", "Error processing order: ${e.message}")
                    hideSearchingForDriverUI()
                    Toast.makeText(this@OrderSummaryActivity, "An error occurred: ${e.message}", Toast.LENGTH_LONG).show()
                    btnConfirmOrder.isEnabled = true
                }
            }
        }
    }

    private fun hideSearchingForDriverUI() {
        progressBar.visibility = View.GONE
        tvSearchStatus.visibility = View.GONE
        btnConfirmOrder.isEnabled = true
    }

    private fun showSearchingForDriverUI() {
        progressBar.visibility = View.VISIBLE
        tvSearchStatus.visibility = View.VISIBLE
        tvSearchStatus.text = "Searching for available drivers..."
        btnConfirmOrder.isEnabled = false
    }

    private fun updateSearchStatus(status: String) {
        tvSearchStatus.text = status
    }

    private fun setupOrderStatusListener(orderId: String) {
        orderStatusListener?.remove()

        orderStatusListener = FirebaseFirestore.getInstance()
            .collection("orders")
            .document(orderId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("OrderSummary", "Error listening for order updates: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status")
                    val driverUid = snapshot.getString("driverUid")

                    when (status) {
                        "Looking For Driver" -> {
                            updateSearchStatus("Looking for a driver...")
                        }
                        "Accepted" -> {
                            updateSearchStatus("Driver found! Preparing your order...")
                            progressBar.visibility = View.GONE

                            if (driverUid != null) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    getDriverDetails(driverUid)
                                }
                            }

                            CoroutineScope(Dispatchers.Main).launch {
                                delay(3000)
                                navigateToOrdersScreen()
                            }
                        }
                        "No Drivers Available" -> {
                            updateSearchStatus("No drivers available at this time. Please try again later.")
                            progressBar.visibility = View.GONE
                            btnConfirmOrder.isEnabled = true
                            btnConfirmOrder.text = "Try Again"
                        }
                        "Driver Search Failed" -> {
                            updateSearchStatus("Failed to find a driver. Please try again.")
                            progressBar.visibility = View.GONE
                            btnConfirmOrder.isEnabled = true
                            btnConfirmOrder.text = "Try Again"
                        }
                        else -> {
                            updateSearchStatus("Order status: $status")
                        }
                    }
                }
            }
    }

    private suspend fun getDriverDetails(driverId: String) {
        try {
            val driverDoc = withContext(Dispatchers.IO) {
                FirebaseFirestore.getInstance().collection("users")
                    .document(driverId)
                    .get()
                    .await()
            }

            if (driverDoc.exists()) {
                val driverName = driverDoc.getString("displayName") ?: "Your Driver"
                updateSearchStatus("$driverName has accepted your order!")
            }
        } catch (e: Exception) {
            Log.e("OrderSummary", "Error getting driver details: ${e.message}")
        }
    }

    private fun navigateToOrdersScreen() {
        // Navigate to MainActivity, showing Orders tab
        val intent = Intent(this@OrderSummaryActivity, MainActivity::class.java)
        intent.putExtra("selectedTab", "orders")
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    // Simple method to extract city from address (modify as needed)
    private fun extractCity(address: String): String {
        return address.split(",").firstOrNull() ?: address
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove order status listener
        orderStatusListener?.remove()
    }
}