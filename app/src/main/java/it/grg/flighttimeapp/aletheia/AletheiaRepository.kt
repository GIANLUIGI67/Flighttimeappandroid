package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.UUID

class AletheiaRepository(context: Context) {
    private val app = context.applicationContext
    val prefs = AletheiaPrefs(app)
    val api = AletheiaApi(app)
    val outbox = AletheiaOutbox(app)
    val offline = AletheiaOfflineEngine(app)

    fun hasNetwork(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun ensureConsentSyncedIfNeeded() {
        if (!prefs.consentAnswered) return
        runCatching { api.setConsent(prefs.trainingConsentGranted) }
    }

    suspend fun setConsent(granted: Boolean) {
        api.setConsent(granted)
        prefs.consentAnswered = true
        prefs.trainingConsentGranted = granted
    }

    suspend fun sendOnline(prompt: String): AletheiaChatResult {
        if (!prefs.consentAnswered) throw AletheiaError.ConsentRequired()
        ensureConsentSyncedIfNeeded()
        val messageId = UUID.randomUUID().toString()
        val result = api.chat(prompt, messageId)
        prefs.cachedDailyRemaining = result.remainingDaily
        prefs.cachedCreditsRemaining = result.creditsRemaining
        prefs.cachedHaikuRemaining = result.remainingHaiku
        return result
    }

    suspend fun sendOffline(prompt: String): AletheiaChatResult {
        val messageId = UUID.randomUUID().toString()
        val (answer, source) = offline.answer(prompt)
        outbox.enqueue(
            AletheiaOutboxEntry(
                messageId = messageId,
                prompt = prompt,
                answer = answer,
                source = source.name.lowercase(),
                createdAtMs = System.currentTimeMillis()
            )
        )
        if (hasNetwork() && prefs.consentAnswered) {
            runCatching { outbox.flush(api) }
        }
        return AletheiaChatResult(
            messageId = messageId,
            answer = answer,
            brand = AletheiaFeature.BRAND,
            source = source,
            route = "offline",
            remainingDaily = prefs.cachedDailyRemaining,
            remainingHaiku = prefs.cachedHaikuRemaining,
            creditsRemaining = prefs.cachedCreditsRemaining,
            dailyUsed = 0,
            dailyLimit = AletheiaPrefs.FREE_DAILY_LIMIT,
            trainingConsent = prefs.trainingConsentGranted
        )
    }

    suspend fun flushOutbox(): Int {
        if (!hasNetwork()) return 0
        if (!prefs.consentAnswered) return 0
        ensureConsentSyncedIfNeeded()
        return outbox.flush(api)
    }

    suspend fun feedback(
        messageId: String,
        rating: AletheiaFeedbackRating,
        note: String?
    ) {
        if (prefs.trainingConsentGranted && note.isNullOrBlank() &&
            (rating == AletheiaFeedbackRating.BAD || rating == AletheiaFeedbackRating.PARTIAL)
        ) {
            // Compulsory note only when RL consent is Yes (product rule).
            throw AletheiaError.Backend("NOTE_REQUIRED")
        }
        api.submitFeedback(messageId, rating, note)
    }
}
