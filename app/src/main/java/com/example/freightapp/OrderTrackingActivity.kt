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
    }

    private fun setupOrderDetails(order: Order) {
        // Display basic order information
        tvOrigin.text = "Pickup: ${order.originCity}"
        tvDestination.text = "Delivery: ${order.destinationCity}"

        // Calculate and show the distance
        val distanceKm = calculateDistance(
            order.originLat, order.originLon,
            order.destinationLat, order.destinationLon
        )
        tvDistance.text = "Distance: ${String.format("%.1f", distanceKm)} km"

        // Set order status
        updateOrderStatusDisplay(order)

        // If the order has a driver, fetch and display driver info
        if (order.driverUid != null) {
            fetchDriverInfo(order.driverUid!!)
            btnContactDriver.visibility = View.VISIBLE
        } else {
            tvDriverInfo.text = "No driver assigned yet"
            btnContactDriver.visibility = View.GONE
        }
    }

    private fun updateOrderStatusDisplay(order: Order) {
        // Update status text and change color based on status
        when (order.status) {
            "Pending" -> {
                tvOrderStatus.text = "Status: Looking for a driver"
                tvOrderStatus.setTextColor(ContextCompat.getColor(this, R.color.purple_500))
                isPickedUp = false
            }
            "Accepted" -> {
                tvOrderStatus.text = "Status: Driver going to pickup location"
                tvOrderStatus.setTextColor(ContextCompat.getColor(this, R.color.teal_700))
                isPickedUp = false
            }
            "In Progress" -> {
                tvOrderStatus.text = "Status: Driver heading to pickup location"
                tvOrderStatus.setTextColor(ContextCompat.getColor(this, R.color.teal_700))
                isPickedUp = false
            }
            "Picked Up" -> {
                tvOrderStatus.text = "Status: Freight picked up, on the way to delivery"
                tvOrderStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                isPickedUp = true
            }
            "Delivered" -> {
                tvOrderStatus.text = "Status: Freight delivered successfully"
                tvOrderStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                isPickedUp = true
            }
            else -> {
                tvOrderStatus.text = "Status: ${order.status}"
                tvOrderStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                isPickedUp = false
            }
        }
    }

    private fun fetchDriverInfo(driverUid: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val driverDoc = firestore.collection("users").document(driverUid).get().await()

                val driverName = driverDoc.getString("displayName") ?: "Driver"
                val driverPhone = driverDoc.getString("phoneNumber") ?: "No phone"

                CoroutineScope(Dispatchers.Main).launch {
                    tvDriverInfo.text = "Driver: $driverName\nPhone: $driverPhone"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching driver info: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch {
                    tvDriverInfo.text = "Driver information unavailable"
                }
            }
        }
    }

    private fun setupMap(order: Order) {
        // Clear previous overlays
        mapView.overlays.clear()

        // Add origin marker
        val originPoint = GeoPoint(order.originLat, order.originLon)
        originMarker = Marker(mapView).apply {
            position = originPoint
            title = "Pickup: ${order.originCity}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@OrderTrackingActivity, R.drawable.ic_pickup)
        }
        mapView.overlays.add(originMarker)

        // Add destination marker
        val destPoint = GeoPoint(order.destinationLat, order.destinationLon)
        destinationMarker = Marker(mapView).apply {
            position = destPoint
            title = "Delivery: ${order.destinationCity}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@OrderTrackingActivity, R.drawable.ic_delivery)
        }
        mapView.overlays.add(destinationMarker)

        // Draw route
        routePolyline = Polyline().apply {
            addPoint(originPoint)
            addPoint(destPoint)
            color = ContextCompat.getColor(this@OrderTrackingActivity, R.color.purple_500)
            width = 5f
        }
        mapView.overlays.add(routePolyline)

        // Zoom to include both points
        mapView.zoomToBoundingBox(routePolyline?.bounds?.increaseByScale(1.5f), true)

        // Add driver marker if driver location is available
        if (order.driverLocation != null) {
            val driverLocation = order.driverLocation
            updateDriverMarker(driverLocation!!)
        }

        mapView.invalidate()
    }

    private fun startOrderTracking(orderId: String) {
        // Remove any existing listener
        orderListener?.remove()

        // Set up a real-time listener for order updates
        orderListener = firestore.collection("orders").document(orderId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to order updates: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val updatedOrder = snapshot.toObject(Order::class.java)?.apply {
                        id = snapshot.id
                    }

                    if (updatedOrder != null) {
                        // Update our local order object
                        order = updatedOrder

                        // Update order status display
                        updateOrderStatusDisplay(updatedOrder)

                        // Update driver marker position if available
                        val driverLocation = snapshot.getGeoPoint("driverLocation")
                        if (driverLocation != null) {
                            updateDriverMarker(driverLocation)

                            // Calculate and update ETA
                            calculateEta(driverLocation, updatedOrder)
                        }

                        // Update polyline based on pickup status
                        updateRoutePolyline(updatedOrder, driverLocation)
                    }
                }
            }
    }

    private fun updateDriverMarker(driverLocation: org.osmdroid.util.GeoPoint) {
        val driverPoint =
            org.osmdroid.util.GeoPoint(driverLocation.latitude, driverLocation.longitude)

        if (driverMarker == null) {
            // Create new driver marker if it doesn't exist
            driverMarker = Marker(mapView).apply {
                position = driverPoint
                title = "Driver Location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@OrderTrackingActivity, R.drawable.ic_truck)
            }
            mapView.overlays.add(driverMarker)
        } else {
            // Update existing driver marker position
            driverMarker?.position = driverPoint
        }

        // Ensure the driver marker is visible on the map
        ensureDriverVisible(driverPoint)

        mapView.invalidate()
    }

    private fun ensureDriverVisible(driverPoint: org.osmdroid.util.GeoPoint) {
        // Get current map bounds
        val mapBounds = mapView.boundingBox

        // Check if driver is within current bounds
        if (!mapBounds.contains(driverPoint)) {
            // Create a new bounding box that includes the driver and either origin or destination
            // (depending on whether the order has been picked up)
            val currentOrder = order ?: return

            val pointToInclude = if (isPickedUp) {
                org.osmdroid.util.GeoPoint(currentOrder.destinationLat, currentOrder.destinationLon)
            } else {
                org.osmdroid.util.GeoPoint(currentOrder.originLat, currentOrder.originLon)
            }

            // Calculate new bounds
            val minLat = min(driverPoint.latitude, pointToInclude.latitude)
            val maxLat = max(driverPoint.latitude, pointToInclude.latitude)
            val minLon = min(driverPoint.longitude, pointToInclude.longitude)
            val maxLon = max(driverPoint.longitude, pointToInclude.longitude)

            // Add some padding
            val latPadding = (maxLat - minLat) * 0.3
            val lonPadding = (maxLon - minLon) * 0.3

            val newBounds = BoundingBox(
                maxLat + latPadding,
                maxLon + lonPadding,
                minLat - latPadding,
                minLon - lonPadding
            )

            // Animate to the new bounds
            mapView.zoomToBoundingBox(newBounds, true)
        }
    }

    private fun updateRoutePolyline(order: Order, driverLocation: org.osmdroid.util.GeoPoint?) {
        if (driverLocation == null) return

        // Remove existing polyline
        if (routePolyline != null) {
            mapView.overlays.remove(routePolyline)
        }

        // Create a new polyline
        routePolyline = Polyline().apply {
            // Driver point
            val driverPoint =
                org.osmdroid.util.GeoPoint(driverLocation.latitude, driverLocation.longitude)

            // If the order has been picked up, draw route from driver to destination
            // Otherwise, draw route from driver to pickup (origin)
            if (isPickedUp) {
                // Draw from driver to destination
                addPoint(driverPoint)
                addPoint(org.osmdroid.util.GeoPoint(order.destinationLat, order.destinationLon))
                color = ContextCompat.getColor(this@OrderTrackingActivity, android.R.color.holo_blue_dark)
            } else {
                // Draw from driver to pickup
                addPoint(driverPoint)
                addPoint(org.osmdroid.util.GeoPoint(order.originLat, order.originLon))
                color = ContextCompat.getColor(this@OrderTrackingActivity, R.color.teal_700)
            }

            width = 5f
        }

        mapView.overlays.add(routePolyline)
        mapView.invalidate()
    }

    private fun calculateEta(driverLocation: org.osmdroid.util.GeoPoint, order: Order) {
        // Determine the target point based on order status
        val targetLat: Double
        val targetLon: Double

        if (isPickedUp) {
            // If already picked up, calculate ETA to destination
            targetLat = order.destinationLat
            targetLon = order.destinationLon
        } else {
            // If not picked up yet, calculate ETA to pickup
            targetLat = order.originLat
            targetLon = order.originLon
        }

        // Calculate distance to target in kilometers
        val distanceKm = calculateDistance(
            driverLocation.latitude, driverLocation.longitude,
            targetLat, targetLon
        )

        // Get driver speed from order if available, otherwise use average speed of 50 km/h
        val driverSpeedKmh = order.driverSpeed?.toDouble() ?: 50.0

        // Avoid division by zero
        val speedToUse = if (driverSpeedKmh < 5.0) 5.0 else driverSpeedKmh

        // Calculate ETA in minutes
        val etaMinutes = (distanceKm / speedToUse) * 60

        // Format ETA display
        val etaText = when {
            etaMinutes < 1 -> "ETA: Less than 1 minute"
            etaMinutes < 60 -> "ETA: ${etaMinutes.toInt()} minutes"
            else -> {
                val hours = etaMinutes / 60
                val mins = etaMinutes % 60
                "ETA: ${hours.toInt()} hr ${mins.toInt()} min"
            }
        }

        // Update the ETA text view
        tvEta.text = etaText
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth's radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2) * sin(dLat/2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon/2) * sin(dLon/2)
        val c = 2 * atan2(sqrt(a), sqrt(1-a))
        return R * c
    }

    private fun contactDriver() {
        val currentOrder = order ?: return
        val driverUid = currentOrder.driverUid ?: return
        val customerUid = auth.currentUser?.uid ?: return

        ChatRepository.createOrGetChat(currentOrder.id, driverUid, customerUid) { chatId ->
            if (chatId != null) {
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("EXTRA_CHAT_ID", chatId)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Failed to open chat", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()

        // Restart order tracking if needed
        orderId?.let { startOrderTracking(it) }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()

        // Remove the order listener to avoid memory leaks
        orderListener?.remove()
    }

    override fun onDestroy() {
        super.onDestroy()
        orderListener?.remove()
    }