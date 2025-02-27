package com.example.freightapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.freightapp.services.OrderProcessor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OrderSummaryActivity : AppCompatActivity() {
    private val orderProcessor = OrderProcessor()

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
        val btnConfirmOrder = findViewById<Button>(R.id.btnConfirmOrder)

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
            // Use CoroutineScope for asynchronous order creation
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Create the order
                    val order = Order(
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
                        Toast.makeText(this@OrderSummaryActivity, "Order confirmed!", Toast.LENGTH_LONG).show()
                        Log.d("OrderSummary", "Order saved with ID: $orderId")

                        // Navigate to MainActivity, showing Orders tab
                        val intent = Intent(this@OrderSummaryActivity, MainActivity::class.java)
                        intent.putExtra("selectedTab", "orders")
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@OrderSummaryActivity, "Failed to save order. Please try again.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e("OrderSummary", "Error processing order: ${e.message}")
                    Toast.makeText(this@OrderSummaryActivity, "An error occurred: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Simple method to extract city from address (modify as needed)
    private fun extractCity(address: String): String {
        return address.split(",").firstOrNull() ?: address
    }
}
