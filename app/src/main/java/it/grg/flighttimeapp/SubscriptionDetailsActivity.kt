package it.grg.flighttimeapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SubscriptionDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription_details)

        findViewById<TextView>(R.id.subDetailsClose).setOnClickListener { finish() }

        findViewById<TextView>(R.id.subDetailsPrivacy).setOnClickListener {
            openUrl("https://gianluigi67.github.io/flighttime-legal/privacy.html")
        }
        findViewById<TextView>(R.id.subDetailsTerms).setOnClickListener {
            openUrl("https://gianluigi67.github.io/flighttime-legal/terms.html")
        }
        findViewById<TextView>(R.id.subDetailsGoogle).setOnClickListener {
            Toast.makeText(this, getString(R.string.sub_details_coming_soon), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_browser_app), Toast.LENGTH_SHORT).show()
        }
    }
}
