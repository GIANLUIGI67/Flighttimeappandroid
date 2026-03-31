package it.grg.flighttimeapp.crewl

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class CrewLocationManager private constructor() {

    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private val listeners = mutableSetOf<(Location) -> Unit>()
    private var lastKnownLocation: Location? = null
    
    private var systemLocationManager: LocationManager? = null
    private var systemLocationListener: LocationListener? = null

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, onLocation: (Location) -> Unit) {
        if (client == null) {
            client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        }
        if (systemLocationManager == null) {
            systemLocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }
        
        synchronized(listeners) {
            listeners.add(onLocation)
        }
        
        Log.d(TAG, "Starting location updates. Listeners: ${listeners.size}. Enabled=${isLocationEnabled(context)}")

        // 1. Immediate cached location
        lastKnownLocation?.let { onLocation(it) }

        // 2. GMS last location
        client?.lastLocation?.addOnSuccessListener { loc ->
            if (loc != null && isReasonable(loc)) {
                Log.d(TAG, "GMS LastLocation: ${loc.latitude}, ${loc.longitude}")
                updateAndNotify(loc)
            }
        }

        // 3. System LocationManager fallback (very fast sometimes)
        try {
            val providers = systemLocationManager?.getProviders(true) ?: emptyList()
            for (provider in providers) {
                val loc = systemLocationManager?.getLastKnownLocation(provider)
                if (loc != null && isReasonable(loc)) {
                    Log.d(TAG, "System LastKnown ($provider): ${loc.latitude}, ${loc.longitude}")
                    updateAndNotify(loc)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "System LastKnown error: ${e.message}")
        }

        // 4. One-shot fresh fix
        val cts = CancellationTokenSource()
        client?.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            ?.addOnSuccessListener { loc ->
                if (loc != null && isReasonable(loc)) {
                    Log.d(TAG, "GMS One-shot fix: ${loc.latitude}, ${loc.longitude}")
                    updateAndNotify(loc)
                }
            }

        // 5. Continuous updates
        if (callback == null) {
            Log.d(TAG, "🚀 Starting GMS continuous updates")
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .build()

            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { updateAndNotify(it) }
                }
            }
            callback = cb
            client?.requestLocationUpdates(request, cb, android.os.Looper.getMainLooper())
        }

        // 6. System continuous updates fallback
        if (systemLocationListener == null) {
            val sl = object : LocationListener {
                override fun onLocationChanged(location: Location) { updateAndNotify(location) }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            systemLocationListener = sl
            try {
                if (systemLocationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                    systemLocationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, sl)
                }
                if (systemLocationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                    systemLocationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 10f, sl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "System updates error: ${e.message}")
            }
        }
    }

    private fun isReasonable(loc: Location): Boolean {
        if (loc.latitude == 0.0 && loc.longitude == 0.0 && loc.accuracy > 1000) return false
        return true
    }

    private fun updateAndNotify(loc: Location) {
        if (!isReasonable(loc)) return
        
        // Only notify if it's a significant change or we have no last location
        val last = lastKnownLocation
        if (last == null || last.distanceTo(loc) > 10f || last.accuracy > loc.accuracy) {
            lastKnownLocation = loc
            val currentListeners = synchronized(listeners) { listeners.toList() }
            currentListeners.forEach { it(loc) }
        }
    }

    fun stop(onLocation: ((Location) -> Unit)? = null) {
        synchronized(listeners) {
            if (onLocation != null) listeners.remove(onLocation) else listeners.clear()
        }

        if (listeners.isEmpty()) {
            callback?.let { client?.removeLocationUpdates(it) }
            callback = null
            systemLocationListener?.let { systemLocationManager?.removeUpdates(it) }
            systemLocationListener = null
            Log.d(TAG, "🛑 Updates stopped")
        }
    }

    companion object {
        val shared = CrewLocationManager()
        private const val TAG = "CrewLocation"
    }
}
