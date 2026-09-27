package com.navio.companion

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

class LocationProvider(context: Context) {

    interface Callback {
        fun onLocation(lat: Double, lon: Double, speedMps: Float)
    }

    private val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    fun start(cb: Callback) {
        stop()
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val speed = if (location.hasSpeed()) location.speed else -1f
                cb.onLocation(location.latitude, location.longitude, speed)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            @Deprecated("Deprecated in Java")
            override fun onProviderEnabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onProviderDisabled(provider: String) {}
        }
        listener = l
        var started = false
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            started = tryStart(LocationManager.GPS_PROVIDER, l)
        }
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            started = tryStart(LocationManager.NETWORK_PROVIDER, l) || started
        }
        if (!started) {
            tryStart(LocationManager.GPS_PROVIDER, l)
            tryStart(LocationManager.NETWORK_PROVIDER, l)
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryStart(provider: String, l: LocationListener): Boolean = try {
        lm.requestLocationUpdates(provider, 1000L, 0f, l, Looper.getMainLooper())
        true
    } catch (e: Exception) {
        false
    }

    fun stop() {
        listener?.let { runCatching { lm.removeUpdates(it) } }
        listener = null
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): LatLon? {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) return LatLon(loc.latitude, loc.longitude)
            } catch (e: Exception) {
            }
        }
        return null
    }
}
