package it.grg.flighttimeapp.aletheia

/**
 * Everything the Android client asks of the Firebase callables (project flighttimeapp-6f816,
 * us-central1). [AletheiaApi] is the real implementation; tests use fakes.
 */
interface AletheiaBackend {
    suspend fun setConsent(granted: Boolean, source: String): Boolean
    suspend fun getStatus(): AletheiaStatus
    suspend fun chat(request: AletheiaChatRequest): AletheiaChatResult
    suspend fun logTurn(turn: AletheiaTurnLog): Boolean
    suspend fun submitFeedback(feedback: AletheiaFeedbackPayload): Boolean
    suspend fun deleteMyData(source: String): AletheiaDeleteResult
    suspend fun verifyPlayPurchase(productId: String, purchaseToken: String): AletheiaPurchaseResult
}

/** Server status from `aletheiaGetStatus` (same fields iOS reads). */
data class AletheiaStatus(
    /** null = the server has no consent choice on file yet. */
    val trainingConsent: Boolean?,
    val plan: String,
    val dailyUsed: Int,
    val dailyLimit: Int,
    val remainingDaily: Int,
    val remainingHaiku: Int,
    val haikuMonthlyLimit: Int,
    val creditsRemaining: Int
) {
    /** iOS AletheiaStatus.isOnlineQuotaExhausted. */
    val isOnlineQuotaExhausted: Boolean
        get() = remainingDaily <= 0 && creditsRemaining <= 0
}

data class AletheiaChatRequest(
    val message: String,
    /** Prior turns, oldest first; the payload keeps only the last [MAX_HISTORY]. */
    val history: List<AletheiaHistoryTurn>,
    val locale: String,
    val preferHaiku: Boolean,
    val clientRequestId: String
) {
    companion object {
        const val MAX_HISTORY = 12
    }
}

data class AletheiaHistoryTurn(val role: String, val content: String)

data class AletheiaTurnLog(
    val messageId: String,
    val prompt: String,
    val answer: String,
    /** Channel: offline / manuals / online (iOS AletheiaMessageSource.logMode). */
    val mode: String,
    val locale: String,
    /** null only for entries queued by 1.12.15, whose engine is unknown (never guessed). */
    val engine: AletheiaEngine?
)

data class AletheiaFeedbackPayload(
    val messageId: String,
    val rating: AletheiaFeedbackRating,
    val note: String?,
    val prompt: String,
    val answer: String,
    val mode: String,
    val locale: String,
    val engine: AletheiaEngine?
)

data class AletheiaDeleteResult(
    val ok: Boolean,
    val alreadyDeleted: Boolean,
    val turnsTombstoned: Int,
    val feedbackTombstoned: Int,
    val cachedAnswersDeleted: Int
)

data class AletheiaPurchaseResult(
    val ok: Boolean,
    val plan: String?,
    val creditsRemaining: Int?,
    /** True when the callable is not deployed yet (purchase stays pending, nothing granted). */
    val notDeployed: Boolean = false
)
