package it.grg.flighttimeapp.crewl

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Locale

class LayoverInfoCategoryStore(
    val category: LayoverInfoCategory,
    val cityName: String,
    cityKey: String
) {
    private val root: DatabaseReference = FirebaseDatabase.getInstance().reference
    val cityKey: String = sanitizeCityKey(cityKey)
    private val ref: DatabaseReference = root.child("layover_info").child(category.key).child(cityKey)

    private val _items = MutableLiveData<List<LayoverInfoItem>>(emptyList())
    val items: LiveData<List<LayoverInfoItem>> = _items

    private var listener: ValueEventListener? = null
    private var didNormalizeCityBuckets: Boolean = false

    fun start() {
        if (listener != null) return
        ref.keepSynced(true)
        migrateLegacyItemsIfNeeded(legacyKey = sanitizeCityKey(cityName))
        migrateLegacyCategoryIfNeeded()
        normalizeCityBucketsIfNeeded()
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<LayoverInfoItem>()
                snapshot.children.forEach { cs ->
                    val dict = cs.value as? Map<*, *> ?: return@forEach
                    val title = (dict["title"] as? String) ?: ""
                    val details = (dict["details"] as? String) ?: ""
                    val location = (dict["location"] as? String) ?: ""
                    val createdAtMs = (dict["createdAtMs"] as? Long)
                        ?: (dict["createdAtMs"] as? Double)?.toLong()
                        ?: 0L
                    val creatorUid = (dict["creatorUid"] as? String) ?: ""
                    val cityKeyValue = (dict["cityKey"] as? String) ?: cityKey
                    val cityNameValue = (dict["cityName"] as? String) ?: cityName
                    val item = LayoverInfoItem(
                        id = cs.key ?: "",
                        title = title,
                        details = details,
                        location = location,
                        createdAtMs = createdAtMs,
                        creatorUid = creatorUid,
                        cityKey = cityKeyValue,
                        cityName = cityNameValue
                    )
                    list.add(item)
                }
                list.sortByDescending { it.createdAtMs }
                _items.postValue(list)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        listener = l
        ref.addValueEventListener(l)
    }

    private fun normalizeCityBucketsIfNeeded() {
        if (didNormalizeCityBuckets) return
        didNormalizeCityBuckets = true
        val geocoder = android.location.Geocoder(FirebaseApp.getInstance().applicationContext, Locale.US)
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Thread {
                    snapshot.children.forEach { cs ->
                        val dict = cs.value as? Map<*, *> ?: return@forEach
                        val cityNameValue = (dict["cityName"] as? String) ?: ""
                        val title = (dict["title"] as? String) ?: ""
                        val details = (dict["details"] as? String) ?: ""
                        val location = (dict["location"] as? String) ?: ""
                        val createdAtMs = (dict["createdAtMs"] as? Long)
                            ?: (dict["createdAtMs"] as? Double)?.toLong()
                            ?: 0L
                        val creatorUid = (dict["creatorUid"] as? String) ?: ""

                        val trimmed = cityNameValue.trim()
                        if (trimmed.isBlank()) return@forEach
                        val resolvedKey = try {
                            @Suppress("DEPRECATION")
                            val list = geocoder.getFromLocationName(trimmed, 1)
                            val location = list?.firstOrNull()
                            if (location == null) {
                                fallbackCityKey(trimmed) ?: sanitizeCityKey(trimmed)
                            } else {
                                @Suppress("DEPRECATION")
                                val keyList = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                                val placemark = keyList?.firstOrNull()
                                val keyCity = placemark?.locality ?: placemark?.adminArea ?: placemark?.countryName
                                val countryCode = placemark?.countryCode ?: ""
                                val rawKey = listOf(countryCode, keyCity).filter { !it.isNullOrBlank() }.joinToString("_")
                                sanitizeCityKey(if (rawKey.isBlank()) trimmed else rawKey)
                            }
                        } catch (_: Exception) {
                            fallbackCityKey(trimmed) ?: sanitizeCityKey(trimmed)
                        }

                        if (resolvedKey.isBlank() || resolvedKey == cityKey) return@forEach
                        val targetRef = root.child("layover_info").child(category.key).child(resolvedKey)
                        val payload = mapOf(
                            "title" to title,
                            "details" to details,
                            "location" to location,
                            "createdAtMs" to createdAtMs,
                            "creatorUid" to creatorUid,
                            "cityKey" to resolvedKey,
                            "cityName" to cityNameValue
                        )
                        targetRef.child(cs.key ?: return@forEach).setValue(payload)
                        ref.child(cs.key ?: return@forEach).removeValue()
                    }
                }.start()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun stop() {
        listener?.let { ref.removeEventListener(it) }
        listener = null
    }

    fun addItem(
        title: String,
        details: String,
        location: String,
        cityNameOverride: String?,
        cityKeyOverride: String?
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val t = title.trim()
        val d = details.trim()
        val l = location.trim()
        val cityOverride = cityNameOverride?.trim().orEmpty()
        if (t.isEmpty()) return
        val id = ref.push().key ?: return
        val overrideKey = cityKeyOverride?.trim().orEmpty()
        val targetCityKey = if (overrideKey.isBlank()) cityKey else overrideKey
        val targetRef = if (targetCityKey == cityKey) ref else root.child("layover_info").child(category.key).child(targetCityKey)
        val payload = mapOf(
            "title" to t,
            "details" to d,
            "location" to l,
            "createdAtMs" to System.currentTimeMillis(),
            "creatorUid" to uid,
            "cityKey" to targetCityKey,
            "cityName" to (if (cityOverride.isBlank()) cityName else cityOverride)
        )
        targetRef.child(id).setValue(payload)
    }

    fun updateItem(
        item: LayoverInfoItem,
        title: String,
        details: String,
        location: String,
        cityNameOverride: String?,
        cityKeyOverride: String?
    ) {
        val t = title.trim()
        val d = details.trim()
        val l = location.trim()
        val cityOverride = cityNameOverride?.trim().orEmpty()
        if (t.isEmpty()) return
        val overrideKey = cityKeyOverride?.trim().orEmpty()
        val targetCityKey = if (overrideKey.isBlank()) cityKey else overrideKey
        val targetRef = if (targetCityKey == cityKey) ref else root.child("layover_info").child(category.key).child(targetCityKey)
        val updatedCityName = if (cityOverride.isBlank()) item.cityName else cityOverride

        if (targetCityKey == cityKey) {
            val updates = mapOf(
                "title" to t,
                "details" to d,
                "location" to l,
                "cityName" to updatedCityName
            )
            ref.child(item.id).updateChildren(updates)
        } else {
            val payload = mapOf(
                "title" to t,
                "details" to d,
                "location" to l,
                "createdAtMs" to item.createdAtMs,
                "creatorUid" to item.creatorUid,
                "cityKey" to targetCityKey,
                "cityName" to updatedCityName
            )
            targetRef.child(item.id).setValue(payload)
            ref.child(item.id).removeValue()
        }
    }

    fun deleteItem(item: LayoverInfoItem) {
        if (item.id.isEmpty()) return
        ref.child(item.id).removeValue()
    }

    fun canDelete(item: LayoverInfoItem): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        if (item.creatorUid == uid) return true
        return LayoverInfoAdminConfig.adminUids.contains(uid)
    }

    companion object {
        fun fallbackCityKey(cityName: String): String? {
            val normalized = cityName.trim().lowercase(Locale.ROOT)
            if (normalized.isBlank()) return null
            val map = mapOf(
                "marsiglia" to "FR_Marseille",
                "marseille" to "FR_Marseille",
                "parigi" to "FR_Paris",
                "paris" to "FR_Paris",
                "londra" to "GB_London",
                "london" to "GB_London",
                "roma" to "IT_Rome",
                "rome" to "IT_Rome",
                "milano" to "IT_Milan",
                "milan" to "IT_Milan",
                "venezia" to "IT_Venice",
                "venice" to "IT_Venice",
                "firenze" to "IT_Florence",
                "florence" to "IT_Florence",
                "napoli" to "IT_Naples",
                "naples" to "IT_Naples",
                "torino" to "IT_Turin",
                "turin" to "IT_Turin",
                "monaco" to "DE_Munich",
                "munich" to "DE_Munich",
                "muenchen" to "DE_Munich",
                "jeddah" to "SA_Jeddah",
                "gedda" to "SA_Jeddah"
            )
            val rawKey = map[normalized] ?: return null
            return sanitizeCityKey(rawKey)
        }

        fun sanitizeCityKey(rawKey: String): String {
            val lowered = rawKey.trim().lowercase(Locale.ROOT)
            val sb = StringBuilder()
            lowered.forEach { ch ->
                when {
                    ch.isLetterOrDigit() -> sb.append(ch)
                    else -> sb.append('_')
                }
            }
            val key = sb.toString().replace("__", "_")
            return if (key.isBlank()) "unknown" else key
        }
    }

    private fun migrateLegacyItemsIfNeeded(legacyKey: String) {
        if (legacyKey == cityKey) return
        val legacyRef = root.child("layover_info").child(category.key).child(legacyKey)
        legacyRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val updates = mutableMapOf<String, Any>()
                snapshot.children.forEach { cs ->
                    val dict = cs.value as? Map<*, *> ?: return@forEach
                    updates[cs.key ?: return@forEach] = dict
                }
                if (updates.isNotEmpty()) {
                    ref.updateChildren(updates)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun migrateLegacyCategoryIfNeeded() {
        if (category.key != "warnings") return
        val legacyRef = root.child("layover_info").child("options").child(cityKey)
        legacyRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val updates = mutableMapOf<String, Any>()
                snapshot.children.forEach { cs ->
                    val dict = cs.value as? Map<*, *> ?: return@forEach
                    updates[cs.key ?: return@forEach] = dict
                }
                if (updates.isNotEmpty()) {
                    ref.updateChildren(updates)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
