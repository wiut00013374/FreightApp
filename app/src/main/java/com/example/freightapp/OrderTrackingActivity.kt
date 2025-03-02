package com.example.freightapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.freightapp.model.Order
import com.example.freightapp.repos.ChatRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.*

class OrderTrackingActivity : AppCompatActivity() {

    private val TAG = "OrderTrackingActivity"
    private lateinit var mapView: MapView
    private lateinit var tvOrderStatus: TextView
    private lateinit var tvDriverInfo: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvOrigin: TextView
    private lateinit var tvDestination: TextView
    private lateinit var tvDistance: TextView
    private lateinit var btnContactDriver: Button
    private lateinit var btnBack: Button

    private var order: Order? = null
    private var orderId: String? = null
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Map overlays
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var driverMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // Order listener for real-time updates
    private var orderListener: ListenerRegistration? = null

    // Status flags
    private var isPickedUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_tracking)

        // Initialize OSMDroid
        Configuration.getInstance().userAgentValue = packageName

        // Initialize views
        initializeViews()

        // Get the order ID from intent
        orderId = intent.getStringExtra("EXTRA_ORDER_ID")
        if (orderId == null) {
            Toast.makeText(this, "No order ID provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Fetch order details and start real-time tracking
        fetchOrderDetails(orderId!!)

        // Button click listeners
        setupClickListeners()
    }

    private fun initializeViews() {
        mapView = findViewById(R.id.mapOrderTracking)
        tvOrderStatus = findViewById(R.id.tvTrackingOrderStatus)
        tvDriverInfo = findViewById(R.id.tvTrackingDriverInfo)
        tvEta = findViewById(R.id.tvTrackingEta)
        tvOrigin = findViewById(R.id.tvTrackingOrigin)
        tvDestination = findViewById(R.id.tvTrackingDestination)
        tvDistance = findViewById(R.id.tvTrackingDistance)
        btnContactDriver = findViewById(R.id.btnTrackingContactDriver)
        btnBack = findViewById(R.id.btnTrackingBack)

        // Initialize the map
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)
    }

    private fun setupClickListeners() {
        btnContactDriver.setOnClickListener {
            contactDriver()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun fetchOrderDetails(orderId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val orderDoc = firestore.collection("orders").document(orderId).get().await()
                val fetchedOrder = orderDoc.toObject(Order::class.java)?.apply {
                    id = orderDoc.id
                }

                if (fetchedOrder != null) {
                    order = fetchedOrder

                    CoroutineScope(Dispatchers.Main).launch {
                        // Setup initial UI with order information
                        setupOrderDetails(fetchedOrder)

                        // Set up map with route
                        setupMap(fetchedOrder)

                        // Start listening for real-time updates
                        startOrderTracking(orderId)
                    }
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(this@OrderTrackingActivity, "Order not found", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching order: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(this@OrderTrackingActivity, "Error loading order: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }