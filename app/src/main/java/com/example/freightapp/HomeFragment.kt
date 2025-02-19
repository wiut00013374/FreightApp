package com.example.freightapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.*

data class NominatimSuggestion(
    val displayName: String,
    val lat: Double,
    val lon: Double
)

class HomeFragment : Fragment() {

    // Views within the fragment
    private lateinit var mapView: MapView
    private lateinit var actvOrigin: AutoCompleteTextView
    private lateinit var actvDestination: AutoCompleteTextView
    private lateinit var btnShowRoute: Button
    private lateinit var imgBtnMyLocation: ImageButton

    // Overlays and markers
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var myLocationMarker: Marker? = null

    // Route coordinates
    private var originLat: Double? = null
    private var originLon: Double? = null
    private var destLat: Double? = null
    private var destLon: Double? = null

    // Forward geocoding suggestions
    private var lastSearchResultsOrigin = listOf<NominatimSuggestion>()
    private var lastSearchResultsDest = listOf<NominatimSuggestion>()

    // Toggle for map tapping: true means next tap sets "From", false sets "To"
    private var isSelectingFromOnMap = true

    // Debounce handler for text input
    private val searchHandler = Handler(Looper.getMainLooper())
    private val SEARCH_DELAY_MS = 500L

    companion object {
        private const val TAG = "HomeFragment"
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 101
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate fragment_home.xml layout
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Set osmdroid user agent (if not already set in Application)
        Configuration.getInstance().userAgentValue = requireActivity().packageName

        // Reference views from the fragment's layout
        mapView = view.findViewById(R.id.mapView)
        actvOrigin = view.findViewById(R.id.actvOrigin)
        actvDestination = view.findViewById(R.id.actvDestination)
        btnShowRoute = view.findViewById(R.id.btnShowRoute)
        imgBtnMyLocation = view.findViewById(R.id.imgBtnMyLocation)

        initMap()
        checkPermissions()

        imgBtnMyLocation.setOnClickListener {
            centerMapOnMyLocation()
        }

        // Forward geocoding for "From"
        actvOrigin.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.length < 2) {
                    actvOrigin.setAdapter(
                        ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, emptyList<String>())
                    )
                    actvOrigin.dismissDropDown()
                    return
                }
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({
                    searchNominatimForward(query) { suggestions ->
                        lastSearchResultsOrigin = suggestions
                        val displayNames = suggestions.map { it.displayName }
                        val adapter = ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            displayNames
                        )
                        actvOrigin.setAdapter(adapter)
                        actvOrigin.showDropDown()
                    }
                }, SEARCH_DELAY_MS)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
        })

        actvOrigin.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as String
            val suggestion = lastSearchResultsOrigin.firstOrNull { it.displayName == selectedName }
            suggestion?.let {
                originLat = it.lat
                originLon = it.lon
                originMarker?.let { marker -> mapView.overlays.remove(marker) }
                originMarker = addMarker(GeoPoint(originLat!!, originLon!!), "Origin")
                mapView.controller.setCenter(GeoPoint(originLat!!, originLon!!))
                actvOrigin.setText(it.displayName)
                actvOrigin.setAdapter(
                    ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, emptyList<String>())
                )
                actvOrigin.dismissDropDown()
            }
        }

        // Forward geocoding for "To"
        actvDestination.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.length < 2) {
                    actvDestination.setAdapter(
                        ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, emptyList<String>())
                    )
                    actvDestination.dismissDropDown()
                    return
                }
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({
                    searchNominatimForward(query) { suggestions ->
                        lastSearchResultsDest = suggestions
                        val displayNames = suggestions.map { it.displayName }
                        val adapter = ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            displayNames
                        )
                        actvDestination.setAdapter(adapter)
                        actvDestination.showDropDown()
                    }
                }, SEARCH_DELAY_MS)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
        })

        actvDestination.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as String
            val suggestion = lastSearchResultsDest.firstOrNull { it.displayName == selectedName }
            suggestion?.let {
                destLat = it.lat
                destLon = it.lon
                destinationMarker?.let { marker -> mapView.overlays.remove(marker) }
                destinationMarker = addMarker(GeoPoint(destLat!!, destLon!!), "Destination")
                mapView.controller.setCenter(GeoPoint(destLat!!, destLon!!))
                actvDestination.setText(it.displayName)
                actvDestination.setAdapter(
                    ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, emptyList<String>())
                )
                actvDestination.dismissDropDown()
            }
        }

        // Map tap: reverse geocoding
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let { pickAddressFromMap(it.latitude, it.longitude) }
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
        mapView.overlays.add(mapEventsOverlay)

        // "Show Route" button: When pressed, fetch the route then show a confirmation dialog.
        btnShowRoute.setOnClickListener {
            if (originLat == null || originLon == null || destLat == null || destLon == null) {
                Toast.makeText(requireContext(), "Please pick both origin and destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CoroutineScope(Dispatchers.IO).launch {
                val route = getRoute(originLat!!, originLon!!, destLat!!, destLon!!)
                withContext(Dispatchers.Main) {
                    if (route != null) {
                        drawRoute(route)
                        val totalDistance = calculateRouteDistance(route)
                        // Get addresses from the UI fields
                        val originAddress = actvOrigin.text.toString().trim()
                        val destinationAddress = actvDestination.text.toString().trim()
                        showRouteConfirmationDialog(totalDistance, originAddress, destinationAddress)
                    } else {
                        Toast.makeText(requireContext(), "Failed to find a route", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun searchNominatimForward(query: String, callback: (List<NominatimSuggestion>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlStr = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=$query"
                val response = URL(urlStr).readText()
                val jsonArray = JSONArray(response)
                val results = mutableListOf<NominatimSuggestion>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    results.add(
                        NominatimSuggestion(
                            obj.getString("display_name"),
                            obj.getString("lat").toDouble(),
                            obj.getString("lon").toDouble()
                        )
                    )
                }
                withContext(Dispatchers.Main) { callback(results) }
            } catch (e: Exception) {
                Log.e(TAG, "Forward geocode failed: ${e.message}")
                withContext(Dispatchers.Main) { callback(emptyList()) }
            }
        }
    }

    private fun pickAddressFromMap(lat: Double, lon: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlStr = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon"
                val response = URL(urlStr).readText()
                val json = JSONObject(response)
                val addressStr = json.optString("display_name", "Unknown location")
                withContext(Dispatchers.Main) {
                    if (isSelectingFromOnMap) {
                        actvOrigin.setText(addressStr)
                        originLat = lat
                        originLon = lon
                        originMarker?.let { mapView.overlays.remove(it) }
                        originMarker = addMarker(GeoPoint(lat, lon), "Origin")
                        isSelectingFromOnMap = false
                        Toast.makeText(requireContext(), "Picked FROM location from map", Toast.LENGTH_SHORT).show()
                    } else {
                        actvDestination.setText(addressStr)
                        destLat = lat
                        destLon = lon
                        destinationMarker?.let { mapView.overlays.remove(it) }
                        destinationMarker = addMarker(GeoPoint(lat, lon), "Destination")
                        isSelectingFromOnMap = true
                        Toast.makeText(requireContext(), "Picked TO location from map", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reverse geocode failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Reverse geocode failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(GeoPoint(41.2995, 69.2401))
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), mapView)
        myLocationOverlay.enableMyLocation()
        mapView.overlays.add(myLocationOverlay)
    }

    private fun addMarker(point: GeoPoint, title: String): Marker {
        val marker = Marker(mapView)
        marker.position = point
        marker.title = title
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        mapView.overlays.add(marker)
        mapView.invalidate()
        return marker
    }

    private suspend fun getRoute(oLat: Double, oLon: Double, dLat: Double, dLon: Double): List<GeoPoint>? =
        withContext(Dispatchers.IO) {
            val urlStr = "https://router.project-osrm.org/route/v1/driving/$oLon,$oLat;$dLon,$dLat?overview=full&geometries=geojson"
            try {
                val connection = URL(urlStr).openConnection() as HttpsURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    if (json.has("code") && json.getString("code") == "Ok") {
                        val route = json.getJSONArray("routes").getJSONObject(0)
                        val geometry = route.getJSONObject("geometry")
                        val coordinates = geometry.getJSONArray("coordinates")
                        val geoPoints = mutableListOf<GeoPoint>()
                        for (i in 0 until coordinates.length()) {
                            val point = coordinates.getJSONArray(i)
                            geoPoints.add(GeoPoint(point.getDouble(1), point.getDouble(0))) // lat, lon
                        }
                        return@withContext geoPoints
                    } else {
                        Log.e(TAG, "OSRM API error: ${json.optString("message")}")
                        return@withContext null
                    }
                } else {
                    Log.e(TAG, "OSRM connection failed with code: $responseCode")
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting route: ${e.message}")
                return@withContext null
            }
        }

    private fun drawRoute(points: List<GeoPoint>) {
        val polyline = Polyline(mapView)
        polyline.setPoints(points)
        polyline.color = Color.BLUE
        polyline.width = 8f

        val existingPolylines = mapView.overlays.filterIsInstance<Polyline>()
        mapView.overlays.removeAll(existingPolylines)
        mapView.overlays.add(polyline)
        mapView.invalidate()

        mapView.zoomToBoundingBox(polyline.bounds, true)

        val totalDistanceMeters = calculateRouteDistance(points)
        val originAddress = actvOrigin.text.toString().trim()
        val destinationAddress = actvDestination.text.toString().trim()
        showRouteConfirmationDialog(totalDistanceMeters, originAddress, destinationAddress)
    }

    private fun calculateRouteDistance(points: List<GeoPoint>): Double {
        var distance = 0.0
        for (i in 0 until points.size - 1) {
            distance += haversine(points[i], points[i + 1])
        }
        return distance
    }

    private fun haversine(p1: GeoPoint, p2: GeoPoint): Double {
        val R = 6371000.0 // Earth's radius in meters
        val lat1Rad = Math.toRadians(p1.latitude)
        val lat2Rad = Math.toRadians(p2.latitude)
        val deltaLat = Math.toRadians(p2.latitude - p1.latitude)
        val deltaLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(deltaLat / 2).pow(2.0) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun showRouteConfirmationDialog(
        totalDistanceMeters: Double,
        originAddress: String,
        destinationAddress: String
    ) {
        val totalDistanceKm = totalDistanceMeters / 1000.0
        val message = "Origin: $originAddress\n" +
                "Destination: $destinationAddress\n" +
                "Total route distance: %.2f km.\nDo you want to proceed with entering freight details?"
                    .format(totalDistanceKm)
        AlertDialog.Builder(requireContext())
            .setTitle("Route Confirmed")
            .setMessage(message)
            .setPositiveButton("Proceed") { _, _ ->
                // Check that the coordinate values are not null; if they are, display an error.
                if (originLat == null || originLon == null || destLat == null || destLon == null) {
                    Toast.makeText(requireContext(), "Missing location data. Please check your selections.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Launch the FreightDetailsActivity and pass all data.
                val intent = Intent(requireContext(), FreightDetailsActivity::class.java).apply {
                    putExtra("originAddress", originAddress)
                    putExtra("destinationAddress", destinationAddress)
                    putExtra("totalDistance", totalDistanceMeters)
                    putExtra("originLat", originLat)
                    putExtra("originLon", originLon)
                    putExtra("destinationLat", destLat)
                    putExtra("destinationLon", destLon)
                    // You can also pass truckType, volume, weight, totalPrice if available,
                    // or these could be entered in FreightDetailsActivity.
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Recalculate") { dialog, _ ->
                dialog.dismiss()
                // Optionally clear current route data.
            }
            .show()
    }



    private fun centerMapOnMyLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            myLocationOverlay.enableMyLocation()
            val locationProvider = myLocationOverlay.myLocationProvider
            val lastKnownLocation = locationProvider.lastKnownLocation
            if (lastKnownLocation != null) {
                val myLocation = GeoPoint(lastKnownLocation)
                mapView.controller.animateTo(myLocation)
                mapView.controller.setZoom(15.0)
                myLocationMarker?.let { mapView.overlays.remove(it) }
                myLocationMarker = addMarker(myLocation, "My Location")
            } else {
                Toast.makeText(requireContext(), "Location not available. Please check your settings.", Toast.LENGTH_LONG).show()
                showEnableLocationServicesDialog()
            }
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private fun showEnableLocationServicesDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Enable Location Services")
            .setMessage("This app needs access to your location to show your current position on the map. Please enable location services in your device settings.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsToRequest.toTypedArray(),
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        myLocationOverlay.disableMyLocation()
    }
}
