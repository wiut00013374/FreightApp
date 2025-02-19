package com.example.freightapp.helpers
import kotlin.math.*

object PriceCalculatorHelper {

    /**
     * Basic Haversine to compute "as-the-crow-flies" distance between two lat/lon points.
     * Returns distance in meters.
     */
    fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * Example formula for total price calculation.
     * Adjust the baseFare, costPerKm, etc. to your needs.
     */
    fun calculatePrice(distanceMeters: Double, weight: Double, volume: Double): Double {
        val distanceKm = distanceMeters / 1000.0
        val baseFare = 50.0
        val costPerKm = 1.2
        val costPerKg = 0.05
        val costPerCubic = 0.1

        return baseFare +
                (distanceKm * costPerKm) +
                (weight * costPerKg) +
                (volume * costPerCubic)
    }
}
