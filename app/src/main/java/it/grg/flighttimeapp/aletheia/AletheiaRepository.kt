package it.grg.flighttimeapp.aletheia

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Crew Tools Aletheia logic shared by the screens (aligned with iOS AletheiaChatViewModel):
 * consent, server status/quota, Online (aletheiaChat) and Offline (on-device) turns,
 * turn log + feedback outbox, delete-my-data. All dependencies are injected so the JVM
 * tests run the real logic against fakes.
 */
class AletheiaRepository(
    val prefs: AletheiaPrefs,
    private val backend: AletheiaBackend,
    val outbox: AletheiaOutbox,
    val offline: AletheiaOfflineEngine,
    private val network: () -> Boolean,
    private val localeProvider: () -> String = { AletheiaClientInfo.currentLocaleCode() },
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val consentMutex = Mutex()
    private val onlineMutex = Mutex()
    @Volatile private var consentEpoch = 0
    /** Monotonic seq so an older in-flight Online response cannot overwrite newer quota. */
    @Volatile private var quotaSeq = 0L
    @Volatile private var lastAppliedQuotaSeq = 0L

    enum class FeedbackOutcome { SENT, QUEUED }

    fun hasNetwork(): Boolean = network()

    fun locale(): String = localeProvider()

    /**
     * Read plan / usage / credits / consent from the server (iOS refreshStatus).
     * The server consent wins unless a newer local choice is still waiting to sync.
     * Returns null when offline or when the call fails (cached values stay).
     */
    suspend fun refreshStatus(): AletheiaStatus? {
        if (!hasNetwork()) return null
        val status = try {
            backend.getStatus()
        } catch (_: Exception) {
            return null
        }
        prefs.applyStatus(status)
        val server = status.trainingConsent
        when {
            prefs.consentSyncPending && prefs.consentAnswered -> runCatching { pushConsent("resync") }
            server != null -> {
                prefs.consentAnswered = true
                prefs.trainingConsentGranted = server
            }
            prefs.consentAnswered -> runCatching { pushConsent("resync") }
        }
        return status
    }

    /** Pushes the local consent only when the server has not received it yet. */
    suspend fun ensureConsentSyncedIfNeeded() {
        if (!prefs.consentAnswered || !prefs.consentSyncPending) return
        if (!hasNetwork()) return
        pushConsent("resync")
    }

    private suspend fun pushConsent(source: String) {
        backend.setConsent(prefs.trainingConsentGranted, source)
        prefs.consentSyncPending = false
    }

    /**
     * Persist locally first so Offline works without network, then sync when possible.
     * Serialized so out-of-order completions cannot reverse the latest user choice.
     */
    suspend fun setConsent(granted: Boolean, source: String = SOURCE_SETTINGS) = consentMutex.withLock {
        val epoch = ++consentEpoch
        prefs.consentAnswered = true
        prefs.trainingConsentGranted = granted
        prefs.consentSyncPending = true
        if (!hasNetwork()) return@withLock
        try {
            backend.setConsent(granted, source)
            if (epoch == consentEpoch) {
                prefs.consentSyncPending = false
            } else {
                // A newer toggle happened while we were in flight: re-sync the latest value.
                pushConsent(source)
            }
        } catch (e: Exception) {
            // Local choice kept; it is pushed again on the next status refresh / Online send.
            if (epoch == consentEpoch && hasNetwork()) throw e
        }
    }

    /** Client hard stop before an Online send (iOS shouldBlockOnlineForQuota). */
    fun isOnlineQuotaExhausted(): Boolean =
        prefs.statusFromServer && prefs.cachedStatus().isOnlineQuotaExhausted

    suspend fun sendOnline(prompt: String, history: List<AletheiaHistoryTurn> = emptyList()): AletheiaChatResult =
        onlineMutex.withLock {
            if (!prefs.consentAnswered) throw AletheiaError.ConsentRequired()
            ensureConsentSyncedIfNeeded()
            // Refresh meters before each Online send so the local gate tracks the server.
            refreshStatus()
            if (isOnlineQuotaExhausted()) throw AletheiaError.DailyLimitReached()
            val locale = locale()

            // Manuals package first (deterministic, no quota); logged like iOS (mode "manuals").
            AletheiaManuals.lookup(prompt)?.let { (answer, source) ->
                val id = newId()
                queueTurn(id, prompt, answer, source.logMode, locale, AletheiaEngine.DETERMINISTIC)
                if (hasNetwork()) runCatching { outbox.flush(backend) }
                return@withLock AletheiaChatResult(
                    messageId = id,
                    answer = answer,
                    brand = AletheiaFeature.BRAND,
                    source = source,
                    route = "client_manuals",
                    remainingDaily = prefs.cachedDailyRemaining,
                    remainingHaiku = prefs.cachedHaikuRemaining,
                    creditsRemaining = prefs.cachedCreditsRemaining,
                    dailyUsed = prefs.lastDailyUsed,
                    dailyLimit = prefs.cachedDailyLimit,
                    trainingConsent = prefs.trainingConsentGranted,
                    engine = AletheiaEngine.DETERMINISTIC
                )
            }

            val requestId = clientRequestIdForRetry(prompt)
            val seq = ++quotaSeq
            val result = try {
                backend.chat(
                    AletheiaChatRequest(
                        message = prompt,
                        history = history,
                        locale = locale,
                        // iOS: preferHaiku = status.remainingHaiku > 0 (only plans with Pro replies left).
                        preferHaiku = prefs.cachedHaikuRemaining > 0,
                        clientRequestId = requestId
                    )
                )
            } catch (e: AletheiaError.DailyLimitReached) {
                // Quota exhausted: a send after upgrade/wait is a new request (iOS).
                prefs.clearPendingOnline()
                refreshStatus()
                throw e
            }
            prefs.clearPendingOnline()
            applyQuotaIfNewer(result, seq)
            result
        }

    suspend fun sendOffline(prompt: String): AletheiaChatResult {
        if (!prefs.consentAnswered) throw AletheiaError.ConsentRequired()
        val locale = locale()
        val reply = offline.answer(prompt, locale)
        val messageId = newId()
        queueTurn(messageId, prompt, reply.text, reply.source.logMode, locale, reply.engine)
        if (hasNetwork()) {
            runCatching {
                ensureConsentSyncedIfNeeded()
                outbox.flush(backend)
            }
        }
        return AletheiaChatResult(
            messageId = messageId,
            answer = reply.text,
            brand = AletheiaFeature.BRAND,
            source = reply.source,
            route = if (reply.source == AletheiaSource.MANUALS) "manuals" else "offline_local",
            remainingDaily = prefs.cachedDailyRemaining,
            remainingHaiku = prefs.cachedHaikuRemaining,
            creditsRemaining = prefs.cachedCreditsRemaining,
            dailyUsed = prefs.lastDailyUsed,
            dailyLimit = prefs.cachedDailyLimit,
            trainingConsent = prefs.trainingConsentGranted,
            engine = reply.engine,
            modelVerified = reply.modelVerified
        )
    }

    suspend fun flushOutbox(): Int {
        if (!hasNetwork()) return 0
        if (!prefs.consentAnswered) return 0
        ensureConsentSyncedIfNeeded()
        return outbox.flush(backend)
    }

    /**
     * Rating + optional note with the same fields iOS sends (prompt, answer, mode, locale)
     * plus provenance. If the callable fails the rating is queued, not lost (iOS outbox).
     */
    suspend fun feedback(
        message: AletheiaChatMessage,
        rating: AletheiaFeedbackRating,
        note: String?
    ): FeedbackOutcome {
        if (prefs.trainingConsentGranted && note.isNullOrBlank() &&
            (rating == AletheiaFeedbackRating.BAD || rating == AletheiaFeedbackRating.PARTIAL)
        ) {
            throw AletheiaError.Backend(NOTE_REQUIRED)
        }
        val payload = AletheiaFeedbackPayload(
            messageId = message.id,
            rating = rating,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            prompt = message.prompt.orEmpty(),
            answer = message.text,
            mode = (message.source ?: AletheiaSource.ONLINE).logMode,
            locale = locale(),
            engine = message.engine
        )
        return try {
            if (!hasNetwork()) throw AletheiaError.Network("offline")
            backend.submitFeedback(payload)
            FeedbackOutcome.SENT
        } catch (_: Exception) {
            outbox.enqueue(
                AletheiaOutboxEntry(
                    messageId = payload.messageId,
                    prompt = payload.prompt,
                    answer = payload.answer,
                    source = payload.mode,
                    createdAtMs = System.currentTimeMillis(),
                    kind = AletheiaOutboxEntry.KIND_FEEDBACK,
                    mode = payload.mode,
                    locale = payload.locale,
                    engine = payload.engine?.apiEngine,
                    rating = rating.apiValue,
                    note = payload.note
                )
            )
            FeedbackOutcome.QUEUED
        }
    }

    /**
     * aletheiaDeleteMyData: the server tombstones this account's turns and feedback,
     * deletes cached answers and sets training consent to No. Locally the pending queue
     * (not yet uploaded chats) is cleared too, and consent becomes No.
     */
    suspend fun deleteMyData(source: String = SOURCE_DELETE): AletheiaDeleteResult {
        if (!hasNetwork()) throw AletheiaError.Network("offline")
        val result = backend.deleteMyData(source)
        outbox.clear()
        prefs.clearPendingOnline()
        prefs.consentAnswered = true
        prefs.trainingConsentGranted = false
        prefs.consentSyncPending = false
        return result
    }

    private fun queueTurn(
        messageId: String,
        prompt: String,
        answer: String,
        mode: String,
        locale: String,
        engine: AletheiaEngine
    ) {
        outbox.enqueue(
            AletheiaOutboxEntry(
                messageId = messageId,
                prompt = prompt,
                answer = answer,
                source = mode,
                createdAtMs = System.currentTimeMillis(),
                kind = AletheiaOutboxEntry.KIND_LOG_TURN,
                mode = mode,
                locale = locale,
                engine = engine.apiEngine
            )
        )
    }

    /** Same id for a retry of the same text (no double charge); survives app restarts. */
    private fun clientRequestIdForRetry(prompt: String): String {
        val existingId = prefs.pendingOnlineMessageId
        val existingPrompt = prefs.pendingOnlinePrompt
        if (!existingId.isNullOrBlank() && existingPrompt == prompt) {
            return existingId
        }
        val id = newId()
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
        prefs.cachedDailyLimit = result.dailyLimit
        prefs.cachedCreditsRemaining = result.creditsRemaining
        prefs.cachedHaikuRemaining = result.remainingHaiku
    }

    companion object {
        const val NOTE_REQUIRED = "NOTE_REQUIRED"
        const val SOURCE_FIRST_USE = "consent_screen"
        const val SOURCE_SETTINGS = "settings_toggle"
        const val SOURCE_DELETE = "settings_delete_my_data"

        fun create(context: Context): AletheiaRepository {
            val app = context.applicationContext
            return AletheiaRepository(
                prefs = AletheiaPrefs(app),
                backend = AletheiaServiceLocator.backend(app),
                outbox = AletheiaOutbox(app),
                offline = AletheiaOfflineEngine.fromContext(app),
                network = AletheiaServiceLocator.network(app)
            )
        }
    }
}
