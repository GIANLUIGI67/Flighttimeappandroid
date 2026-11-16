package it.grg.flighttimeapp.aletheia

import it.grg.flighttimeapp.BuildConfig
import it.grg.flighttimeapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen decisions (same functions the Activities call): CT-HOME-TILE, CT-FEATURE-FLAG,
 * CT-VERSION-LABEL, CT-CHAT-COMPOSE, CT-CHAT-THINKING, CT-FEEDBACK-GATE, CT-LPM-WARNING,
 * CT-QUOTA-METER, CT-ENGINE-BADGE, CT-MODEL-STATUS, CT-PLANS-SCREEN.
 */
class AletheiaUiRulesTest {
    private fun user(t: String) = AletheiaChatMessage("u-$t", AletheiaChatMessage.Role.USER, t)
    private fun ans(t: String, engine: AletheiaEngine? = AletheiaEngine.ONLINE, rated: Boolean = false) =
        AletheiaChatMessage("a-$t", AletheiaChatMessage.Role.ASSISTANT, t, AletheiaSource.ONLINE, engine, feedbackSent = rated)

    @Test
    fun featureFlag_onInThisBuild_tileOpensHub() {
        assertTrue(BuildConfig.ALETHEIA_ENABLED)
        assertTrue(AletheiaFeature.isEnabled)
        assertTrue(AletheiaUiRules.crewToolsOpensHub(AletheiaFeature.isEnabled))
        assertFalse(AletheiaUiRules.crewToolsOpensHub(false))
        assertEquals("Aletheia Beta", AletheiaFeature.BRAND)
        assertEquals(BuildConfig.ALETHEIA_VERSION, AletheiaFeature.versionName)
    }

    @Test
    fun compose_blocks_emptyBusyNoConsentPendingFeedback() {
        val none = emptyList<AletheiaChatMessage>()
        assertEquals(AletheiaUiRules.SendBlock.EMPTY, AletheiaUiRules.sendBlock("  ", false, true, true, none))
        assertEquals(AletheiaUiRules.SendBlock.BUSY, AletheiaUiRules.sendBlock("q", true, true, true, none))
        assertEquals(AletheiaUiRules.SendBlock.CONSENT_MISSING, AletheiaUiRules.sendBlock("q", false, false, false, none))
        val unrated = listOf(user("q1"), ans("a1"))
        assertEquals(AletheiaUiRules.SendBlock.FEEDBACK_PENDING, AletheiaUiRules.sendBlock("q2", false, true, true, unrated))
        // Consent No: rating is optional, the next question is allowed.
        assertEquals(AletheiaUiRules.SendBlock.NONE, AletheiaUiRules.sendBlock("q2", false, true, false, unrated))
        val rated = listOf(user("q1"), ans("a1", rated = true))
        assertEquals(AletheiaUiRules.SendBlock.NONE, AletheiaUiRules.sendBlock("q2", false, true, true, rated))
    }

    @Test
    fun feedbackGate_andNoteRule() {
        assertTrue(AletheiaUiRules.hasPendingRequiredFeedback(listOf(ans("a"))))
        assertFalse(AletheiaUiRules.hasPendingRequiredFeedback(listOf(ans("a", rated = true))))
        assertFalse(AletheiaUiRules.hasPendingRequiredFeedback(listOf(user("u"))))
        assertTrue(AletheiaUiRules.noteRequired(true, AletheiaFeedbackRating.BAD))
        assertTrue(AletheiaUiRules.noteRequired(true, AletheiaFeedbackRating.PARTIAL))
        assertFalse(AletheiaUiRules.noteRequired(true, AletheiaFeedbackRating.GOOD))
        assertFalse(AletheiaUiRules.noteRequired(false, AletheiaFeedbackRating.BAD))
    }

    @Test
    fun thinkingIndicator_after3_5s_likeIOS() {
        assertEquals(3_500L, AletheiaUiRules.THINKING_DELAY_MS)
    }

    @Test
    fun lowPowerWarning_onlyWhenSwitchingToOffline_untilDismissed() {
        assertTrue(AletheiaUiRules.shouldShowLowPowerWarning(switchingToOffline = true, dismissed = false))
        assertFalse(AletheiaUiRules.shouldShowLowPowerWarning(switchingToOffline = true, dismissed = true))
        assertFalse(AletheiaUiRules.shouldShowLowPowerWarning(switchingToOffline = false, dismissed = false))
    }

    @Test
    fun history_userAndAssistantOnly_last12() {
        val msgs = (1..10).flatMap { listOf(user("q$it"), ans("a$it")) } +
            AletheiaChatMessage("x", AletheiaChatMessage.Role.ASSISTANT, "toast-only", engine = null)
        val h = AletheiaUiRules.historyFor(msgs)
        assertEquals(12, h.size)
        assertEquals(AletheiaHistoryTurn("assistant", "a10"), h.last())
        assertTrue(h.none { it.content == "toast-only" })
    }

    @Test
    fun meter_freeOrWithCredits() {
        assertEquals(AletheiaUiRules.Meter.FREE_TODAY, AletheiaUiRules.meter(0))
        assertEquals(AletheiaUiRules.Meter.WITH_CREDITS, AletheiaUiRules.meter(3))
    }

    @Test
    fun engineBadge_isTheEngineThatReallyAnswered() {
        val online = ans("a", AletheiaEngine.ONLINE)
        assertEquals(R.string.aletheia_engine_online, AletheiaMessageAdapter.badgeTextRes(online))
        val local = online.copy(engine = AletheiaEngine.LOCAL_MODEL, source = AletheiaSource.OFFLINE, modelVerified = true)
        assertEquals(R.string.aletheia_engine_local, AletheiaMessageAdapter.badgeTextRes(local))
        assertEquals(R.string.aletheia_engine_local_unverified,
            AletheiaMessageAdapter.badgeTextRes(local.copy(modelVerified = false)))
        val fixed = online.copy(engine = AletheiaEngine.DETERMINISTIC, source = AletheiaSource.OFFLINE)
        assertEquals(R.string.aletheia_engine_fixed, AletheiaMessageAdapter.badgeTextRes(fixed))
        assertEquals(R.string.aletheia_engine_fixed_manuals,
            AletheiaMessageAdapter.badgeTextRes(fixed.copy(source = AletheiaSource.MANUALS)))
        assertEquals(null, AletheiaMessageAdapter.badgeTextRes(user("u")))
        // Provenance values sent to the server for each engine.
        assertEquals("online" to null, AletheiaEngine.ONLINE.apiEngine to AletheiaEngine.ONLINE.modelRoute)
        assertEquals("offline" to "local", AletheiaEngine.LOCAL_MODEL.apiEngine to AletheiaEngine.LOCAL_MODEL.modelRoute)
        assertEquals("deterministic" to "guard", AletheiaEngine.DETERMINISTIC.apiEngine to AletheiaEngine.DETERMINISTIC.modelRoute)
    }

    @Test
    fun modelStatus_importOutcome_deleteOutcome_planNames() {
        val rec = AletheiaLocalModelStore.ModelRecord("a.gguf", 1, "x", true, 0)
        assertEquals(AletheiaUiRules.ModelStatus.MISSING, AletheiaUiRules.modelStatus(null, true))
        assertEquals(AletheiaUiRules.ModelStatus.READY_VERIFIED, AletheiaUiRules.modelStatus(rec, true))
        assertEquals(AletheiaUiRules.ModelStatus.READY_VERIFIED, AletheiaUiRules.modelStatus(rec, null))
        assertEquals(AletheiaUiRules.ModelStatus.READY_UNVERIFIED, AletheiaUiRules.modelStatus(rec.copy(verified = false), true))
        assertEquals(AletheiaUiRules.ModelStatus.ENGINE_UNAVAILABLE, AletheiaUiRules.modelStatus(rec, false))
        assertEquals(AletheiaUiRules.DeleteOutcome.DONE, AletheiaUiRules.deleteOutcome(AletheiaDeleteResult(true, false, 1, 0, 0)))
        assertEquals(AletheiaUiRules.DeleteOutcome.ALREADY_DELETED, AletheiaUiRules.deleteOutcome(AletheiaDeleteResult(true, true, 0, 0, 0)))
        assertEquals(AletheiaUiRules.PlanName.MAX, AletheiaUiRules.planName("max"))
        assertEquals(AletheiaUiRules.PlanName.FREE, AletheiaUiRules.planName("platinum"))
        assertEquals(AletheiaUiRules.PlanName.FREE, AletheiaUiRules.planName(null))
    }

    @Test
    fun buyButton_showsStorePriceOnlyWhenPlayReturnedIt() {
        assertEquals("Plus", AletheiaUiRules.buyButtonLabel("Plus", null))
        assertEquals("Plus", AletheiaUiRules.buyButtonLabel("Plus", " "))
        assertEquals("Plus · €0.99", AletheiaUiRules.buyButtonLabel("Plus", "€0.99"))
    }

    @Test
    fun subscription_buysBasePlan_notAPromoOffer() {
        assertEquals("base", AletheiaUiRules.chooseOfferToken(listOf("intro-trial" to "promo", null to "base")))
        assertEquals("only", AletheiaUiRules.chooseOfferToken(listOf("winback" to "only")))
        assertEquals(null, AletheiaUiRules.chooseOfferToken(emptyList()))
    }
}
