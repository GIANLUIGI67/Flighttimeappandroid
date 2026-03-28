package it.grg.flighttimeapp.crewl

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R
import java.util.Locale

class LayoverInfoCategoryActivity : AppCompatActivity() {

    private lateinit var backBtn: ImageButton
    private lateinit var titleText: TextView
    private lateinit var cityText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var addBtn: Button

    private lateinit var adapter: LayoverInfoItemAdapter
    private lateinit var store: LayoverInfoCategoryStore
    private var cityName: String = ""
    private var cityKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layover_info_category)

        val categoryKey = intent.getStringExtra(EXTRA_CATEGORY) ?: LayoverInfoCategory.TRANSPORT.key
        cityName = intent.getStringExtra(EXTRA_CITY_NAME) ?: ""
        cityKey = intent.getStringExtra(EXTRA_CITY_KEY) ?: cityName
        val category = LayoverInfoCategory.fromKey(categoryKey)

        backBtn = findViewById(R.id.layoverInfoCategoryBack)
        titleText = findViewById(R.id.layoverInfoCategoryTitle)
        cityText = findViewById(R.id.layoverInfoCategoryCity)
        emptyText = findViewById(R.id.layoverInfoEmptyText)
        recycler = findViewById(R.id.layoverInfoItemsRecycler)
        addBtn = findViewById(R.id.layoverInfoAddBtn)

        backBtn.setOnClickListener { finish() }
        titleText.setText(category.labelResId)
        cityText.text = getString(R.string.my_layover_info_city_format, cityName)

        store = LayoverInfoCategoryStore(category, cityName, cityKey)
        store.start()

        adapter = LayoverInfoItemAdapter(
            emptyList(),
            { item -> store.canDelete(item) },
            { item -> store.deleteItem(item) },
            { item -> showEditDialog(item) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        store.items.observe(this, Observer { list ->
            adapter.submit(list)
            emptyText.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        })

        addBtn.setOnClickListener {
            showAddDialog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        store.stop()
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_layover_info, null)
        val titleInput = view.findViewById<EditText>(R.id.layoverInfoTitleInput)
        val locationInput = view.findViewById<EditText>(R.id.layoverInfoLocationInput)
        val locationBtn = view.findViewById<ImageButton>(R.id.layoverInfoLocationBtn)
        val cityInput = view.findViewById<EditText>(R.id.layoverInfoCityInput)
        val cityBtn = view.findViewById<ImageButton>(R.id.layoverInfoCityBtn)
        val detailsInput = view.findViewById<EditText>(R.id.layoverInfoDetailsInput)

        cityInput.setText(cityName)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_layover_info_add_button))
            .setView(view)
            .setPositiveButton(getString(R.string.cl_layover_info_save)) { _, _ ->
                val cityValue = cityInput.text.toString().trim()
                resolveCityKeyFromName(cityValue) { keyOverride ->
                    store.addItem(
                        titleInput.text.toString(),
                        detailsInput.text.toString(),
                        locationInput.text.toString(),
                        cityValue,
                        keyOverride
                    )
                }
            }
            .setNegativeButton(getString(R.string.cl_layover_info_cancel), null)
            .show()

        locationBtn.setOnClickListener {
            fillLocationFromDevice(locationInput)
        }
        cityBtn.setOnClickListener {
            fillCityFromDevice(cityInput)
        }
    }

    private fun showEditDialog(item: LayoverInfoItem) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_layover_info, null)
        val titleInput = view.findViewById<EditText>(R.id.layoverInfoTitleInput)
        val locationInput = view.findViewById<EditText>(R.id.layoverInfoLocationInput)
        val locationBtn = view.findViewById<ImageButton>(R.id.layoverInfoLocationBtn)
        val cityInput = view.findViewById<EditText>(R.id.layoverInfoCityInput)
        val cityBtn = view.findViewById<ImageButton>(R.id.layoverInfoCityBtn)
        val detailsInput = view.findViewById<EditText>(R.id.layoverInfoDetailsInput)

        titleInput.setText(item.title)
        locationInput.setText(item.location)
        cityInput.setText(item.cityName)
        detailsInput.setText(item.details)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_layover_info_edit))
            .setView(view)
            .setPositiveButton(getString(R.string.cl_layover_info_save)) { _, _ ->
                val cityValue = cityInput.text.toString().trim()
                resolveCityKeyFromName(cityValue) { keyOverride ->
                    store.updateItem(
                        item,
                        titleInput.text.toString(),
                        detailsInput.text.toString(),
                        locationInput.text.toString(),
                        cityValue,
                        keyOverride
                    )
                }
            }
            .setNegativeButton(getString(R.string.cl_layover_info_cancel), null)
            .show()

        locationBtn.setOnClickListener {
            fillLocationFromDevice(locationInput)
        }
        cityBtn.setOnClickListener {
            fillCityFromDevice(cityInput)
        }
    }

    private fun fillLocationFromDevice(target: EditText) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            showLocationRequiredDialog()
            return
        }

        CrewLocationManager.shared.start(this) { loc ->
            val geocoder = Geocoder(this, Locale.getDefault())
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { list ->
                    val placemark = list.firstOrNull()
                    val address = listOf(
                        placemark?.getAddressLine(0),
                        placemark?.thoroughfare,
                        placemark?.locality
                    ).filterNotNull().joinToString(", ")
                    runOnUiThread {
                        if (address.isNotBlank()) {
                            target.setText(address)
                        }
                    }
                    CrewLocationManager.shared.stop()
                }
            } else {
                Thread {
                    val address = try {
                        @Suppress("DEPRECATION")
                        val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                        val placemark = list?.firstOrNull()
                        listOf(
                            placemark?.getAddressLine(0),
                            placemark?.thoroughfare,
                            placemark?.locality
                        ).filterNotNull().joinToString(", ")
                    } catch (_: Exception) {
                        ""
                    }
                    runOnUiThread {
                        if (address.isNotBlank()) {
                            target.setText(address)
                        }
                    }
                    CrewLocationManager.shared.stop()
                }.start()
            }
        }
    }

    private fun fillCityFromDevice(target: EditText) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            showLocationRequiredDialog()
            return
        }

        CrewLocationManager.shared.start(this) { loc ->
            val geocoder = Geocoder(this, Locale.getDefault())
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { list ->
                    val placemark = list.firstOrNull()
                    val city = placemark?.locality ?: placemark?.adminArea ?: placemark?.countryName
                    runOnUiThread {
                        if (!city.isNullOrBlank()) {
                            target.setText(city)
                        }
                    }
                    CrewLocationManager.shared.stop()
                }
            } else {
                Thread {
                    val city = try {
                        @Suppress("DEPRECATION")
                        val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                        val placemark = list?.firstOrNull()
                        placemark?.locality ?: placemark?.adminArea ?: placemark?.countryName
                    } catch (_: Exception) {
                        null
                    }
                    runOnUiThread {
                        if (!city.isNullOrBlank()) {
                            target.setText(city)
                        }
                    }
                    CrewLocationManager.shared.stop()
                }.start()
            }
        }
    }

    private fun showLocationRequiredDialog() {
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

    companion object {
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_CITY_NAME = "extra_city_name"
        const val EXTRA_CITY_KEY = "extra_city_key"
    }
}
