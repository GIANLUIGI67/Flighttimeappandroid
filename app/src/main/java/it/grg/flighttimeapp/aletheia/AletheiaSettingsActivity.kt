package it.grg.flighttimeapp.aletheia

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import it.grg.flighttimeapp.R
import kotlinx.coroutines.launch

class AletheiaSettingsActivity : AppCompatActivity() {
    private lateinit var repo: AletheiaRepository
    private lateinit var consentSwitch: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aletheia_settings)
        repo = AletheiaRepository(this)

        findViewById<ImageButton>(R.id.aletheiaSettingsBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.aletheiaSettingsVersion).text =
            getString(R.string.aletheia_version_format, AletheiaFeature.versionName)

        consentSwitch = findViewById(R.id.aletheiaConsentSwitch)
        consentSwitch.isChecked = repo.prefs.trainingConsentGranted && repo.prefs.consentAnswered
        consentSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                try {
                    repo.setConsent(isChecked)
                    Toast.makeText(
                        this@AletheiaSettingsActivity,
                        if (isChecked) R.string.aletheia_consent_yes_toast else R.string.aletheia_consent_no_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    consentSwitch.isChecked = !isChecked
                    Toast.makeText(
                        this@AletheiaSettingsActivity,
                        e.message ?: getString(R.string.aletheia_error_generic),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val modelStatus = if (repo.offline.isModelInstalled()) {
            getString(R.string.aletheia_model_installed, repo.offline.modelPath())
        } else {
            getString(R.string.aletheia_model_missing)
        }
        findViewById<TextView>(R.id.aletheiaOfflineModelStatus).text = modelStatus
    }
}
