package it.grg.flighttimeapp.crewl

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
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
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CrewNearbyAdapter(emptyList()) { user ->
            val intent = Intent(this, CrewChatActivity::class.java).apply {
                putExtra(CrewChatActivity.EXTRA_PEER_ID, user.userId)
                putExtra(CrewChatActivity.EXTRA_PEER_NAME, user.nickname)
                putExtra(CrewChatActivity.EXTRA_PEER_COMPANY, user.companyName ?: "")
            }
            startActivity(intent)
        }
        recycler.adapter = adapter

        store.onlineNow.observe(this, Observer { online ->
            val last24 = store.activeLast24h.value ?: emptyList()
            adapter.submit(buildItems(online, last24))
        })
        store.activeLast24h.observe(this, Observer { last24 ->
            val online = store.onlineNow.value ?: emptyList()
            adapter.submit(buildItems(online, last24))
        })
    }

    private fun buildItems(online: List<NearbyCrewUser>, last24: List<NearbyCrewUser>): List<CrewNearbyAdapter.NearbyItem> {
        val items = mutableListOf<CrewNearbyAdapter.NearbyItem>()
        if (online.isNotEmpty()) {
            items.add(CrewNearbyAdapter.NearbyItem.Header(getString(R.string.cl_online_now)))
            online.forEach { items.add(CrewNearbyAdapter.NearbyItem.User(it)) }
        }
        if (last24.isNotEmpty()) {
            items.add(CrewNearbyAdapter.NearbyItem.Header(getString(R.string.cl_active_last_24h)))
            last24.forEach { items.add(CrewNearbyAdapter.NearbyItem.User(it)) }
        }
        return items
    }

    private fun applyDistanceUi(isUnlimited: Boolean, maxKm: Double) {
        distanceSeek.isEnabled = !isUnlimited
        distanceSeek.alpha = if (isUnlimited) 0.4f else 1.0f
        distanceValue.text = if (isUnlimited) "∞" else getString(R.string.cl_distance_km_format, maxKm.toInt())
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
