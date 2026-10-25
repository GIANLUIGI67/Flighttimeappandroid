package it.grg.flighttimeapp.aletheia

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import it.grg.flighttimeapp.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AletheiaSettingsActivity : AppCompatActivity() {
    private lateinit var repo: AletheiaRepository
    private lateinit var installer: AletheiaModelInstaller
    private lateinit var consentSwitch: SwitchMaterial
    private lateinit var modelStatus: TextView
    private var consentJob: Job? = null
    private var updatingSwitchProgrammatically = false

    private val consentListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (updatingSwitchProgrammatically) return@OnCheckedChangeListener
        consentJob?.cancel()
        consentJob = lifecycleScope.launch {
            try {
                repo.setConsent(isChecked)
                Toast.makeText(
                    this@AletheiaSettingsActivity,
                    if (isChecked) R.string.aletheia_consent_yes_toast else R.string.aletheia_consent_no_toast,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                restoreSwitch(repo.prefs.trainingConsentGranted && repo.prefs.consentAnswered)
                Toast.makeText(
                    this@AletheiaSettingsActivity,
                    e.message ?: getString(R.string.aletheia_error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aletheia_settings)
        repo = AletheiaRepository(this)
        installer = AletheiaModelInstaller(this)

        findViewById<ImageButton>(R.id.aletheiaSettingsBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.aletheiaSettingsVersion).text =
            getString(R.string.aletheia_version_format, AletheiaFeature.versionName)

        consentSwitch = findViewById(R.id.aletheiaConsentSwitch)
        restoreSwitch(repo.prefs.trainingConsentGranted && repo.prefs.consentAnswered)
        consentSwitch.setOnCheckedChangeListener(consentListener)

        modelStatus = findViewById(R.id.aletheiaOfflineModelStatus)
        findViewById<MaterialButton>(R.id.aletheiaDownloadModel).setOnClickListener {
            if (installer.isInstalled()) {
                Toast.makeText(this, R.string.aletheia_download_already, Toast.LENGTH_SHORT).show()
                refreshModelStatus()
                return@setOnClickListener
            }
            val id = installer.enqueueDownload(wifiOnly = true)
            Toast.makeText(
                this,
                if (id >= 0) R.string.aletheia_download_started else R.string.aletheia_download_failed,
                Toast.LENGTH_LONG
            ).show()
            refreshModelStatus()
        }
        refreshModelStatus()
    }

    override fun onResume() {
        super.onResume()
        installer.promoteIfDownloaded()
        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val text = when {
            installer.isInstalled() ->
                getString(R.string.aletheia_model_installed, installer.modelFile().absolutePath)
            else -> {
                val progress = installer.downloadProgress()
                if (progress != null && progress.second > 0) {
                    val pct = ((progress.first * 100) / progress.second).toInt()
                    getString(R.string.aletheia_model_missing) + "\nDownload: $pct%"
                } else {
                    getString(R.string.aletheia_model_missing)
                }
            }
        }
        modelStatus.text = text
    }

    private fun restoreSwitch(checked: Boolean) {
        updatingSwitchProgrammatically = true
        consentSwitch.isChecked = checked
        updatingSwitchProgrammatically = false
    }
}
