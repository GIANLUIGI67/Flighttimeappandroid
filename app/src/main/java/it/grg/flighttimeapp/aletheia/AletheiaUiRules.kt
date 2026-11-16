package it.grg.flighttimeapp.aletheia

/**
 * Pure rules behind the Crew Tools screens (no Android types), so the JVM tests check the
 * same decisions the Activities take. Each rule mirrors the iOS behaviour named in its KDoc.
 */
object AletheiaUiRules {
    /** iOS: thinking row only when a turn takes longer than ~3.5 s. */
    const val THINKING_DELAY_MS = 3_500L

    enum class SendBlock { NONE, EMPTY, BUSY, CONSENT_MISSING, FEEDBACK_PENDING }

    /** iOS send(): empty / in flight / no consent / previous answer not rated (consent Yes). */
    fun sendBlock(
        text: String,
        sending: Boolean,
        consentAnswered: Boolean,
        trainingConsent: Boolean,
        messages: List<AletheiaChatMessage>
    ): SendBlock = when {
        text.isBlank() -> SendBlock.EMPTY
        sending -> SendBlock.BUSY
        !consentAnswered -> SendBlock.CONSENT_MISSING
        trainingConsent && hasPendingRequiredFeedback(messages) -> SendBlock.FEEDBACK_PENDING
        else -> SendBlock.NONE
    }

    /** True when consent is Yes and an assistant answer still awaits a rating. */
    fun hasPendingRequiredFeedback(messages: List<AletheiaChatMessage>): Boolean =
        messages.any {
            it.role == AletheiaChatMessage.Role.ASSISTANT && !it.pending && !it.feedbackSent &&
                it.engine != null && it.text.isNotBlank()
        }

    /** A note is compulsory for neutral / bad ratings only when consent is Yes. */
    fun noteRequired(trainingConsent: Boolean, rating: AletheiaFeedbackRating): Boolean =
        trainingConsent && (rating == AletheiaFeedbackRating.BAD || rating == AletheiaFeedbackRating.PARTIAL)

    /** The battery warning appears when the user picks Offline, until dismissed. */
    fun shouldShowLowPowerWarning(switchingToOffline: Boolean, dismissed: Boolean): Boolean =
        switchingToOffline && !dismissed

    /** Prior turns sent as `history` (user/assistant only, failure bubbles excluded). */
    fun historyFor(messages: List<AletheiaChatMessage>): List<AletheiaHistoryTurn> =
        messages.mapNotNull { m ->
            when {
                m.text.isBlank() -> null
                m.role == AletheiaChatMessage.Role.USER -> AletheiaHistoryTurn("user", m.text)
                m.role == AletheiaChatMessage.Role.ASSISTANT && m.engine != null ->
                    AletheiaHistoryTurn("assistant", m.text)
                else -> null
            }
        }.takeLast(AletheiaChatRequest.MAX_HISTORY)

    enum class Meter { FREE_TODAY, WITH_CREDITS }

    /** iOS remainingMeterText: "x/20 free today" or "x/y + z credits". */
    fun meter(creditsRemaining: Int): Meter =
        if (creditsRemaining > 0) Meter.WITH_CREDITS else Meter.FREE_TODAY

    enum class ModelStatus { READY_VERIFIED, READY_UNVERIFIED, MISSING, ENGINE_UNAVAILABLE }

    fun modelStatus(record: AletheiaLocalModelStore.ModelRecord?, nativeAvailable: Boolean?): ModelStatus = when {
        record != null && nativeAvailable == false -> ModelStatus.ENGINE_UNAVAILABLE
        record == null -> ModelStatus.MISSING
        record.verified -> ModelStatus.READY_VERIFIED
        else -> ModelStatus.READY_UNVERIFIED
    }

    enum class ImportOutcome { OK_VERIFIED, OK_UNVERIFIED, REJECTED_NAME, REJECTED_FORMAT, FAILED }

    fun importOutcome(result: AletheiaLocalModelStore.ImportResult): ImportOutcome = when (result) {
        is AletheiaLocalModelStore.ImportResult.Imported ->
            if (result.record.verified) ImportOutcome.OK_VERIFIED else ImportOutcome.OK_UNVERIFIED
        AletheiaLocalModelStore.ImportResult.RejectedName -> ImportOutcome.REJECTED_NAME
        AletheiaLocalModelStore.ImportResult.RejectedNotModel -> ImportOutcome.REJECTED_FORMAT
        is AletheiaLocalModelStore.ImportResult.Failed -> ImportOutcome.FAILED
    }

    enum class DeleteOutcome { DONE, ALREADY_DELETED }

    fun deleteOutcome(result: AletheiaDeleteResult): DeleteOutcome =
        if (result.alreadyDeleted) DeleteOutcome.ALREADY_DELETED else DeleteOutcome.DONE

    /** Crew Tools tile: hub when the feature flag is on, otherwise the coming-soon notice. */
    fun crewToolsOpensHub(featureEnabled: Boolean): Boolean = featureEnabled

    enum class PlanName { FREE, PLUS, PRO, MAX }

    fun planName(serverPlan: String?): PlanName = when (serverPlan?.lowercase()) {
        "plus" -> PlanName.PLUS
        "pro" -> PlanName.PRO
        "max" -> PlanName.MAX
        else -> PlanName.FREE
    }

    /** Buy button text: plan text plus the real store price once Play has returned it (never a fixed price). */
    fun buyButtonLabel(base: String, formattedPrice: String?): String =
        if (formattedPrice.isNullOrBlank()) base else "$base · $formattedPrice"

    /** Subscription offer to buy: the base plan (offerId == null) first, never an arbitrary promotional offer. */
    fun chooseOfferToken(offers: List<Pair<String?, String>>): String? =
        offers.firstOrNull { it.first == null }?.second ?: offers.firstOrNull()?.second
}
