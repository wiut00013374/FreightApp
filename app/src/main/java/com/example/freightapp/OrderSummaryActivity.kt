package com.example.freightapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class OrderSummaryActivity : AppCompatActivity() {

    private lateinit var tvOriginAddress: TextView
    private lateinit var tvDestinationAddress: TextView
    private lateinit var tvTotalDistance: TextView
    private lateinit var tvTruckType: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvWeight: TextView
    private lateinit var tvTotalPrice: TextView
    private lateinit var btnConfirmOrder: Button

    companion object {
        private const val TAG = "OrderSummaryActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_summary)

        // Reference UI elements
        tvOriginAddress = findViewById(R.id.tvOriginAddress)
        tvDestinationAddress = findViewById(R.id.tvDestinationAddress)
        tvTotalDistance = findViewById(R.id.tvTotalDistance)
        tvTruckType = findViewById(R.id.tvTruckType)
        tvVolume = findViewById(R.id.tvVolume)
        tvWeight = findViewById(R.id.tvWeight)
        tvTotalPrice = findViewById(R.id.tvTotalPrice)
        btnConfirmOrder = findViewById(R.id.btnConfirmOrder)

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
            confirmOrder(
                originAddress,
                destinationAddress,
                originLat,
                originLon,
                destinationLat,
                destinationLon,
                truckType,
                volume,
                weight,
                totalPrice
            )
        }
    }

    // Simple method to extract city from address (modify as needed)
    private fun extractCity(address: String): String {
        return address.split(",").firstOrNull() ?: address
    }

    private fun confirmOrder(
        originAddress: String,
        destinationAddress: String,
        originLat: Double,
        originLon: Double,
        destinationLat: Double,
        destinationLon: Double,
        truckType: String,
        volume: Double,
        weight: Double,
        totalPrice: Double
    ) {
        // Get the current user’s UID (ensure the user is authenticated)
        val currentUserUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val order = Order(
            uid = currentUserUid,  // Customer’s UID
            originCity = extractCity(originAddress),
            destinationCity = extractCity(destinationAddress),
            originLat = originLat,
            originLon = originLon,
            destinationLat = destinationLat,
            destinationLon = destinationLon,
            totalPrice = totalPrice,
            truckType = truckType,
            volume = volume,
            weight = weight,
            status = "Pending"
        )

        OrderRepository.createOrder(order) { success, orderId ->
            if (success) {
                Toast.makeText(this, "Order confirmed!", Toast.LENGTH_LONG).show()
                Log.d(TAG, "Order saved with ID: $orderId")
                // Navigate to MainActivity, e.g., to the Orders tab.
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("selectedTab", "orders")
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Failed to save order. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }



}
