package it.grg.flighttimeapp.aletheia

/**
 * Answer channel (iOS AletheiaMessageSource): it is the Firebase `mode` of the turn.
 * The engine that really answered is tracked separately in [AletheiaEngine].
 */
enum class AletheiaSource {
    ONLINE,
    OFFLINE,
    MANUALS;

    /** Firebase `mode` value (iOS logMode = rawValue). */
    val logMode: String
        get() = name.lowercase()

    companion object {
        /**
         * Map server `source` and/or `route` to the channel.
         * Prefer explicit source; fall back to route (`groq`/`haiku` -> Online,
         * `offline_local` -> Offline, `client`/`manuals` -> Manuali).
         */
        fun fromServer(raw: String?, route: String? = null): AletheiaSource {
            val key = (raw ?: route)?.lowercase()?.trim().orEmpty()
            return when {
                key in setOf("manuals", "manual", "manuali", "client") -> MANUALS
                key in setOf("offline", "ondevice", "local", "offline_local") -> OFFLINE
                key in setOf("groq", "haiku", "online", "cloud") -> ONLINE
                key.isBlank() -> ONLINE
                else -> ONLINE
            }
        }

        fun fromLogMode(raw: String?): AletheiaSource = when (raw?.trim()?.lowercase()) {
            "online" -> ONLINE
            "manuals" -> MANUALS
            else -> OFFLINE
        }
    }
}

enum class AletheiaFeedbackRating(val apiValue: String) {
    GOOD("good"),
    PARTIAL("partial"),
    BAD("bad");
}

data class AletheiaChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val source: AletheiaSource? = null,
    /** Engine that really produced an assistant reply (null for user turns / failure bubbles). */
    val engine: AletheiaEngine? = null,
    val modelVerified: Boolean = false,
    /** User prompt that produced this assistant reply (feedback payload, like iOS). */
    val prompt: String? = null,
    val pending: Boolean = false,
    var feedbackSent: Boolean = false
) {
    enum class Role { USER, ASSISTANT, SYSTEM }

    val engineLabel: AletheiaEngineLabel?
        get() = engine?.let { AletheiaEngineLabel.of(it, modelVerified) }
}

data class AletheiaChatResult(
    val messageId: String,
    val answer: String,
    val brand: String,
    val source: AletheiaSource,
    val route: String?,
    val remainingDaily: Int,
    val remainingHaiku: Int,
    val creditsRemaining: Int,
    val dailyUsed: Int,
    val dailyLimit: Int,
    val trainingConsent: Boolean,
    val engine: AletheiaEngine = AletheiaEngine.ONLINE,
    val modelVerified: Boolean = false
)

/**
 * Durable outbox item (iOS AletheiaSyncOutbox.Item). Fields added after 1.12.15 are
 * nullable so entries written by the old app still deserialize.
 */
data class AletheiaOutboxEntry(
    val messageId: String,
    val prompt: String,
    val answer: String,
    /** Legacy channel field from 1.12.15 ("offline" / "manuals"). */
    val source: String,
    val createdAtMs: Long,
    /** "logTurn" (default when null) or "submitFeedback". */
    val kind: String? = KIND_LOG_TURN,
    val mode: String? = null,
    val locale: String? = null,
    /** AletheiaEngine.apiEngine; null on legacy entries (engine unknown). */
    val engine: String? = null,
    val rating: String? = null,
    val note: String? = null
) {
    companion object {
        const val KIND_LOG_TURN = "logTurn"
        const val KIND_FEEDBACK = "submitFeedback"
    }
}

sealed class AletheiaError(message: String) : Exception(message) {
    class ConsentRequired : AletheiaError("Consent choice required before using Aletheia.")
    class DailyLimitReached(val remaining: Int = 0) : AletheiaError("Daily online limit reached.")
    class Network(message: String) : AletheiaError(message)
    class Backend(message: String) : AletheiaError(message)
    /**
     * The callable answered INTERNAL / UNKNOWN (for example HTTP 500): the server failed while
     * the network worked. The UI shows "the online service had a problem", never a network hint.
     */
    class ServiceProblem(message: String) : AletheiaError(message)
}
