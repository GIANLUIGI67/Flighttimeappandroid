package it.grg.flighttimeapp.aletheia

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import it.grg.flighttimeapp.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class AletheiaChatActivity : AppCompatActivity() {
    private lateinit var repo: AletheiaRepository
    private lateinit var adapter: AletheiaMessageAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText
    private lateinit var thinkingRow: LinearLayout
    private lateinit var thinkingIcon: ImageView
    private lateinit var subtitle: TextView
    private lateinit var modeOnline: MaterialButton
    private lateinit var modeOffline: MaterialButton

    private val messages = mutableListOf<AletheiaChatMessage>()
    private var sendJob: Job? = null
    private var thinkingAnimator: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thinkingDelayRunnable: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AletheiaFeature.isEnabled) {
            finish()
            return
        }
        setContentView(R.layout.activity_aletheia_chat)
        repo = AletheiaRepository(this)

        findViewById<ImageButton>(R.id.aletheiaChatBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.aletheiaChatSettings).setOnClickListener {
            startActivity(Intent(this, AletheiaSettingsActivity::class.java))
        }

        subtitle = findViewById(R.id.aletheiaChatSubtitle)
        modeOnline = findViewById(R.id.aletheiaModeOnline)
        modeOffline = findViewById(R.id.aletheiaModeOffline)
        thinkingRow = findViewById(R.id.aletheiaThinkingRow)
        thinkingIcon = findViewById(R.id.aletheiaThinkingIcon)
        input = findViewById(R.id.aletheiaInput)
        recycler = findViewById(R.id.aletheiaRecycler)

        adapter = AletheiaMessageAdapter { msg, rating -> promptFeedback(msg, rating) }
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        modeOnline.setOnClickListener { setOnlineMode(true) }
        modeOffline.setOnClickListener {
            setOnlineMode(false)
            maybeShowLowPowerWarning()
        }
        findViewById<ImageButton>(R.id.aletheiaSend).setOnClickListener { send() }

        setOnlineMode(repo.prefs.preferredModeOnline)
        updateSubtitle()
        maybeAskConsent()
        registerConnectivityFlush()

        lifecycleScope.launch {
            runCatching { repo.flushOutbox() }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSubtitle()
        lifecycleScope.launch {
            runCatching { repo.flushOutbox() }
        }
    }

    private fun setOnlineMode(online: Boolean) {
        repo.prefs.preferredModeOnline = online
        modeOnline.alpha = if (online) 1f else 0.55f
        modeOffline.alpha = if (online) 0.55f else 1f
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val mode = if (repo.prefs.preferredModeOnline) {
            getString(R.string.aletheia_mode_online)
        } else {
            getString(R.string.aletheia_mode_offline)
        }
        subtitle.text = getString(
            R.string.aletheia_chat_subtitle_format,
            AletheiaFeature.versionName,
            mode,
            repo.prefs.cachedDailyRemaining
        )
    }

    private fun maybeAskConsent() {
        if (repo.prefs.consentAnswered) return
        AlertDialog.Builder(this)
            .setTitle(R.string.aletheia_rl_consent_title)
            .setMessage(R.string.aletheia_rl_consent_dialog)
            .setCancelable(false)
            .setPositiveButton(R.string.aletheia_yes) { _, _ ->
                lifecycleScope.launch {
                    runCatching { repo.setConsent(true) }
                        .onFailure {
                            Toast.makeText(this@AletheiaChatActivity, it.message, Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton(R.string.aletheia_no) { _, _ ->
                lifecycleScope.launch {
                    runCatching { repo.setConsent(false) }
                        .onFailure {
                            Toast.makeText(this@AletheiaChatActivity, it.message, Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
    }

    private fun maybeShowLowPowerWarning() {
        if (repo.prefs.lowPowerWarningDismissed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.aletheia_low_power_title)
            .setMessage(R.string.aletheia_low_power_body)
            .setPositiveButton(R.string.aletheia_got_it) { _, _ ->
                repo.prefs.lowPowerWarningDismissed = true
            }
            .setCancelable(true)
            .show()
    }

    private fun send() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || sendJob?.isActive == true) return
        if (!repo.prefs.consentAnswered) {
            maybeAskConsent()
            return
        }

        input.text = null
        val userMsg = AletheiaChatMessage(
            id = UUID.randomUUID().toString(),
            role = AletheiaChatMessage.Role.USER,
            text = text
        )
        messages.add(userMsg)
        refreshList()

        scheduleThinkingIndicator()
        sendJob = lifecycleScope.launch {
            try {
                val result = if (repo.prefs.preferredModeOnline) {
                    if (!repo.hasNetwork()) {
                        Toast.makeText(
                            this@AletheiaChatActivity,
                            R.string.aletheia_offline_fallback,
                            Toast.LENGTH_SHORT
                        ).show()
                        repo.sendOffline(text)
                    } else {
                        repo.sendOnline(text)
                    }
                } else {
                    repo.sendOffline(text)
                }
                messages.add(
                    AletheiaChatMessage(
                        id = result.messageId,
                        role = AletheiaChatMessage.Role.ASSISTANT,
                        text = result.answer,
                        source = result.source
                    )
                )
                updateSubtitle()
            } catch (e: AletheiaError.DailyLimitReached) {
                startActivity(Intent(this@AletheiaChatActivity, AletheiaPaywallActivity::class.java))
            } catch (e: AletheiaError.ConsentRequired) {
                maybeAskConsent()
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                when {
                    msg.contains("limit", ignoreCase = true) || msg.contains("quota", ignoreCase = true) -> {
                        startActivity(Intent(this@AletheiaChatActivity, AletheiaPaywallActivity::class.java))
                    }
                    msg.contains("Consent", ignoreCase = true) -> {
                        Toast.makeText(
                            this@AletheiaChatActivity,
                            R.string.aletheia_consent_sync_retry,
                            Toast.LENGTH_LONG
                        ).show()
                        lifecycleScope.launch {
                            runCatching { repo.ensureConsentSyncedIfNeeded() }
                                .onFailure {
                                    Toast.makeText(
                                        this@AletheiaChatActivity,
                                        it.message ?: getString(R.string.aletheia_error_generic),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                    }
                    else -> {
                        Toast.makeText(
                            this@AletheiaChatActivity,
                            msg.ifBlank { getString(R.string.aletheia_error_generic) },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                hideThinking()
                refreshList()
            }
        }
    }

    private fun scheduleThinkingIndicator() {
        thinkingDelayRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = if (repo.prefs.preferredModeOnline) 1500L else 900L
        thinkingDelayRunnable = Runnable { showThinking() }
        mainHandler.postDelayed(thinkingDelayRunnable!!, delayMs)
    }

    private fun showThinking() {
        thinkingRow.visibility = View.VISIBLE
        thinkingAnimator?.cancel()
        thinkingAnimator = ObjectAnimator.ofFloat(thinkingIcon, View.ALPHA, 0.25f, 1f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun hideThinking() {
        thinkingDelayRunnable?.let { mainHandler.removeCallbacks(it) }
        thinkingAnimator?.cancel()
        thinkingRow.visibility = View.GONE
    }

    private fun promptFeedback(msg: AletheiaChatMessage, rating: AletheiaFeedbackRating) {
        val needsNote = repo.prefs.trainingConsentGranted &&
            (rating == AletheiaFeedbackRating.BAD || rating == AletheiaFeedbackRating.PARTIAL)
        if (!needsNote) {
            submitFeedback(msg, rating, null)
            return
        }
        val noteInput = EditText(this).apply {
            hint = getString(R.string.aletheia_feedback_note_hint)
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.aletheia_feedback_note_title)
            .setMessage(R.string.aletheia_feedback_note_compulsory_body)
            .setView(noteInput)
            .setPositiveButton(R.string.aletheia_send) { _, _ ->
                val note = noteInput.text?.toString()?.trim().orEmpty()
                if (note.isEmpty()) {
                    Toast.makeText(this, R.string.aletheia_feedback_note_required, Toast.LENGTH_SHORT).show()
                } else {
                    submitFeedback(msg, rating, note)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun submitFeedback(msg: AletheiaChatMessage, rating: AletheiaFeedbackRating, note: String?) {
        lifecycleScope.launch {
            try {
                repo.feedback(msg.id, rating, note)
                msg.feedbackSent = true
                refreshList()
                Toast.makeText(this@AletheiaChatActivity, R.string.aletheia_feedback_thanks, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (e.message == "NOTE_REQUIRED") {
                    Toast.makeText(
                        this@AletheiaChatActivity,
                        R.string.aletheia_feedback_note_required,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@AletheiaChatActivity, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshList() {
        adapter.submit(messages.toList())
        if (messages.isNotEmpty()) {
            recycler.scrollToPosition(messages.lastIndex)
        }
    }

    private fun registerConnectivityFlush() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lifecycleScope.launch {
                    runCatching { repo.flushOutbox() }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    lifecycleScope.launch {
                        runCatching { repo.flushOutbox() }
                    }
                }
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    override fun onDestroy() {
        hideThinking()
        networkCallback?.let { callback ->
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        super.onDestroy()
    }
}
