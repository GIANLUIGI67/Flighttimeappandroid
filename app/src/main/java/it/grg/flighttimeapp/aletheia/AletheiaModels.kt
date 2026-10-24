package it.grg.flighttimeapp.aletheia

enum class AletheiaSource {
    ONLINE,
    OFFLINE,
    MANUALS;

    companion object {
        fun fromServer(raw: String?): AletheiaSource {
            return when (raw?.lowercase()) {
                "manuals", "manual", "manuali" -> MANUALS
                "offline", "ondevice", "local" -> OFFLINE
                else -> ONLINE
            }
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
    val pending: Boolean = false,
    var feedbackSent: Boolean = false
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
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
    val trainingConsent: Boolean
)

data class AletheiaOutboxEntry(
    val messageId: String,
    val prompt: String,
    val answer: String,
    val source: String,
    val createdAtMs: Long
)

sealed class AletheiaError(message: String) : Exception(message) {
    class ConsentRequired : AletheiaError("Consent choice required before using Aletheia.")
    class DailyLimitReached(val remaining: Int = 0) : AletheiaError("Daily online limit reached.")
    class Network(message: String) : AletheiaError(message)
    class Backend(message: String) : AletheiaError(message)
}
