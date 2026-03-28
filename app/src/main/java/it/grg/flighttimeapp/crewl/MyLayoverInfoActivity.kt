package it.grg.flighttimeapp.crewl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.provider.Settings
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

    private lateinit var adapter: LayoverInfoCategoryAdapter

    private var currentCityName: String = ""
    private var detectedCityName: String = ""
    private var detectedCityKey: String = ""
    private var currentCityKey: String = ""
    private var isManualCityOverride: Boolean = false
    private var observedCityKey: String = ""
    private val categoryCounts: MutableMap<String, Int> = mutableMapOf()
    private val categoryListeners: MutableMap<LayoverInfoCategory, Pair<DatabaseReference, ValueEventListener>> = mutableMapOf()
    private val dbRoot: DatabaseReference by lazy { FirebaseDatabase.getInstance().reference }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_layover_info)

        backBtn = findViewById(R.id.layoverInfoBack)
        cityText = findViewById(R.id.layoverInfoCityText)
        noCityText = findViewById(R.id.layoverInfoNoCity)
        categoriesRecycler = findViewById(R.id.layoverInfoRecycler)

        backBtn.setOnClickListener { finish() }
        cityText.setOnClickListener {
            if (currentCityName.isBlank() && detectedCityName.isBlank()) return@setOnClickListener
            showCityEditor()
        }

        adapter = LayoverInfoCategoryAdapter(LayoverInfoCategory.entries.toList()) { category ->
            if (currentCityName.isBlank() || currentCityKey.isBlank()) return@LayoverInfoCategoryAdapter
            val i = Intent(this, LayoverInfoCategoryActivity::class.java).apply {
                putExtra(LayoverInfoCategoryActivity.EXTRA_CATEGORY, category.key)
                putExtra(LayoverInfoCategoryActivity.EXTRA_CITY_NAME, currentCityName)
                putExtra(LayoverInfoCategoryActivity.EXTRA_CITY_KEY, currentCityKey)
            }
            startActivity(i)
        }
        categoriesRecycler.layoutManager = LinearLayoutManager(this)
        categoriesRecycler.adapter = adapter

        updateCityUI("", isLoading = true)
        ensureLocationAndLoadCity()
    }

    private fun ensureLocationAndLoadCity() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            CrewLocationManager.shared.start(this) { loc ->
                resolveCity(loc)
            }
        } else {
            showLocationRequiredDialog()
        }
    }

    private fun showLocationRequiredDialog() {
        updateCityUI("", isLoading = false)
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
        val displayGeocoder = Geocoder(this, Locale.getDefault())
        val keyGeocoder = Geocoder(this, Locale.US)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            displayGeocoder.getFromLocation(location.latitude, location.longitude, 1) { displayList ->
                val displayName = displayList.firstOrNull()?.locality
                    ?: displayList.firstOrNull()?.adminArea
                    ?: displayList.firstOrNull()?.countryName

                keyGeocoder.getFromLocation(location.latitude, location.longitude, 1) { keyList ->
                    val keyCity = keyList.firstOrNull()?.locality
                        ?: keyList.firstOrNull()?.adminArea
                        ?: keyList.firstOrNull()?.countryName
                    val countryCode = keyList.firstOrNull()?.countryCode
                        ?: displayList.firstOrNull()?.countryCode
                        ?: ""
                    val rawKey = listOf(countryCode, keyCity).filter { !it.isNullOrBlank() }.joinToString("_")
                    val stableKey = LayoverInfoCategoryStore.sanitizeCityKey(rawKey)

                    runOnUiThread {
                    if (!displayName.isNullOrBlank() && stableKey.isNotBlank()) {
                        detectedCityName = displayName
                        detectedCityKey = stableKey
                        if (!isManualCityOverride) {
                            currentCityName = displayName
                            currentCityKey = stableKey
                        }
                        updateCityUI(currentCityName, isLoading = false)
                    } else {
                        currentCityName = ""
                        detectedCityName = ""
                        detectedCityKey = ""
                        currentCityKey = ""
                        updateCityUI("", isLoading = false)
                    }
                    }
                }
            }
        } else {
            Thread {
                val displayName = try {
                    @Suppress("DEPRECATION")
                    val list = displayGeocoder.getFromLocation(location.latitude, location.longitude, 1)
                    list?.firstOrNull()?.locality
                        ?: list?.firstOrNull()?.adminArea
                        ?: list?.firstOrNull()?.countryName
                } catch (_: Exception) {
                    null
                }

                val keyData = try {
                    @Suppress("DEPRECATION")
                    val list = keyGeocoder.getFromLocation(location.latitude, location.longitude, 1)
                    val keyCity = list?.firstOrNull()?.locality
                        ?: list?.firstOrNull()?.adminArea
                        ?: list?.firstOrNull()?.countryName
                    val countryCode = list?.firstOrNull()?.countryCode ?: ""
                    val rawKey = listOf(countryCode, keyCity).filter { !it.isNullOrBlank() }.joinToString("_")
                    LayoverInfoCategoryStore.sanitizeCityKey(rawKey)
                } catch (_: Exception) {
                    ""
                }

                runOnUiThread {
                    if (!displayName.isNullOrBlank() && keyData.isNotBlank()) {
                        detectedCityName = displayName
                        detectedCityKey = keyData
                        if (!isManualCityOverride) {
                            currentCityName = displayName
                            currentCityKey = keyData
                        }
                        updateCityUI(currentCityName, isLoading = false)
                    } else {
                        currentCityName = ""
                        detectedCityName = ""
                        detectedCityKey = ""
                        currentCityKey = ""
                        updateCityUI("", isLoading = false)
                    }
                }
            }.start()
        }
    }

    private fun showCityEditor() {
        val input = android.widget.EditText(this).apply {
            setText(currentCityName.ifBlank { detectedCityName })
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_layover_info_edit_city))
            .setMessage(getString(R.string.cl_layover_info_edit_city_message))
            .setView(input)
            .setPositiveButton(getString(R.string.cl_layover_info_save)) { _, _ ->
                val value = input.text.toString().trim()
                val targetName = if (value.isBlank()) detectedCityName else value
                isManualCityOverride = true
                resolveCityKeyFromName(targetName) { keyOverride ->
                    currentCityName = targetName
                    currentCityKey = keyOverride ?: detectedCityKey
                    updateCityUI(currentCityName, isLoading = false)
                }
            }
            .setNeutralButton(getString(R.string.cl_layover_info_reset_city)) { _, _ ->
                currentCityName = detectedCityName
                currentCityKey = detectedCityKey
                isManualCityOverride = false
                updateCityUI(currentCityName, isLoading = false)
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

    private fun updateCityUI(cityName: String, isLoading: Boolean) {
        if (isLoading) {
            cityText.text = getString(R.string.my_layover_info_detecting)
            noCityText.visibility = View.GONE
            categoriesRecycler.visibility = View.GONE
            removeCategoryListeners()
            return
        }

        if (cityName.isNotBlank() && currentCityKey.isNotBlank()) {
            cityText.text = getString(R.string.my_layover_info_city_format, cityName)
            noCityText.visibility = View.GONE
            categoriesRecycler.visibility = View.VISIBLE
            startCategoryCounts(currentCityKey)
        } else {
            cityText.text = ""
            noCityText.text = getString(R.string.cl_layover_info_no_city)
            noCityText.visibility = View.VISIBLE
            categoriesRecycler.visibility = View.GONE
            removeCategoryListeners()
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
    }
}
