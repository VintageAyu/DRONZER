package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import java.util.Locale

object LocationSender {
    private var latestLocation: Location? = null
    private var appContext: Context? = null
    private var latestCity: String? = null
    private var latestArea: String? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = location
            Log.d("LocationSender", "Location updated: ${location.latitude}, ${location.longitude}")
            appContext?.let { updateAddress(it, location) }
        }
        
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        
        override fun onProviderEnabled(provider: String) {
            Log.d("LocationSender", "Provider Enabled: $provider. Catching location now...")
        }
        
        override fun onProviderDisabled(provider: String) {
            Log.d("LocationSender", "Provider Disabled: $provider. Waiting for user to turn it on...")
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(context: Context) {
        appContext = context.applicationContext
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // We request updates from both GPS and Network providers. 
            // Setting the interval to 3000ms (3 seconds) and distance to 0m for real-time tracking.
            // Even if location is OFF, these requests stay registered. 
            // As soon as the user turns on Location, the system will start sending data to the listener.
            
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            
            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        3000L, // 3 seconds interval
                        0f,    // 0 meters minimum distance
                        locationListener
                    )
                } catch (e: IllegalArgumentException) {
                    Log.e("LocationSender", "Provider $provider not available on this device")
                }
            }

            // Try to get the best last known location immediately
            val lastGps = try { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
            val lastNet = try { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
            
            val initialLoc = when {
                lastGps != null && lastNet != null -> if (lastGps.accuracy <= lastNet.accuracy) lastGps else lastNet
                lastGps != null -> lastGps
                else -> lastNet
            }

            if (initialLoc != null) {
                latestLocation = initialLoc
                appContext?.let { updateAddress(it, initialLoc) }
            }
            
        } catch (e: Exception) {
            Log.e("LocationSender", "Error starting location updates: ${e.message}")
        }
    }

    private fun updateAddress(context: Context, location: Location) {
        Thread {
            try {
                if (!Geocoder.isPresent()) return@Thread
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    latestCity = address.locality ?: "Unknown City"
                    latestArea = address.subLocality ?: address.subAdminArea ?: address.adminArea ?: "Unknown Area"
                    Log.d("LocationSender", "Address Updated: City: $latestCity, Area: $latestArea")
                }
            } catch (e: Exception) {
                Log.e("LocationSender", "Error fetching address: ${e.message}")
            }
        }.start()
    }

    fun stopLocationUpdates(context: Context) {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.e("LocationSender", "Error stopping location updates: ${e.message}")
        }
    }

    fun getLocationString(context: Context): String {
        val location = latestLocation
        return if (location != null) {
            val lat = location.latitude
            val lon = location.longitude
            val city = latestCity ?: "Fetching..."
            val area = latestArea ?: "Fetching..."
            
            "--- Location ---\n" +
            "City: $city\n" +
            "Area: $area\n" +
            "Lat: $lat, Lon: $lon\n" +
            "Provider: ${location.provider}\n" +
            "Accuracy: ${location.accuracy}m\n" +
            "Maps: https://www.google.com/maps/search/?api=1&query=$lat,$lon\n"
        } else {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            "--- Location ---\n" +
            "(Waiting for location fix... GPS=$isGpsEnabled, Network=$isNetworkEnabled)\n"
        }
    }
}
