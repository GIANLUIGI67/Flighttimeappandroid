package it.grg.flighttimeapp.crewl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer
import it.grg.flighttimeapp.R
import it.grg.flighttimeapp.training.DonateActivity

class CrewLayoverActivity : AppCompatActivity() {

    private lateinit var bannerText: TextView
    private lateinit var onlineNearbyText: TextView
    private lateinit var nearMeBtn: android.view.View
    private lateinit var settingsBtn: android.view.View
    private lateinit var myLayoverInfoBtn: android.view.View
    private lateinit var shareBtn: android.widget.Button
    private lateinit var donateBtn: android.widget.Button
    private lateinit var communityWarning: TextView

    private val store = CrewLayoverStore.shared

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crew_layover)

        bannerText = findViewById(R.id.bannerText)
        onlineNearbyText = findViewById(R.id.onlineNearbyText)
        nearMeBtn = findViewById(R.id.nearMeBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        myLayoverInfoBtn = findViewById(R.id.myLayoverInfoBtn)
        shareBtn = findViewById(R.id.shareBtn)
        donateBtn = findViewById(R.id.donateBtn)
        communityWarning = findViewById(R.id.communityProfileWarning)

        store.init(this)

        nearMeBtn.setOnClickListener {
            startActivity(Intent(this, CrewNearbyActivity::class.java))
        }
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, CrewSettingsActivity::class.java))
        }
        myLayoverInfoBtn.setOnClickListener {
            if (hasLocationPermission()) {
                startActivity(Intent(this, MyLayoverInfoActivity::class.java))
            } else {
                showLocationRequiredDialog()
            }
        }
        shareBtn.setOnClickListener {
            val text = getString(R.string.cl_share_sheet_text)
            val url = "https://apps.apple.com/app/id6756632781"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$text\n$url")
            }
            startActivity(Intent.createChooser(intent, getString(R.string.cl_share_button)))
        }
        donateBtn.setOnClickListener {
            startActivity(Intent(this, DonateActivity::class.java))
        }

        store.onlineNearbyCount.observe(this, Observer { count ->
            onlineNearbyText.text = getString(R.string.cl_online_nearby_format, count)
        })

        store.settingsLive.observe(this, Observer {
            updateCommunityWarning()
        })

        store.hasIncomingInvitation.observe(this, Observer { hasInvite ->
            if (hasInvite && notificationsEnabled()) {
                CrewBannerCenter.shared.show(getString(R.string.cl_new_event_invite_banner))
                updateBanner()
            }
        })

        ensureNotificationPermission()
        ensureSignedInAndStart()
    }

    override fun onResume() {
        super.onResume()
        updateBanner()
        updateCommunityWarning()
    }

    private fun updateBanner() {
        val banner = CrewBannerCenter.shared.current
        if (banner != null) {
            bannerText.text = banner.message
            bannerText.visibility = android.view.View.VISIBLE
        } else {
            bannerText.visibility = android.view.View.GONE
        }
    }

    private fun updateCommunityWarning() {
        val ready = isProfileReady()
        communityWarning.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun isProfileReady(): Boolean {
        val s = store.settingsLive.value
        val nickname = s?.nickname?.trim().orEmpty()
        val hasPhoto = CrewPhotoLoader.shared.myLocalProfileImage() != null
        return nickname.isNotEmpty() && hasPhoto && s?.role != null
    }

    private fun ensureSignedInAndStart() {
        CrewAuthManager.ensureSignedIn { uid ->
            if (uid.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.cl_auth_failed), Toast.LENGTH_SHORT).show()
                return@ensureSignedIn
            }
            CrewPresenceService.shared.start(uid)
            store.start(uid)
            store.refreshNow()
            ensureLocationPermission()
        }
    }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            CrewLocationManager.shared.start(this) { loc ->
                store.updateMyLocation(loc)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
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

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val status = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (status != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    private fun notificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                CrewLocationManager.shared.start(this) { loc ->
                    store.updateMyLocation(loc)
                }
            }
        }
        if (requestCode == REQ_NOTIFICATIONS) {
            updateBanner()
        }
    }

    companion object {
        private const val REQ_LOCATION = 5001
        private const val REQ_NOTIFICATIONS = 5002
    }
}
