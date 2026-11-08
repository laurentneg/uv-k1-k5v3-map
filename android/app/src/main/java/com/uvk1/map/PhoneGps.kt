package com.uvk1.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class PhoneGps(
    private val context: Context,
    private val onFix: (lat: Double, lon: Double, accuracy: Float, latCas: Int, lonCas: Int) -> Unit,
    private val onStatus: (String) -> Unit,
) : LocationListener {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastLatCas: Int? = null
    private var lastLonCas: Int? = null
    private var reinject: Runnable? = null
    private var started = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) {
            onStatus("Permission localisation requise")
            return
        }
        if (started) return
        started = true
        onStatus("GPS téléphone…")
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        for (p in providers) {
            if (locationManager.isProviderEnabled(p)) {
                locationManager.requestLocationUpdates(p, 2000L, 1f, this, Looper.getMainLooper())
            }
        }
        // Re-inject often enough for the radio's 15 s TTL
        val r = object : Runnable {
            override fun run() {
                val lat = lastLatCas
                val lon = lastLonCas
                if (lat != null && lon != null) {
                    // Accuracy unknown on reinject; MainActivity keeps last known
                    onFix(
                        K5ViewerProtocol.centiArcsecondsToDegrees(lat),
                        K5ViewerProtocol.centiArcsecondsToDegrees(lon),
                        -1f,
                        lat,
                        lon
                    )
                }
                handler.postDelayed(this, 5000L)
            }
        }
        reinject = r
        handler.postDelayed(r, 5000L)
    }

    /** Last fix in centi-arcseconds, or null if none yet. */
    fun lastFixCas(): Pair<Int, Int>? {
        val lat = lastLatCas ?: return null
        val lon = lastLonCas ?: return null
        return lat to lon
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager.removeUpdates(this) }
        reinject?.let { handler.removeCallbacks(it) }
        reinject = null
    }

    override fun onLocationChanged(location: Location) {
        val latCas = K5ViewerProtocol.degreesToCentiArcseconds(location.latitude)
        val lonCas = K5ViewerProtocol.degreesToCentiArcseconds(location.longitude)
        lastLatCas = latCas
        lastLonCas = lonCas
        onFix(location.latitude, location.longitude, location.accuracy, latCas, lonCas)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
