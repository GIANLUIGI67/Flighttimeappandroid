package it.grg.flighttimeapp.crewl

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R
import kotlin.math.round

class CrewNearbyActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private val store = CrewLayoverStore.shared
    private lateinit var adapter: CrewNearbyAdapter
    private lateinit var distanceValue: TextView
    private lateinit var distanceToggle: SwitchCompat
    private lateinit var distanceSeek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crew_nearby)

        store.init(this)

        findViewById<ImageButton>(R.id.nearbyBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.nearbyRefresh).setOnClickListener {
            store.refreshNow()
        }
        distanceValue = findViewById(R.id.distanceValue)
        distanceToggle = findViewById(R.id.distanceUnlimitedToggle)
        distanceSeek = findViewById(R.id.distanceSeekBar)

        distanceSeek.max = SEEK_MAX
        val initialUnlimited = store.getDistanceUnlimited()
        val initialMaxKm = store.getDistanceMaxKm()
        distanceToggle.isChecked = initialUnlimited
        distanceSeek.progress = progressFromKm(initialMaxKm)
        applyDistanceUi(initialUnlimited, initialMaxKm)

        distanceToggle.setOnCheckedChangeListener { _, isChecked ->
            val currentKm = kmFromProgress(distanceSeek.progress)
            applyDistanceUi(isChecked, currentKm)
            store.setDistanceFilter(isChecked, currentKm)
        }

        distanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (distanceToggle.isChecked) {
                    distanceToggle.isChecked = false
                }
                val km = kmFromProgress(progress)
                applyDistanceUi(false, km)
                store.setDistanceFilter(false, km)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        recycler = findViewById(R.id.nearbyRecycler)
        val gridLayout = GridLayoutManager(this, 2)
        gridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    2 -> 1
                    else -> 2
                }
            }
        }
        recycler.layoutManager = gridLayout
        adapter = CrewNearbyAdapter(
            emptyList(),
            onClick = { user ->
                val intent = Intent(this, CrewChatActivity::class.java).apply {
                    putExtra(CrewChatActivity.EXTRA_PEER_ID, user.userId)
                    putExtra(CrewChatActivity.EXTRA_PEER_NAME, user.nickname)
                    putExtra(CrewChatActivity.EXTRA_PEER_COMPANY, user.companyName ?: "")
                }
                startActivity(intent)
            },
            onPhotoClick = { user, index ->
                showPhotoPreview(user, index)
            }
        )
        recycler.adapter = adapter

        val emptyText = findViewById<TextView>(R.id.nearbyEmptyText)

        store.onlineNow.observe(this, Observer { online ->
            val last24 = store.activeLast24h.value ?: emptyList()
            val items = buildItems(online, last24)
            adapter.submit(items)
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        })
        store.activeLast24h.observe(this, Observer { last24 ->
            val online = store.onlineNow.value ?: emptyList()
            val items = buildItems(online, last24)
            adapter.submit(items)
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        })
    }

    private fun buildItems(online: List<NearbyCrewUser>, last24: List<NearbyCrewUser>): List<CrewNearbyAdapter.NearbyItem> {
        val items = mutableListOf<CrewNearbyAdapter.NearbyItem>()
        if (online.isNotEmpty()) {
            val onlineTitle = getString(R.string.cl_online_now) + " (${online.size})"
            addSection(items, onlineTitle, online, R.color.green_ok)
        }
        if (last24.isNotEmpty()) {
            addSection(items, getString(R.string.cl_active_last_24h), last24, R.color.iosHint)
        }
        return items
    }

    private fun addSection(
        items: MutableList<CrewNearbyAdapter.NearbyItem>,
        title: String,
        users: List<NearbyCrewUser>,
        dotColorRes: Int
    ) {
        if (users.isEmpty()) return
        items.add(CrewNearbyAdapter.NearbyItem.Header(title, dotColorRes))
        val (withPhoto, withoutPhoto) = users.partition { hasPhoto(it) }
        withPhoto.forEach { items.add(CrewNearbyAdapter.NearbyItem.MosaicUser(it)) }
        withoutPhoto.forEach { items.add(CrewNearbyAdapter.NearbyItem.User(it)) }
    }

    private fun hasPhoto(user: NearbyCrewUser): Boolean {
        return user.photosB64.isNotEmpty() || !user.photoB64.isNullOrBlank()
    }

    private fun applyDistanceUi(isUnlimited: Boolean, maxKm: Double) {
        distanceSeek.isEnabled = !isUnlimited
        distanceSeek.alpha = if (isUnlimited) 0.4f else 1.0f
        distanceValue.text = if (isUnlimited) "∞" else getString(R.string.cl_distance_km_format, maxKm.toInt())
    }

    private fun showPhotoPreview(user: NearbyCrewUser, initialIndex: Int) {
        val photos = if (user.photosB64.isNotEmpty()) {
            user.photosB64
        } else {
            user.photoB64?.let { listOf(it) } ?: emptyList()
        }
        if (photos.isEmpty()) return
        val avatarB64 = user.photoB64 ?: photos.firstOrNull()
        CrewPhotoPreviewDialog.show(
            fragmentManager = supportFragmentManager,
            ownerUid = user.userId,
            photosB64 = ArrayList(photos),
            initialIndex = initialIndex,
            threadId = null,
            messageId = null,
            avatarB64 = avatarB64,
            peerName = user.nickname,
            peerCompany = user.companyName ?: ""
        )
    }

    private fun kmFromProgress(progress: Int): Double {
        val slider = progress.toDouble() / SEEK_MAX.toDouble()
        val km = if (slider <= NEAR_RATIO) {
            (slider / NEAR_RATIO) * NEAR_MAX_KM
        } else {
            NEAR_MAX_KM + ((slider - NEAR_RATIO) / (1.0 - NEAR_RATIO)) * (TOTAL_MAX_KM - NEAR_MAX_KM)
        }
        return round(km).coerceIn(0.0, TOTAL_MAX_KM)
    }

    private fun progressFromKm(km: Double): Int {
        val clampedKm = km.coerceIn(0.0, TOTAL_MAX_KM)
        val slider = if (clampedKm <= NEAR_MAX_KM) {
            (clampedKm / NEAR_MAX_KM) * NEAR_RATIO
        } else {
            NEAR_RATIO + ((clampedKm - NEAR_MAX_KM) / (TOTAL_MAX_KM - NEAR_MAX_KM)) * (1.0 - NEAR_RATIO)
        }
        return (slider * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
    }

    private companion object {
        const val SEEK_MAX = 1000
        const val NEAR_MAX_KM = 100.0
        const val TOTAL_MAX_KM = 5000.0
        const val NEAR_RATIO = 0.6
    }
}
