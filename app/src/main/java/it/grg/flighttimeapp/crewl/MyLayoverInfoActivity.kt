package it.grg.flighttimeapp.crewl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import it.grg.flighttimeapp.R
import java.util.Locale

class MyLayoverInfoActivity : AppCompatActivity() {

    private lateinit var backBtn: ImageButton
    private lateinit var cityText: TextView
    private lateinit var noCityText: TextView
    private lateinit var categoriesRecycler: RecyclerView
    private lateinit var debugBanner: TextView

    private lateinit var adapter: LayoverInfoCategoryAdapter

    private var detectedCityName: String = ""
    private var detectedCityKey: String = ""
    private var manualCityOverride: String = ""
    private var manualCityKeyOverride: String = ""
    private var observedCityKey: String = ""
    private var lastLocation: Location? = null
    private var resolveToken: Int = 0
    private val resolveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var resolveTimeout: Runnable? = null
    private val categoryCounts: MutableMap<String, Int> = mutableMapOf()
    private val categoryListeners: MutableMap<LayoverInfoCategory, Pair<DatabaseReference, ValueEventListener>> = mutableMapOf()
    private val dbRoot: DatabaseReference by lazy { FirebaseDatabase.getInstance().reference }

    private val logTag = "MyLayoverInfo"

    private val locationListener: (Location) -> Unit = { loc ->
        Log.d(logTag, "📍 resolve city from lat=${loc.latitude} lon=${loc.longitude} acc=${loc.accuracy}")
        lastLocation = loc
        if (manualCityOverride.isBlank()) {
            resolveCity(loc)
        } else {
            Log.d(logTag, "Manual city override active, skip auto-detect")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_layover_info)

        backBtn = findViewById(R.id.layoverInfoBack)
        cityText = findViewById(R.id.layoverInfoCityText)
        noCityText = findViewById(R.id.layoverInfoNoCity)
        categoriesRecycler = findViewById(R.id.layoverInfoRecycler)
        debugBanner = findViewById(R.id.layoverInfoDebugBanner)

        backBtn.setOnClickListener { finish() }
        cityText.setOnClickListener {
            if (displayCity().isBlank() && detectedCityName.isBlank()) return@setOnClickListener
            showCityEditor()
        }

        adapter = LayoverInfoCategoryAdapter(LayoverInfoCategory.entries.toList()) { category ->
            if (displayCity().isBlank() || selectedCityKey().isBlank()) return@LayoverInfoCategoryAdapter
            val i = Intent(this, LayoverInfoCategoryActivity::class.java).apply {
                putExtra(LayoverInfoCategoryActivity.EXTRA_CATEGORY, category.key)
                putExtra(LayoverInfoCategoryActivity.EXTRA_CITY_NAME, displayCity())
                putExtra(LayoverInfoCategoryActivity.EXTRA_CITY_KEY, selectedCityKey())
            }
            startActivity(i)
        }
        categoriesRecycler.layoutManager = LinearLayoutManager(this)
        categoriesRecycler.adapter = adapter

        updateCityUI(isLoading = true)
        ensureLocationAndLoadCity()
    }

    private fun ensureLocationAndLoadCity() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        Log.d(logTag, "location permission fine=$fine coarse=$coarse")
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            updateDebugBanner("Location permission granted - starting detection")
            CrewLocationManager.shared.start(this, locationListener)
        } else {
            updateDebugBanner("Location permission missing")
            showLocationRequiredDialog()
        }
    }

    private fun showLocationRequiredDialog() {
        updateCityUI(isLoading = false)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_location_required_title))
            .setMessage(getString(R.string.cl_location_required_message))
            .setPositiveButton(getString(R.string.cl_location_open_settings)) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cl_location_cancel), null)
            .show()
    }

    private fun resolveCity(location: Location) {
        if (manualCityOverride.isNotBlank()) {
            Log.d(logTag, "Manual city override active, skipping resolveCity")
            return
        }
        resolveToken += 1
        val token = resolveToken
        resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (token != resolveToken) return@Runnable
            Log.w(logTag, "⚠️ Geocoding timeout, using fallback")
            val fallbackName = Locale.getDefault().displayCountry
            if (fallbackName.isNotBlank()) {
                detectedCityName = fallbackName
                detectedCityKey = LayoverInfoCategoryStore.sanitizeCityKey(Locale.getDefault().country)
            } else {
                detectedCityName = ""
                detectedCityKey = ""
            }
            updateDebugBanner("Geocoding timeout - fallback")
            updateCityUI(isLoading = false)
        }
        resolveTimeout = timeout
        resolveHandler.postDelayed(timeout, 4000L)
        updateCityUI(isLoading = true)

        if (!Geocoder.isPresent()) {
            Log.e(logTag, "Geocoder not present on device")
            updateDebugBanner("Geocoder unavailable - using fallback")
            val fallbackName = Locale.getDefault().displayCountry
            if (fallbackName.isNotBlank()) {
                detectedCityName = fallbackName
                detectedCityKey = LayoverInfoCategoryStore.sanitizeCityKey(Locale.getDefault().country)
                resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
                updateCityUI(isLoading = false)
            } else {
                resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
                updateCityUI(isLoading = false)
            }
            return
        }

        val displayGeocoder = Geocoder(this, Locale.getDefault())
        val keyGeocoder = Geocoder(this, Locale.US)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            displayGeocoder.getFromLocation(location.latitude, location.longitude, 5) { displayList ->
                val addr = displayList.firstOrNull()
                val displayName = addr?.locality
                    ?: addr?.subAdminArea
                    ?: addr?.adminArea
                    ?: addr?.countryName

                Log.d(logTag, "display geocoder result: $displayName from ${displayList.size} results")
                updateDebugBanner("Geocoding display=$displayName")

                keyGeocoder.getFromLocation(location.latitude, location.longitude, 5) { keyList ->
                    val kAddr = keyList.firstOrNull()
                    val keyCity = kAddr?.locality
                        ?: kAddr?.subAdminArea
                        ?: kAddr?.adminArea
                        ?: kAddr?.countryName
                    val countryCode = kAddr?.countryCode
                        ?: addr?.countryCode
                        ?: ""
                    
                    Log.d(logTag, "key geocoder result: $keyCity country=$countryCode")
                    updateDebugBanner("Geocoding key=$keyCity cc=$countryCode")
                    val rawKey = listOf(countryCode, keyCity).filter { !it.isNullOrBlank() }.joinToString("_")
                    val stableKey = LayoverInfoCategoryStore.sanitizeCityKey(rawKey)

                    runOnUiThread {
                        if (manualCityOverride.isNotBlank()) return@runOnUiThread
                        if (!displayName.isNullOrBlank() && stableKey.isNotBlank() && stableKey != "unknown") {
                            detectedCityName = displayName
                            detectedCityKey = stableKey
                            updateDebugBanner("Detected: $displayName ($stableKey)")
                            resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
                            updateCityUI(isLoading = false)
                        } else {
                            val fallbackCountry = addr?.countryName ?: kAddr?.countryName
                            if (!fallbackCountry.isNullOrBlank()) {
                                detectedCityName = fallbackCountry
                                detectedCityKey = LayoverInfoCategoryStore.sanitizeCityKey(addr?.countryCode ?: kAddr?.countryCode ?: fallbackCountry)
                                updateDebugBanner("Fallback: $fallbackCountry ($detectedCityKey)")
                                resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
                                updateCityUI(isLoading = false)
                            } else {
                                updateDebugBanner("No city detected")
                                resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
                                updateCityUI(isLoading = false)
                            }
                        }
                    }
                }
            }
        } else {
            Thread {
                val addr = try {
                    @Suppress("DEPRECATION")
                    displayGeocoder.getFromLocation(location.latitude, location.longitude, 5)?.firstOrNull()
                } catch (_: Exception) { null }

                val displayName = addr?.locality
                    ?: addr?.subAdminArea
                    ?: addr?.adminArea
                    ?: addr?.countryName

                val kAddr = try {
                    @Suppress("DEPRECATION")
                    keyGeocoder.getFromLocation(location.latitude, location.longitude, 5)?.firstOrNull()
                } catch (_: Exception) { null }

                val keyCity = kAddr?.locality
                    ?: kAddr?.subAdminArea
                    ?: kAddr?.adminArea
                    ?: kAddr?.countryName
                val countryCode = kAddr?.countryCode ?: addr?.countryCode ?: ""
                val rawKey = listOf(countryCode, keyCity).filter { !it.isNullOrBlank() }.joinToString("_")
                val stableKey = LayoverInfoCategoryStore.sanitizeCityKey(rawKey)

                runOnUiThread {
                    if (manualCityOverride.isNotBlank()) return@runOnUiThread
                    if (!displayName.isNullOrBlank() && stableKey.isNotBlank() && stableKey != "unknown") {
                        detectedCityName = displayName
                        detectedCityKey = stableKey
                        updateDebugBanner("Detected: $displayName ($stableKey)")
                        resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
                        updateCityUI(isLoading = false)
                    } else {
                        val fallbackCountry = addr?.countryName ?: kAddr?.countryName
                        if (!fallbackCountry.isNullOrBlank()) {
                            detectedCityName = fallbackCountry
                            detectedCityKey = LayoverInfoCategoryStore.sanitizeCityKey(addr?.countryCode ?: kAddr?.countryCode ?: fallbackCountry)
                            updateDebugBanner("Fallback: $fallbackCountry ($detectedCityKey)")
                            resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
                            updateCityUI(isLoading = false)
                        } else {
                            updateDebugBanner("No city detected")
                            resolveTimeout?.let { resolveHandler.removeCallbacks(it) }
                            updateCityUI(isLoading = false)
                        }
                    }
                }
            }.start()
        }
    }

    private fun showCityEditor() {
        val input = android.widget.EditText(this).apply {
            setText(displayCity().ifBlank { detectedCityName })
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_layover_info_edit_city))
            .setMessage(getString(R.string.cl_layover_info_edit_city_message))
            .setView(input)
            .setPositiveButton(getString(R.string.cl_layover_info_save)) { _, _ ->
                val value = input.text.toString().trim()
                val targetName = if (value.isBlank()) detectedCityName else value
                manualCityOverride = targetName
                resolveCityKeyFromName(targetName) { keyOverride ->
                    manualCityKeyOverride = keyOverride ?: LayoverInfoCategoryStore.sanitizeCityKey(targetName)
                    updateDebugBanner("Manual city: $manualCityOverride ($manualCityKeyOverride)")
                    updateCityUI(isLoading = false)
                }
            }
            .setNeutralButton(getString(R.string.cl_layover_info_reset_city)) { _, _ ->
                manualCityOverride = ""
                manualCityKeyOverride = ""
                updateDebugBanner("Manual city cleared")
                lastLocation?.let { resolveCity(it) } ?: updateCityUI(isLoading = false)
            }
            .setNegativeButton(getString(R.string.cl_layover_info_cancel), null)
            .show()
    }

    private fun resolveCityKeyFromName(cityName: String, callback: (String?) -> Unit) {
        val trimmed = cityName.trim()
        if (trimmed.isBlank()) {
            callback(null)
            return
        }
        val geocoder = Geocoder(this, Locale.US)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocationName(trimmed, 1) { list ->
                val location = list.firstOrNull()
                if (location == null) {
                    val fallback = LayoverInfoCategoryStore.fallbackCityKey(trimmed)
                    callback(fallback ?: LayoverInfoCategoryStore.sanitizeCityKey(trimmed))
                    return@getFromLocationName
                }
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { keyList ->
                    val placemark = keyList.firstOrNull()
                    val keyCity = placemark?.locality ?: placemark?.adminArea ?: placemark?.countryName
                    val countryCode = placemark?.countryCode ?: ""
                    val rawKey = listOf(countryCode, keyCity).filter { !it.isNullOrBlank() }.joinToString("_")
                    if (rawKey.isBlank()) {
                        val fallback = LayoverInfoCategoryStore.fallbackCityKey(trimmed)
                        callback(fallback ?: LayoverInfoCategoryStore.sanitizeCityKey(trimmed))
                    } else {
                        callback(LayoverInfoCategoryStore.sanitizeCityKey(rawKey))
                    }
                }
            }
        } else {
            Thread {
                val baseKey = try {
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocationName(trimmed, 1)
                    val location = list?.firstOrNull()
                    if (location == null) {
                        trimmed
                    } else {
                        @Suppress("DEPRECATION")
                        val keyList = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        val placemark = keyList?.firstOrNull()
                        val keyCity = placemark?.locality ?: placemark?.adminArea ?: placemark?.countryName
                        val countryCode = placemark?.countryCode ?: ""
                        val rawKey = listOf(countryCode, keyCity).filter { !it.isNullOrBlank() }.joinToString("_")
                        if (rawKey.isBlank()) trimmed else rawKey
                    }
                } catch (_: Exception) {
                    trimmed
                }
                runOnUiThread {
                    val fallback = LayoverInfoCategoryStore.fallbackCityKey(trimmed)
                    if (baseKey == trimmed && fallback != null) {
                        callback(fallback)
                    } else {
                        callback(LayoverInfoCategoryStore.sanitizeCityKey(baseKey))
                    }
                }
            }.start()
        }
    }

    private fun updateCityUI(isLoading: Boolean) {
        if (isLoading) {
            cityText.text = getString(R.string.my_layover_info_detecting)
            updateDebugBanner("Detecting city…")
            noCityText.visibility = View.GONE
            categoriesRecycler.visibility = View.GONE
            removeCategoryListeners()
            return
        }

        val display = displayCity()
        val key = selectedCityKey()
        if (display.isNotBlank() && key.isNotBlank()) {
            cityText.text = getString(R.string.my_layover_info_city_format, display)
            noCityText.visibility = View.GONE
            categoriesRecycler.visibility = View.VISIBLE
            startCategoryCounts(key)
        } else {
            cityText.text = ""
            noCityText.text = getString(R.string.cl_layover_info_no_city)
            noCityText.visibility = View.VISIBLE
            categoriesRecycler.visibility = View.GONE
            removeCategoryListeners()
            updateDebugBanner("No city selected")
        }
    }

    private fun startCategoryCounts(cityKey: String) {
        val sanitizedKey = LayoverInfoCategoryStore.sanitizeCityKey(cityKey)
        if (sanitizedKey.isBlank()) return
        if (sanitizedKey == observedCityKey && categoryListeners.isNotEmpty()) return

        removeCategoryListeners()
        observedCityKey = sanitizedKey
        categoryCounts.clear()
        adapter.submitCounts(categoryCounts)

        LayoverInfoCategory.entries.forEach { category ->
            val ref = dbRoot.child("layover_info").child(category.key).child(sanitizedKey)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val count = snapshot.childrenCount.toInt()
                    categoryCounts[category.key] = count
                    adapter.submitCounts(categoryCounts)
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            ref.addValueEventListener(listener)
            categoryListeners[category] = Pair(ref, listener)
        }
    }

    private fun removeCategoryListeners() {
        if (observedCityKey.isBlank() || categoryListeners.isEmpty()) return
        categoryListeners.values.forEach { (ref, listener) ->
            ref.removeEventListener(listener)
        }
        categoryListeners.clear()
        observedCityKey = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        removeCategoryListeners()
        CrewLocationManager.shared.stop(locationListener)
    }

    private fun displayCity(): String {
        return manualCityOverride.ifBlank { detectedCityName }
    }

    private fun selectedCityKey(): String {
        if (manualCityOverride.isNotBlank()) {
            return manualCityKeyOverride.ifBlank { LayoverInfoCategoryStore.sanitizeCityKey(manualCityOverride) }
        }
        return detectedCityKey
    }

    private fun updateDebugBanner(message: String) {
        if (!isDebuggable()) return
        debugBanner.text = message
        debugBanner.visibility = View.VISIBLE
    }

    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
