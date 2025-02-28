package com.example.freightapp.helpers

import android.util.Log
import com.example.freightapp.NominatimSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

object NominatimHelper {
    private const val TAG = "NominatimHelper"

    /**
     * searchNominatimForward:
     * Given a text query (e.g. city name or address), returns up to 5 suggestions from Nominatim,
     * each with a displayName, lat, lon.
     *
     * Make sure you have INTERNET permission and a valid User-Agent (in general, default might work).
     */
    suspend fun searchNominatimForward(query: String): List<NominatimSuggestion> {
        // Run on IO dispatcher so we don't block the main thread
        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val urlStr = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=$encodedQuery"
                val connection = URL(urlStr).openConnection()
                connection.setRequestProperty("User-Agent", "FreightApp-Android")

                val response = connection.getInputStream().bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val results = mutableListOf<NominatimSuggestion>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    results.add(
                        NominatimSuggestion(
                            displayName = obj.getString("display_name"),
                            lat = obj.getString("lat").toDouble(),
                            lon = obj.getString("lon").toDouble()
                        )
                    )
                }
                results
            } catch (e: Exception) {
                Log.e(TAG, "Forward geocode failed: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Reverse geocode: Get address from coordinates
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        return withContext(Dispatchers.IO) {
            try {
                val urlStr = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon"
                val connection = URL(urlStr).openConnection()
                connection.setRequestProperty("User-Agent", "FreightApp-Android")

                val response = connection.getInputStream().bufferedReader().use { it.readText() }
                val jsonObj = org.json.JSONObject(response)
                jsonObj.optString("display_name")
            } catch (e: Exception) {
                Log.e(TAG, "Reverse geocode failed: ${e.message}")
                null
            }
        }
    }
}