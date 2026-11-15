package it.grg.flighttimeapp.aletheia

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import it.grg.flighttimeapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AletheiaSettingsActivity : AppCompatActivity() {
    private lateinit var repo: AletheiaRepository
    private lateinit var store: AletheiaLocalModelStore
    private lateinit var consentSwitch: SwitchMaterial
    private lateinit var modelStatus: TextView
    private lateinit var usageLabel: TextView
    private lateinit var importButton: MaterialButton
    private lateinit var importProgress: ProgressBar
    private var consentJob: Job? = null
    private var nativeAvailable: Boolean? = null

    private val consentListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        consentJob?.cancel()
        consentJob = lifecycleScope.launch {
            try {
                repo.setConsent(isChecked, AletheiaRepository.SOURCE_SETTINGS)
                Toast.makeText(
                    this@AletheiaSettingsActivity,
                    if (isChecked) R.string.aletheia_consent_yes_toast else R.string.aletheia_consent_no_toast,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                // Detach before revert so the listener cannot re-enter on a failed sync.
                restoreSwitch(repo.prefs.trainingConsentGranted && repo.prefs.consentAnswered)
                Toast.makeText(
                    this@AletheiaSettingsActivity,
                    getString(
                        R.string.aletheia_consent_sync_failed,
                        e.message ?: getString(R.string.aletheia_error_generic)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aletheia_settings)
        repo = AletheiaRepository.create(this)
        store = AletheiaLocalModelStore.fromContext(this)

        findViewById<ImageButton>(R.id.aletheiaSettingsBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.aletheiaSettingsVersion).text =
            getString(R.string.aletheia_version_format, AletheiaFeature.versionName)

        consentSwitch = findViewById(R.id.aletheiaConsentSwitch)
        restoreSwitch(repo.prefs.trainingConsentGranted && repo.prefs.consentAnswered)
        consentSwitch.setOnCheckedChangeListener(consentListener)

        usageLabel = findViewById(R.id.aletheiaUsageLabel)
        modelStatus = findViewById(R.id.aletheiaOfflineModelStatus)
        importProgress = findViewById(R.id.aletheiaModelImportProgress)
        importButton = findViewById(R.id.aletheiaImportModel)
        importButton.setOnClickListener {
            // GGUF has no registered MIME type: let the user pick any file, the store checks it.
            pickModel.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.aletheiaRescanModel).setOnClickListener {
            store.rescan()
            refreshModelStatus()
        }
        findViewById<MaterialButton>(R.id.aletheiaDeleteMyData).setOnClickListener { confirmDelete() }

        refreshUsage()
        refreshModelStatus()
        lifecycleScope.launch {
            nativeAvailable = runCatching { AletheiaLlamaRuntime.get().probeNative() }.getOrNull()
            refreshModelStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
        lifecycleScope.launch {
            repo.refreshStatus()
            restoreSwitch(repo.prefs.trainingConsentGranted && repo.prefs.consentAnswered)
            refreshUsage()
        }
    }

    private fun refreshUsage() {
        val p = repo.prefs
        usageLabel.text = if (!p.statusFromServer) {
            getString(R.string.aletheia_usage_not_synced)
        } else {
            getString(
                R.string.aletheia_usage_format,
                planLabel(p.serverPlan),
                p.lastDailyUsed,
                p.cachedDailyLimit,
                p.cachedCreditsRemaining
            )
        }
    }

    private fun planLabel(plan: String): String = getString(
        when (AletheiaUiRules.planName(plan)) {
            AletheiaUiRules.PlanName.FREE -> R.string.aletheia_plan_name_free
            AletheiaUiRules.PlanName.PLUS -> R.string.aletheia_plan_name_plus
            AletheiaUiRules.PlanName.PRO -> R.string.aletheia_plan_name_pro
            AletheiaUiRules.PlanName.MAX -> R.string.aletheia_plan_name_max
        }
    )

    private fun refreshModelStatus() {
        modelStatus.text = getString(
            when (AletheiaUiRules.modelStatus(store.installedModel(), nativeAvailable)) {
                AletheiaUiRules.ModelStatus.READY_VERIFIED -> R.string.aletheia_model_status_ready_verified
                AletheiaUiRules.ModelStatus.READY_UNVERIFIED -> R.string.aletheia_model_status_ready_unverified
                AletheiaUiRules.ModelStatus.MISSING -> R.string.aletheia_model_status_missing
                AletheiaUiRules.ModelStatus.ENGINE_UNAVAILABLE -> R.string.aletheia_model_status_engine_unavailable
            }
        )
    }

    private fun importModel(uri: Uri) {
        val name = displayName(uri)
        importButton.isEnabled = false
        importProgress.visibility = View.VISIBLE
        modelStatus.setText(R.string.aletheia_model_importing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val input = contentResolver.openInputStream(uri)
                        ?: return@withContext AletheiaLocalModelStore.ImportResult.Failed("open")
                    store.import(name, input)
                } catch (t: Throwable) {
                    AletheiaLocalModelStore.ImportResult.Failed(t.message ?: t.javaClass.simpleName)
                }
            }
            importButton.isEnabled = true
            importProgress.visibility = View.GONE
            val message = when (AletheiaUiRules.importOutcome(result)) {
                AletheiaUiRules.ImportOutcome.OK_VERIFIED -> getString(R.string.aletheia_model_import_ok_verified)
                AletheiaUiRules.ImportOutcome.OK_UNVERIFIED -> getString(R.string.aletheia_model_import_ok_unverified)
                AletheiaUiRules.ImportOutcome.REJECTED_NAME -> getString(R.string.aletheia_model_import_rejected_name)
                AletheiaUiRules.ImportOutcome.REJECTED_FORMAT -> getString(R.string.aletheia_model_import_rejected_format)
                AletheiaUiRules.ImportOutcome.FAILED -> getString(
                    R.string.aletheia_model_import_failed,
                    (result as? AletheiaLocalModelStore.ImportResult.Failed)?.reason.orEmpty()
                )
            }
            Toast.makeText(this@AletheiaSettingsActivity, message, Toast.LENGTH_LONG).show()
            refreshModelStatus()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.aletheia_delete_confirm_title)
            .setMessage(R.string.aletheia_delete_confirm_body)
            .setPositiveButton(R.string.aletheia_delete_confirm_yes) { _, _ -> runDelete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runDelete() {
        if (!repo.hasNetwork()) {
            showResult(getString(R.string.aletheia_delete_needs_network))
            return
        }
        lifecycleScope.launch {
            val text = try {
                val result = repo.deleteMyData()
                restoreSwitch(false)
                when (AletheiaUiRules.deleteOutcome(result)) {
                    AletheiaUiRules.DeleteOutcome.ALREADY_DELETED -> getString(R.string.aletheia_delete_already)
                    AletheiaUiRules.DeleteOutcome.DONE -> getString(
                        R.string.aletheia_delete_done,
                        result.turnsTombstoned,
                        result.feedbackTombstoned,
                        result.cachedAnswersDeleted
                    )
                }
            } catch (e: Exception) {
                getString(R.string.aletheia_delete_failed, e.message ?: getString(R.string.aletheia_error_generic))
            }
            showResult(text)
        }
    }

    private fun showResult(text: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.aletheia_delete_section)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun restoreSwitch(checked: Boolean) {
        consentSwitch.setOnCheckedChangeListener(null)
        consentSwitch.isChecked = checked
        consentSwitch.setOnCheckedChangeListener(consentListener)
    }
}
