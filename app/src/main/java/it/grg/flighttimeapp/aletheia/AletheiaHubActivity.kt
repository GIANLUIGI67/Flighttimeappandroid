package it.grg.flighttimeapp.aletheia

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import it.grg.flighttimeapp.R

class AletheiaHubActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AletheiaFeature.isEnabled) {
            finish()
            return
        }
        setContentView(R.layout.activity_aletheia_hub)

        findViewById<ImageButton>(R.id.aletheiaHubBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.aletheiaHubSettings).setOnClickListener {
            startActivity(Intent(this, AletheiaSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.aletheiaVersionLabel).text =
            getString(R.string.aletheia_version_format, AletheiaFeature.versionName)

        val prefs = AletheiaPrefs(this)
        findViewById<TextView>(R.id.aletheiaQuotaLabel).text = getString(
            R.string.aletheia_quota_format,
            prefs.cachedDailyRemaining,
            AletheiaPrefs.FREE_DAILY_LIMIT,
            prefs.cachedCreditsRemaining
        )

        findViewById<MaterialCardView>(R.id.aletheiaOpenChatCard).setOnClickListener {
            startActivity(Intent(this, AletheiaChatActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.aletheiaPlansCard).setOnClickListener {
            startActivity(Intent(this, AletheiaPaywallActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = AletheiaPrefs(this)
        findViewById<TextView>(R.id.aletheiaQuotaLabel).text = getString(
            R.string.aletheia_quota_format,
            prefs.cachedDailyRemaining,
            AletheiaPrefs.FREE_DAILY_LIMIT,
            prefs.cachedCreditsRemaining
        )
    }
}
