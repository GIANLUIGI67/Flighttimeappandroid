package it.grg.flighttimeapp.aletheia

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Real AletheiaRepository logic against fake callables (no Firebase, no network). */
class AletheiaRepositoryTest {

    private fun consented(env: TestEnv, granted: Boolean = true) = runBlocking {
        env.repo.setConsent(granted, AletheiaRepository.SOURCE_FIRST_USE)
        env.backend.calls.clear()
        env.backend.consentCalls.clear()
    }

    // ---- CT-CONSENT-FIRST / CT-CONSENT-TOGGLE / CT-CONSENT-SYNC ----

    @Test
    fun consentFirstUse_savedLocally_thenSentWithSource() = runBlocking {
        val env = TestEnv()
        env.repo.setConsent(true, AletheiaRepository.SOURCE_FIRST_USE)
        assertTrue(env.prefs.consentAnswered)
        assertTrue(env.prefs.trainingConsentGranted)
        assertFalse(env.prefs.consentSyncPending)
        assertEquals(listOf(true to "consent_screen"), env.backend.consentCalls)
    }

    @Test
    fun consentWithoutNetwork_staysPending_thenPushedOnNextStatusRefresh() = runBlocking {
        val env = TestEnv(online = false)
        env.repo.setConsent(false, AletheiaRepository.SOURCE_FIRST_USE)
        assertTrue(env.prefs.consentAnswered)
        assertTrue(env.prefs.consentSyncPending)
        assertTrue(env.backend.consentCalls.isEmpty())
        env.online = true
        env.backend.status = env.backend.status.copy(trainingConsent = true) // stale server value
        env.repo.refreshStatus()
        assertEquals(listOf(false to "resync"), env.backend.consentCalls)
        assertFalse(env.prefs.trainingConsentGranted) // local newer choice wins
        assertFalse(env.prefs.consentSyncPending)
    }

    @Test
    fun consentToggleFromSettings_usesSettingsSource_and_failureKeepsLocalChoice() = runBlocking {
        val env = TestEnv()
        consented(env, true)
        env.repo.setConsent(false, AletheiaRepository.SOURCE_SETTINGS)
        assertEquals(listOf(false to "settings_toggle"), env.backend.consentCalls)
        env.backend.consentError = AletheiaError.Backend("boom")
        try {
            env.repo.setConsent(true, AletheiaRepository.SOURCE_SETTINGS)
            fail("expected the sync failure to surface")
        } catch (_: AletheiaError.Backend) {
        }
        assertTrue(env.prefs.trainingConsentGranted)
        assertTrue(env.prefs.consentSyncPending)
    }

    @Test
    fun statusRefresh_adoptsServerConsent_whenNothingPending() = runBlocking {
        val env = TestEnv()
        consented(env, true)
        env.backend.status = env.backend.status.copy(trainingConsent = false)
        env.repo.refreshStatus()
        assertFalse(env.prefs.trainingConsentGranted)
        assertTrue(env.backend.consentCalls.isEmpty()) // never re-sends the local value needlessly
    }

    @Test
    fun ensureConsentSynced_doesNotCallServer_whenAlreadySynced() = runBlocking {
        val env = TestEnv()
        consented(env, true)
        env.repo.ensureConsentSyncedIfNeeded()
        assertTrue(env.backend.calls.isEmpty())
    }

    // ---- CT-QUOTA-STATUS / CT-QUOTA-METER ----

    @Test
    fun statusRefresh_storesPlanUsageCredits() = runBlocking {
        val env = TestEnv()
        env.backend.status = AletheiaStatus(true, "pro", 5, 20, 15, 44, 50, 150)
        val s = env.repo.refreshStatus()
        assertEquals("pro", s!!.plan)
        assertEquals("pro", env.prefs.serverPlan)
        assertEquals(5, env.prefs.lastDailyUsed)
        assertEquals(15, env.prefs.cachedDailyRemaining)
        assertEquals(20, env.prefs.cachedDailyLimit)
        assertEquals(44, env.prefs.cachedHaikuRemaining)
        assertEquals(150, env.prefs.cachedCreditsRemaining)
        assertTrue(env.prefs.statusFromServer)
    }

    @Test
    fun statusRefresh_offlineOrError_keepsCache() = runBlocking {
        val env = TestEnv(online = false)
        assertNull(env.repo.refreshStatus())
        env.online = true
        env.backend.statusError = AletheiaError.Network("x")
        assertNull(env.repo.refreshStatus())
        assertFalse(env.prefs.statusFromServer)
    }

    // ---- CT-CHAT-ONLINE / CT-CHAT-ONLINE-RETRY-ID / CT-QUOTA-PAYWALL / CT-CHAT-ONLINE-FAILURE ----

    @Test
    fun online_sendsHistoryLocalePreferHaiku_andAppliesQuota() = runBlocking {
        val env = TestEnv(locale = "it")
        consented(env)
        env.backend.status = AletheiaStatus(true, "plus", 3, 20, 17, 12, 30, 0)
        val history = (1..20).map { AletheiaHistoryTurn(if (it % 2 == 1) "user" else "assistant", "t$it") }
        val r = env.repo.sendOnline("Che cosa e il CRM?", history)
        val req = env.backend.chatRequests.single()
        assertEquals("Che cosa e il CRM?", req.message)
        assertEquals("it", req.locale)
        assertTrue(req.preferHaiku) // plan still has Pro replies this month (iOS rule)
        assertEquals(20, req.history.size) // the payload builder trims to 12 (contract test)
        assertEquals(AletheiaEngine.ONLINE, r.engine)
        assertEquals(19, env.prefs.cachedDailyRemaining)
        assertNull(env.prefs.pendingOnlineMessageId) // cleared after success
        // iOS order: status refresh before the send.
        assertEquals(listOf("aletheiaGetStatus", "aletheiaChat"), env.backend.calls)
    }

    @Test
    fun online_preferHaikuFalse_whenNoProRepliesLeft() = runBlocking {
        val env = TestEnv()
        consented(env)
        env.backend.status = AletheiaStatus(true, "free", 0, 20, 20, 0, 0, 0)
        env.repo.sendOnline("hello there")
        assertFalse(env.backend.chatRequests.single().preferHaiku)
    }

    @Test
    fun online_retryOfSameText_reusesClientRequestId_noDoubleCharge() = runBlocking {
        val env = TestEnv()
        consented(env)
        env.backend.chatError = AletheiaError.Backend("Aletheia unavailable. Try again shortly.")
        val first = runCatching { env.repo.sendOnline("What is minimum rest?") }
        assertTrue(first.exceptionOrNull() is AletheiaError.Backend) // UI shows the failure
        val keptId = env.prefs.pendingOnlineMessageId
        assertTrue(!keptId.isNullOrBlank())
        env.backend.chatError = null
        env.repo.sendOnline("What is minimum rest?")
        assertEquals(keptId, env.backend.chatRequests.single().clientRequestId)
        // A different question gets a new id.
        env.repo.sendOnline("Another question")
        assertNotEquals(keptId, env.backend.chatRequests.last().clientRequestId)
    }

    @Test
    fun online_quotaExhausted_blocksBeforeSending() = runBlocking {
        val env = TestEnv()
        consented(env)
        env.backend.status = AletheiaStatus(true, "free", 20, 20, 0, 0, 0, 0)
        try {
            env.repo.sendOnline("question")
            fail("expected paywall")
        } catch (_: AletheiaError.DailyLimitReached) {
        }
        assertFalse("aletheiaChat" in env.backend.calls)
    }

    @Test
    fun online_creditsLeft_allowSendEvenWithDailyAtZero() = runBlocking {
        val env = TestEnv()
        consented(env)
        env.backend.status = AletheiaStatus(true, "free", 20, 20, 0, 0, 0, 5)
        env.repo.sendOnline("question")
        assertEquals(1, env.backend.chatRequests.size)
    }

    @Test
    fun online_serverLimitError_clearsRetryId_andRaisesPaywall() = runBlocking {
        val env = TestEnv()
        consented(env)
        env.backend.chatError = AletheiaError.DailyLimitReached()
        try {
            env.repo.sendOnline("question")
            fail("expected paywall")
        } catch (_: AletheiaError.DailyLimitReached) {
        }
        assertNull(env.prefs.pendingOnlineMessageId)
    }

    @Test
    fun online_withoutConsent_isRefused() = runBlocking {
        val env = TestEnv()
        try {
            env.repo.sendOnline("question")
            fail()
        } catch (_: AletheiaError.ConsentRequired) {
        }
        assertTrue(env.backend.calls.isEmpty())
    }

    // ---- CT-CHAT-ONLINE-MANUALS-FIRST ----

    @Test
    fun online_manualsPackageFirst_isDeterministic_logged_andFree() = runBlocking {
        val env = TestEnv()
        consented(env)
        val r = env.repo.sendOnline("What is V1 decision speed?")
        assertEquals(AletheiaSource.MANUALS, r.source)
        assertEquals(AletheiaEngine.DETERMINISTIC, r.engine)
        assertFalse("aletheiaChat" in env.backend.calls)
        val turn = env.backend.turns.single()
        assertEquals("manuals", turn.mode)
        assertEquals(AletheiaEngine.DETERMINISTIC, turn.engine)
        assertTrue(env.outbox.pending().isEmpty())
    }

    // ---- CT-OFF-* / CT-LOGTURN / CT-OUTBOX ----

    @Test
    fun offline_noModel_answersFixed_andLogsDeterministicGuard() = runBlocking {
        val env = TestEnv()
        consented(env)
        val r = env.repo.sendOffline("Explain crew rest after a long duty")
        assertEquals(AletheiaEngine.DETERMINISTIC, r.engine)
        assertTrue(r.answer.contains("Aletheia 1.5B"))
        val turn = env.backend.turns.single()
        assertEquals("offline", turn.mode)
        assertEquals(AletheiaEngine.DETERMINISTIC, turn.engine)
        assertEquals("guard", turn.engine!!.modelRoute)
        assertTrue(env.runtime.prompts.isEmpty()) // no model ran
    }

    @Test
    fun offline_verifiedModel_answersLocal_andLogsOfflineLocal() = runBlocking {
        val env = TestEnv()
        consented(env)
        env.installModel(verified = true)
        val r = env.repo.sendOffline("Explain crew rest after a long duty")
        assertEquals(AletheiaEngine.LOCAL_MODEL, r.engine)
        assertTrue(r.modelVerified)
        val turn = env.backend.turns.single()
        assertEquals("offline", turn.engine!!.apiEngine)
        assertEquals("local", turn.engine!!.modelRoute)
    }

    @Test
    fun outbox_keepsTurnsWithoutNetwork_andFlushesWhenBack() = runBlocking {
        val env = TestEnv(online = false)
        consented(env)
        env.repo.sendOffline("What is V1?")
        env.repo.sendOffline("Explain crew rest")
        assertEquals(2, env.outbox.pending().size)
        assertTrue(env.backend.turns.isEmpty())
        env.online = true
        env.backend.logTurnError = AletheiaError.Network("down")
        assertEquals(0, env.repo.flushOutbox())
        assertEquals(2, env.outbox.pending().size) // failures stay queued
        env.backend.logTurnError = null
        assertEquals(2, env.repo.flushOutbox())
        assertTrue(env.outbox.pending().isEmpty())
        assertEquals(listOf("manuals", "offline"), env.backend.turns.map { it.mode })
    }

    @Test
    fun outbox_capsAt200_likeIOS() {
        val env = TestEnv()
        repeat(205) {
            env.outbox.enqueue(AletheiaOutboxEntry("m$it", "p", "a", "offline", it.toLong()))
        }
        val pending = env.outbox.pending()
        assertEquals(200, pending.size)
        assertEquals("m5", pending.first().messageId)
    }

    @Test
    fun outbox_legacyEntryFrom_1_12_15_flushesWithUnknownEngine() = runBlocking {
        val env = TestEnv()
        consented(env)
        env.outboxStore.edit().putString(
            "entries",
            "[{\"messageId\":\"old-1\",\"prompt\":\"p\",\"answer\":\"a\",\"source\":\"manuals\",\"createdAtMs\":1}]"
        ).apply()
        assertEquals(1, env.repo.flushOutbox())
        val t = env.backend.turns.single()
        assertEquals("manuals", t.mode)
        assertNull(t.engine) // never guessed
    }

    // ---- CT-FEEDBACK-RATE / CT-FEEDBACK-NOTE / CT-FEEDBACK-QUEUE ----

    private fun assistant(engine: AletheiaEngine = AletheiaEngine.ONLINE) = AletheiaChatMessage(
        id = "ans-1", role = AletheiaChatMessage.Role.ASSISTANT, text = "Answer text",
        source = AletheiaSource.ONLINE, engine = engine, prompt = "The question"
    )

    @Test
    fun feedback_sendsPromptAnswerModeLocaleEngine() = runBlocking {
        val env = TestEnv(locale = "it")
        consented(env)
        val out = env.repo.feedback(assistant(), AletheiaFeedbackRating.GOOD, null)
        assertEquals(AletheiaRepository.FeedbackOutcome.SENT, out)
        val fb = env.backend.feedbacks.single()
        assertEquals("ans-1", fb.messageId)
        assertEquals("The question", fb.prompt)
        assertEquals("Answer text", fb.answer)
        assertEquals("online", fb.mode)
        assertEquals("it", fb.locale)
        assertEquals(AletheiaEngine.ONLINE, fb.engine)
        assertNull(fb.note)
    }

    @Test
    fun feedback_noteRequired_forNeutralOrBad_whenConsentYes() = runBlocking {
        val env = TestEnv()
        consented(env, true)
        try {
            env.repo.feedback(assistant(), AletheiaFeedbackRating.BAD, "  ")
            fail()
        } catch (e: AletheiaError.Backend) {
            assertEquals(AletheiaRepository.NOTE_REQUIRED, e.message)
        }
        env.repo.feedback(assistant(), AletheiaFeedbackRating.BAD, "wrong figure")
        assertEquals("wrong figure", env.backend.feedbacks.single().note)
        val envNo = TestEnv()
        consented(envNo, false)
        envNo.repo.feedback(assistant(), AletheiaFeedbackRating.BAD, null) // optional when consent No
        assertEquals(1, envNo.backend.feedbacks.size)
    }

    @Test
    fun feedback_failure_isQueued_thenFlushedAsSubmitFeedback() = runBlocking {
        val env = TestEnv()
        consented(env, false)
        env.backend.feedbackError = AletheiaError.Network("down")
        val out = env.repo.feedback(assistant(AletheiaEngine.LOCAL_MODEL), AletheiaFeedbackRating.PARTIAL, "meh")
        assertEquals(AletheiaRepository.FeedbackOutcome.QUEUED, out)
        assertEquals(AletheiaOutboxEntry.KIND_FEEDBACK, env.outbox.pending().single().kind)
        env.backend.feedbackError = null
        assertEquals(1, env.repo.flushOutbox())
        val fb = env.backend.feedbacks.single()
        assertEquals(AletheiaFeedbackRating.PARTIAL, fb.rating)
        assertEquals("meh", fb.note)
        assertEquals(AletheiaEngine.LOCAL_MODEL, fb.engine)
    }

    // ---- CT-DELETE-MY-DATA (new) ----

    @Test
    fun deleteMyData_callsServer_clearsQueue_andConsentBecomesNo() = runBlocking {
        val env = TestEnv(online = false)
        consented(env, true)
        env.repo.sendOffline("Explain crew rest") // queued, not uploaded
        assertEquals(1, env.outbox.pending().size)
        try {
            env.repo.deleteMyData()
            fail("needs network")
        } catch (_: AletheiaError.Network) {
        }
        assertTrue(env.backend.deletes.isEmpty())
        env.online = true
        env.prefs.pendingOnlinePrompt = "x"
        env.prefs.pendingOnlineMessageId = "y"
        val r = env.repo.deleteMyData()
        assertEquals(listOf("settings_delete_my_data"), env.backend.deletes)
        assertEquals(3, r.turnsTombstoned)
        assertTrue(env.outbox.pending().isEmpty())
        assertTrue(env.prefs.consentAnswered)
        assertFalse(env.prefs.trainingConsentGranted)
        assertFalse(env.prefs.consentSyncPending)
        assertNull(env.prefs.pendingOnlineMessageId)
    }
}
