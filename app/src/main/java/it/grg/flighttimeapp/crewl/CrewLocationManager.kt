package it.grg.flighttimeapp.crewl

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class CrewLocationManager private constructor() {

    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context, onLocation: (Location) -> Unit) {
        if (client == null) {
            client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        }
        stop()

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(8_000L)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocation(loc)
            }
        }
        callback = cb
        client?.requestLocationUpdates(request, cb, android.os.Looper.getMainLooper())

        client?.lastLocation?.addOnSuccessListener { loc ->
            if (loc != null) onLocation(loc)
        }
    }

    fun stop() {
        val c = client ?: return
        callback?.let { c.removeLocationUpdates(it) }
        callback = null
    }

    companion object {
        val shared = CrewLocationManager()
    }
}
