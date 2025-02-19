package com.example.freightapp

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class FreightDetailsActivity : AppCompatActivity() {

    private lateinit var etVolume: EditText
    private lateinit var etWeight: EditText
    private lateinit var spinnerTruckType: Spinner
    private lateinit var btnSubmitFreight: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_freight_details)

        // Reference UI components
        etVolume = findViewById(R.id.etVolume)
        etWeight = findViewById(R.id.etWeight)
        spinnerTruckType = findViewById(R.id.spinnerTruckType)
        btnSubmitFreight = findViewById(R.id.btnSubmitFreight)

        // Set up spinner options
        val truckTypes = listOf("Select Truck Type", "Small", "Medium", "Large", "Refrigerated", "Flatbed")
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            truckTypes
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTruckType.adapter = adapter

        // Retrieve route data passed from HomeActivity
        val originLat = intent.getDoubleExtra("originLat", 0.0)
        val originLon = intent.getDoubleExtra("originLon", 0.0)
        val destinationLat = intent.getDoubleExtra("destinationLat", 0.0)
        val destinationLon = intent.getDoubleExtra("destinationLon", 0.0)
        val originAddress = intent.getStringExtra("originAddress") ?: "Origin not set"
        val destinationAddress = intent.getStringExtra("destinationAddress") ?: "Destination not set"
        val totalDistance = intent.getDoubleExtra("totalDistance", 0.0)

        // Optionally, you can display these details on the UI (for example, in TextViews) so the user can review.
        // For simplicity, we skip that step here.

        btnSubmitFreight.setOnClickListener {
            val volumeStr = etVolume.text.toString().trim()
            val weightStr = etWeight.text.toString().trim()
            val truckType = spinnerTruckType.selectedItem.toString()

            if (volumeStr.isEmpty() || weightStr.isEmpty() || truckType == "Select Truck Type") {
                Toast.makeText(this, "Please complete all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val volume = volumeStr.toDoubleOrNull()
            val weight = weightStr.toDoubleOrNull()

            if (volume == null || weight == null) {
                Toast.makeText(this, "Invalid volume or weight", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Calculate a total price (example formula)
            val baseFare = 50.0
            val costPerKm = 1.2
            val costPerKg = 0.05
            val totalPrice = baseFare + (totalDistance / 1000 * costPerKm) + (weight * costPerKg)

            // Now create an intent to launch OrderSummaryActivity with both route and freight details
            val summaryIntent = Intent(this, OrderSummaryActivity::class.java).apply {
                putExtra("originAddress", originAddress)
                putExtra("destinationAddress", destinationAddress)
                putExtra("originLat", originLat)
                putExtra("originLon", originLon)
                putExtra("destinationLat", destinationLat)
                putExtra("destinationLon", destinationLon)
                putExtra("totalDistance", totalDistance)
                putExtra("truckType", truckType)
                putExtra("volume", volume)
                putExtra("weight", weight)
                putExtra("totalPrice", totalPrice)
            }
            startActivity(summaryIntent)
            finish()
        }
    }
}
