package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AletheiaRepository(context: Context) {
    private val app = context.applicationContext
    val prefs = AletheiaPrefs(app)
    val api = AletheiaApi(app)
    val outbox = AletheiaOutbox(app)
    val offline = AletheiaOfflineEngine(app)

    private val consentMutex = Mutex()
    private val onlineMutex = Mutex()
    @Volatile private var consentEpoch = 0
    /** Monotonic seq so an older in-flight Online response cannot overwrite newer quota. */
    @Volatile private var quotaSeq = 0L
    @Volatile private var lastAppliedQuotaSeq = 0L

    fun hasNetwork(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Syncs local consent to Firebase when online. Throws on failure so Online chat
     * can surface a retry; Offline callers should skip this when offline.
     */
    suspend fun ensureConsentSyncedIfNeeded() {
        if (!prefs.consentAnswered) return
        if (!hasNetwork()) return
        api.setConsent(prefs.trainingConsentGranted)
    }

    /**
     * Persist locally first so Offline works without network, then sync when possible.
     * Serialized so out-of-order completions cannot reverse the latest user choice.
     */
    suspend fun setConsent(granted: Boolean) = consentMutex.withLock {
        val epoch = ++consentEpoch
        prefs.consentAnswered = true
        prefs.trainingConsentGranted = granted
        if (!hasNetwork()) return@withLock
        try {
            api.setConsent(granted)
            // If a newer toggle happened while we were in flight, re-sync latest.
            if (epoch != consentEpoch) {
                api.setConsent(prefs.trainingConsentGranted)
            }
        } catch (e: Exception) {
            // Local choice kept; Online will retry via ensureConsentSyncedIfNeeded.
            if (epoch == consentEpoch && hasNetwork()) throw e
        }
    }

    suspend fun sendOnline(prompt: String): AletheiaChatResult = onlineMutex.withLock {
        if (!prefs.consentAnswered) throw AletheiaError.ConsentRequired()
        ensureConsentSyncedIfNeeded()
        val messageId = messageIdForOnlineRetry(prompt)

        // Client manuals-first (Android parity): avoid inventing AFM numbers via cloud when
        // a local study package matches. Badge = Manuali; still counts as an Online turn only
        // when we hit the server — local manuals do not burn daily quota.
        AletheiaManuals.lookup(prompt)?.let { (answer, source) ->
            prefs.clearPendingOnline()
            return@withLock AletheiaChatResult(
                messageId = messageId,
                answer = answer,
                brand = AletheiaFeature.BRAND,
                source = source,
                route = "client_manuals",
                remainingDaily = prefs.cachedDailyRemaining,
                remainingHaiku = prefs.cachedHaikuRemaining,
                creditsRemaining = prefs.cachedCreditsRemaining,
                dailyUsed = prefs.lastDailyUsed,
                dailyLimit = AletheiaPrefs.FREE_DAILY_LIMIT,
                trainingConsent = prefs.trainingConsentGranted
            )
        }

        val seq = ++quotaSeq
        val result = api.chat(prompt, messageId)
        prefs.clearPendingOnline()
        applyQuotaIfNewer(result, seq)
        // Online mode must never show an Offline badge if the server mis-labels the route.
        if (result.source == AletheiaSource.OFFLINE) {
            result.copy(source = AletheiaSource.ONLINE)
        } else {
            result
        }
    }

    suspend fun sendOffline(prompt: String): AletheiaChatResult {
        if (!prefs.consentAnswered) throw AletheiaError.ConsentRequired()
        val messageId = UUID.randomUUID().toString()
        val (answer, source) = offline.answer(prompt)
        val route = when (source) {
            AletheiaSource.MANUALS -> "manuals"
            else -> "offline_local"
        }
        outbox.enqueue(
            AletheiaOutboxEntry(
                messageId = messageId,
                prompt = prompt,
                answer = answer,
                source = source.name.lowercase(),
                createdAtMs = System.currentTimeMillis()
            )
        )
        if (hasNetwork()) {
            runCatching {
                ensureConsentSyncedIfNeeded()
                outbox.flush(api)
            }
        }
        return AletheiaChatResult(
            messageId = messageId,
            answer = answer,
            brand = AletheiaFeature.BRAND,
            source = source,
            route = route,
            remainingDaily = prefs.cachedDailyRemaining,
            remainingHaiku = prefs.cachedHaikuRemaining,
            creditsRemaining = prefs.cachedCreditsRemaining,
            dailyUsed = prefs.lastDailyUsed,
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
            throw AletheiaError.Backend("NOTE_REQUIRED")
        }
        api.submitFeedback(messageId, rating, note)
    }

    private fun messageIdForOnlineRetry(prompt: String): String {
        val existingId = prefs.pendingOnlineMessageId
        val existingPrompt = prefs.pendingOnlinePrompt
        if (!existingId.isNullOrBlank() && existingPrompt == prompt) {
            return existingId
        }
        val id = UUID.randomUUID().toString()
        prefs.pendingOnlinePrompt = prompt
        prefs.pendingOnlineMessageId = id
        return id
    }

    /**
     * Apply server quota when this response is the newest Online turn.
     * Day reset is allowed (dailyUsed may drop); older overlapping responses are ignored.
     */
    private fun applyQuotaIfNewer(result: AletheiaChatResult, seq: Long) {
        if (seq < lastAppliedQuotaSeq) return
        lastAppliedQuotaSeq = seq
        prefs.lastDailyUsed = result.dailyUsed
        prefs.cachedDailyRemaining = result.remainingDaily
        prefs.cachedCreditsRemaining = result.creditsRemaining
        prefs.cachedHaikuRemaining = result.remainingHaiku
    }
}

