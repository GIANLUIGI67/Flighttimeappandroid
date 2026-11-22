package it.grg.flighttimeapp.aletheia

import com.google.firebase.functions.FirebaseFunctionsException
import com.google.gson.GsonBuilder
import it.grg.flighttimeapp.BuildConfig
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract between AletheiaApi (Kotlin) and the callables in functions/aletheia.js +
 * aletheia_turns.js (server ref aletheia-unified-db-20261115 @ 401d138). Each test records the
 * exact map handed to the Firebase SDK and checks it against the server's input rules; the maps
 * are also written to build/aletheia-contract/android_payloads.json, which
 * src/test/contract/server_contract_check.js feeds to the REAL server handlers.
 */
class AletheiaPayloadContractTest {
    private val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var nextResponse: Map<String, Any?> = mapOf("ok" to true)
    private val info = AletheiaClientInfo.fromBuildConfig()
    private val api = AletheiaApi(
        transport = { name, data -> sent += name to data; nextResponse },
        clientInfo = info,
        ensureAuth = { "uid-test" }
    )

    // Server rule replicas (file:line in the server worktree).
    /** aletheia_turns.js shortStringOrNull: only [\w.+\-/ ] survive, max 32 (surface 48). */
    private fun survivesShortString(v: Any?, max: Int = 32): Boolean =
        v is String && v.isNotEmpty() && v.length <= max && v.all { it.isLetterOrDigit() || it in "_.+-/ " }
    private val platforms = setOf("ios", "android", "pwa", "apk", "beta")
    private val engines = setOf("online", "offline", "deterministic")
    private val modelRoutes = setOf("groq", "haiku", "local", "guard")

    private fun assertProvenance(p: Map<String, Any?>) {
        assertEquals("android", p["platform"])
        assertTrue(p["platform"] in platforms)
        assertEquals(BuildConfig.VERSION_NAME, p["appVersion"])
        assertEquals(BuildConfig.VERSION_CODE.toString(), p["appBuild"])
        assertEquals(BuildConfig.ALETHEIA_VERSION, p["aletheiaVersion"])
        assertEquals("flighttime_crew_tools", p["surface"])
        assertTrue(survivesShortString(p["appVersion"]))
        assertTrue(survivesShortString(p["appBuild"]))
        assertTrue(survivesShortString(p["aletheiaVersion"]))
        assertTrue(survivesShortString(p["surface"], 48))
    }

    @Test
    fun buildIdentity_is_1_12_16_build_38_aletheia_1_2() {
        assertEquals("1.12.16", BuildConfig.VERSION_NAME)
        assertEquals(38, BuildConfig.VERSION_CODE)
        assertEquals("1.7", BuildConfig.ALETHEIA_VERSION)
    }

    @Test
    fun chat_payload_matches_iOS_plus_provenance() = runBlocking {
        nextResponse = mapOf("answer" to "Risposta.", "route" to "online", "source" to "online",
            "remainingDaily" to 18, "remainingHaiku" to 4, "creditsRemaining" to 0,
            "dailyUsed" to 2, "dailyLimit" to 20, "trainingConsent" to true, "brand" to "Aletheia Beta")
        val history = (1..15).flatMap {
            listOf(AletheiaHistoryTurn("user", "q$it"), AletheiaHistoryTurn("assistant", "a$it"))
        } + AletheiaHistoryTurn("system", "ignored")
        val rid = "6f1c2d3e-aaaa-4bbb-8ccc-1234567890ab"
        val result = api.chat(AletheiaChatRequest("Quanto e' l'FDP?", history, "it", true, rid))
        val (name, p) = sent.single()
        assertEquals("aletheiaChat", name)
        assertEquals("Quanto e' l'FDP?", p["message"])
        assertEquals("it", p["locale"])
        assertEquals(true, p["preferHaiku"])
        assertEquals(rid, p["clientRequestId"])
        assertEquals(AletheiaPayloads.sanitizeClientRequestId(rid), p["clientRequestId"])
        @Suppress("UNCHECKED_CAST")
        val h = p["history"] as List<Map<String, String>>
        assertEquals(12, h.size) // iOS .suffix(12); server MAX_HISTORY_TURNS = 12
        assertTrue(h.all { it.keys == setOf("role", "content") && it["role"] in setOf("user", "assistant") })
        assertEquals("a15", h.last()["content"])
        assertProvenance(p)
        // Legacy 1.12.15 keys are gone (iOS never sent them).
        listOf("messageId", "clientVersion", "preferManuals", "clientRouting").forEach { assertFalse(p.containsKey(it)) }
        assertEquals(AletheiaEngine.ONLINE, result.engine)
        assertEquals(18, result.remainingDaily)
        assertEquals(4, result.remainingHaiku)
        record("aletheiaChat", p)
    }

    @Test
    fun chat_serverManualsAnswer_isDeterministic_notOnlineModel() = runBlocking {
        nextResponse = mapOf("answer" to "Package text", "route" to "manuals", "source" to "manuals")
        val r = api.chat(AletheiaChatRequest("fuel limits a320", emptyList(), "en", false, "abcdefgh12"))
        assertEquals(AletheiaSource.MANUALS, r.source)
        assertEquals(AletheiaEngine.DETERMINISTIC, r.engine)
        assertEquals(false, sent.single().second["preferHaiku"])
    }

    @Test
    fun chat_shortRequestId_isDropped_likeServerSanitizer() {
        val p = AletheiaPayloads.chat(AletheiaChatRequest("hi", emptyList(), "en", false, "abc"), info)
        assertFalse(p.containsKey("clientRequestId"))
        assertEquals("abcd-1234_XY", AletheiaPayloads.sanitizeClientRequestId(" abcd-1234_XY "))
        assertEquals("abcdefgh", AletheiaPayloads.sanitizeClientRequestId("ab cd!ef#gh"))
    }

    @Test
    fun logTurn_payload_has_iOS_fields_and_true_engine() = runBlocking {
        api.logTurn(AletheiaTurnLog("msg-1", "q", "a", "offline", "it", AletheiaEngine.LOCAL_MODEL))
        val local = sent.last().second
        assertEquals("aletheiaLogTurn", sent.last().first)
        assertEquals(listOf("messageId", "prompt", "answer", "mode", "locale"), local.keys.take(5))
        assertEquals("offline", local["engine"])
        assertEquals("local", local["modelRoute"])
        assertTrue(local["engine"] in engines && local["modelRoute"] in modelRoutes)
        assertProvenance(local)
        record("aletheiaLogTurn_local", local)

        api.logTurn(AletheiaTurnLog("msg-2", "V1?", "Study aid", "manuals", "en", AletheiaEngine.DETERMINISTIC))
        val fixed = sent.last().second
        assertEquals("deterministic", fixed["engine"])
        assertEquals("guard", fixed["modelRoute"])
        assertEquals("manuals", fixed["mode"])
        record("aletheiaLogTurn_deterministic", fixed)

        api.logTurn(AletheiaTurnLog("msg-3", "q", "a", "offline", "en", null))
        val legacy = sent.last().second
        assertFalse(legacy.containsKey("engine"))
        assertFalse(legacy.containsKey("modelRoute"))
        record("aletheiaLogTurn_legacy_unknown_engine", legacy)
    }

    @Test
    fun feedback_payload_sends_prompt_answer_mode_locale_like_iOS() = runBlocking {
        nextResponse = mapOf("ok" to true, "rating" to "partial")
        api.submitFeedback(AletheiaFeedbackPayload("msg-9", AletheiaFeedbackRating.PARTIAL, "  troppo vago ",
            "Domanda", "Risposta", "online", "it", AletheiaEngine.ONLINE))
        val (name, p) = sent.single()
        assertEquals("aletheiaSubmitFeedback", name)
        assertEquals("partial", p["rating"])
        assertTrue(p["rating"] in setOf("good", "partial", "bad"))
        assertEquals("troppo vago", p["note"])
        assertEquals("Domanda", p["prompt"])
        assertEquals("Risposta", p["answer"])
        assertEquals("online", p["mode"])
        assertEquals("it", p["locale"])
        assertEquals("online", p["engine"])
        assertFalse(p.containsKey("modelRoute"))
        assertProvenance(p)
        record("aletheiaSubmitFeedback", p)
        val noNote = AletheiaPayloads.feedback(
            AletheiaFeedbackPayload("m", AletheiaFeedbackRating.GOOD, "  ", "q", "a", "offline", "en", null), info)
        assertFalse(noNote.containsKey("note"))
    }

    @Test
    fun setConsent_payload_has_granted_and_source() = runBlocking {
        nextResponse = mapOf("ok" to true, "trainingConsent" to false)
        val v = api.setConsent(false, AletheiaRepository.SOURCE_SETTINGS)
        assertEquals(false, v)
        assertEquals("aletheiaSetConsent" to mapOf("granted" to false, "source" to "settings_toggle"), sent.single())
        record("aletheiaSetConsent", sent.single().second)
    }

    @Test
    fun getStatus_parses_server_fields() = runBlocking {
        nextResponse = mapOf("trainingConsent" to null, "plan" to "max", "dailyUsed" to 4, "dailyLimit" to 30,
            "remainingDaily" to 26, "haikuUsed" to 3, "haikuMonthlyLimit" to 100, "remainingHaiku" to 97,
            "creditsRemaining" to 150, "brand" to "Aletheia Beta")
        val s = api.getStatus()
        assertEquals("aletheiaGetStatus", sent.single().first)
        assertTrue(sent.single().second.isEmpty())
        assertNull(s.trainingConsent)
        assertEquals("max", s.plan)
        assertEquals(30, s.dailyLimit)
        assertEquals(26, s.remainingDaily)
        assertEquals(97, s.remainingHaiku)
        assertEquals(100, s.haikuMonthlyLimit)
        assertEquals(150, s.creditsRemaining)
        assertFalse(s.isOnlineQuotaExhausted)
        assertTrue(s.copy(remainingDaily = 0, creditsRemaining = 0).isOnlineQuotaExhausted)
    }

    @Test
    fun deleteMyData_payload_and_result() = runBlocking {
        nextResponse = mapOf("ok" to true, "trainingConsent" to false, "alreadyDeleted" to false,
            "turnsTombstoned" to 7, "turnsAlreadyTombstoned" to 0, "feedbackTombstoned" to 2,
            "feedbackAlreadyTombstoned" to 0, "cachedAnswersDeleted" to 3)
        val r = api.deleteMyData(AletheiaRepository.SOURCE_DELETE)
        val (name, p) = sent.single()
        assertEquals("aletheiaDeleteMyData", name)
        assertEquals("settings_delete_my_data", p["source"])
        assertProvenance(p)
        assertEquals(AletheiaDeleteResult(true, false, 7, 2, 3), r)
        record("aletheiaDeleteMyData", p)
    }

    @Test
    fun verifyPlayPurchase_payload_and_notDeployed_keepsPending() = runBlocking {
        nextResponse = mapOf("ok" to true, "plan" to "plus", "creditsRemaining" to 40, "kind" to "credits")
        val r = api.verifyPlayPurchase("aletheia_credits_s", "token-abcdefgh")
        val (name, p) = sent.single()
        assertEquals("aletheiaVerifyPlayPurchase", name)
        assertEquals("aletheia_credits_s", p["productId"])
        assertEquals("token-abcdefgh", p["purchaseToken"])
        assertEquals("it.grg.flighttimeapp", p["packageName"])
        assertProvenance(p)
        assertEquals(40, r.creditsRemaining)
        record("aletheiaVerifyPlayPurchase", p)

        val notDeployed = AletheiaApi({ _, _ -> throw AletheiaError.Backend("NOT_FOUND") }, info, { "u" })
            .verifyPlayPurchase("aletheia_plus_monthly", "token-abcdefgh")
        assertFalse(notDeployed.ok)
        assertTrue(notDeployed.notDeployed)
    }

    @Test
    fun callableErrors_map_like_iOS() {
        assertTrue(AletheiaApi.mapFunctionsError("FAILED_PRECONDITION", "Consent choice required before using Aletheia.") is AletheiaError.ConsentRequired)
        assertTrue(AletheiaApi.mapFunctionsError("RESOURCE_EXHAUSTED", "x") is AletheiaError.DailyLimitReached)
        assertTrue(AletheiaApi.mapFunctionsError("INTERNAL", "Daily online limit reached") is AletheiaError.DailyLimitReached)
        assertEquals("NOT_FOUND", AletheiaApi.mapFunctionsError("NOT_FOUND", "").message)
        assertTrue(AletheiaApi.mapFunctionsError("UNAUTHENTICATED", "") is AletheiaError.Network)
        assertEquals("Aletheia unavailable. Try again shortly.",
            AletheiaApi.mapFunctionsError("INTERNAL", "Aletheia unavailable. Try again shortly.").message)
    }

    // ---- Founder test 22/11/2026: HTTP 500 is a server problem, not a network one ----

    @Test
    fun callableErrors_internalOrUnknown_areServerProblems_notNetwork() {
        // HTTP 500 from aletheiaChat: firebase-functions reports code INTERNAL, message "INTERNAL".
        assertTrue(AletheiaApi.mapFunctionsError("INTERNAL", "INTERNAL") is AletheiaError.ServiceProblem)
        // Server HttpsError("internal", ...) keeps its text (logs); the chat shows the localized copy.
        val named = AletheiaApi.mapFunctionsError("INTERNAL", "Aletheia online path unavailable. Try again shortly.")
        assertTrue(named is AletheiaError.ServiceProblem)
        assertEquals("Aletheia online path unavailable. Try again shortly.", named.message)
        // Unmapped HTTP status (e.g. 502) arrives as UNKNOWN.
        val unknown = AletheiaApi.mapFunctionsError("UNKNOWN", "")
        assertTrue(unknown is AletheiaError.ServiceProblem)
        assertEquals("UNKNOWN", unknown.message)
        // A failed connection is ALSO code INTERNAL in firebase-functions 22.x: it stays a network
        // error with its existing text.
        val net = AletheiaApi.mapFunctionsError("INTERNAL", "INTERNAL", transportFailure = true)
        assertTrue(net is AletheiaError.Network)
        assertEquals("INTERNAL", net.message)
        // Limit and consent answers still win over the code; other codes are unchanged.
        assertTrue(AletheiaApi.mapFunctionsError("INTERNAL", "Daily online limit reached") is AletheiaError.DailyLimitReached)
        assertTrue(AletheiaApi.mapFunctionsError("INTERNAL", "Consent choice required before using Aletheia.") is AletheiaError.ConsentRequired)
        assertTrue(AletheiaApi.mapFunctionsError("DEADLINE_EXCEEDED", "DEADLINE_EXCEEDED") is AletheiaError.Backend)
        assertTrue(AletheiaApi.mapFunctionsError("UNAVAILABLE", "x") is AletheiaError.Backend)
    }

    /**
     * The SDK's own exception, built like FirebaseFunctions 22.1.1 does: an HTTP error answer uses
     * the (message, code, details) constructor, a failed connection the one with the IOException cause.
     */
    private fun sdkException(code: FirebaseFunctionsException.Code, cause: Throwable?): FirebaseFunctionsException {
        val cls = FirebaseFunctionsException::class.java
        return if (cause == null) {
            cls.getDeclaredConstructor(String::class.java, FirebaseFunctionsException.Code::class.java, Any::class.java)
                .apply { isAccessible = true }
                .newInstance(code.name, code, null)
        } else {
            cls.getDeclaredConstructor(
                String::class.java, FirebaseFunctionsException.Code::class.java, Any::class.java, Throwable::class.java
            ).apply { isAccessible = true }.newInstance(code.name, code, null, cause)
        }
    }

    @Test
    fun mapException_realSdkException_http500_vs_failedConnection() {
        // Tasks.await wraps the SDK exception in an ExecutionException, as in FirebaseCallableTransport.
        val http500 = sdkException(FirebaseFunctionsException.Code.INTERNAL, null)
        assertTrue(AletheiaApi.mapException(ExecutionException(http500)) is AletheiaError.ServiceProblem)
        assertTrue(AletheiaApi.mapException(http500) is AletheiaError.ServiceProblem)

        val noConnection = sdkException(FirebaseFunctionsException.Code.INTERNAL, UnknownHostException("no route"))
        val mapped = AletheiaApi.mapException(ExecutionException(noConnection))
        assertTrue(mapped is AletheiaError.Network)
        assertEquals("INTERNAL", mapped.message)

        val unknown = sdkException(FirebaseFunctionsException.Code.UNKNOWN, null)
        assertTrue(AletheiaApi.mapException(ExecutionException(unknown)) is AletheiaError.ServiceProblem)
    }

    // ---- Founder test 22/11/2026: the rating joins the Online turn on the same id ----

    @Test
    fun onlineRating_sendsTheIdTheServerStoresTheChatTurnUnder() = runBlocking {
        nextResponse = mapOf("answer" to "Risposta.", "route" to "online", "source" to "online",
            "remainingDaily" to 18, "dailyLimit" to 20)
        val rid = "6f1c2d3e-aaaa-4bbb-8ccc-1234567890ab"
        val result = api.chat(AletheiaChatRequest("Quanto e' l'FDP?", emptyList(), "it", false, rid))
        val chat = sent.single().second
        // Server aletheiaChat: messageId = sanitize(data.messageId) || sanitize(data.clientRequestId).
        val serverTurnId = AletheiaPayloads.sanitizeClientRequestId(chat["messageId"] as? String ?: "")
            .ifEmpty { AletheiaPayloads.sanitizeClientRequestId(chat["clientRequestId"] as? String ?: "") }
        assertEquals(rid, serverTurnId)
        // The assistant bubble takes result.messageId; its rating sends that id as messageId.
        assertEquals(rid, result.messageId)
        nextResponse = mapOf("ok" to true)
        api.submitFeedback(
            AletheiaFeedbackPayload(result.messageId, AletheiaFeedbackRating.GOOD, "nota dopo il voto",
                "Quanto e' l'FDP?", result.answer, result.source.logMode, "it", result.engine)
        )
        val (name, fb) = sent.last()
        assertEquals("aletheiaSubmitFeedback", name)
        assertEquals(serverTurnId, fb["messageId"])
    }

    private fun record(name: String, payload: Map<String, Any?>) {
        val dir = File("build/aletheia-contract").apply { mkdirs() }
        val f = File(dir, "android_payloads.json")
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        @Suppress("UNCHECKED_CAST")
        val all = if (f.isFile) (gson.fromJson(f.readText(), Map::class.java) as Map<String, Any?>).toMutableMap()
        else linkedMapOf()
        all[name] = payload
        f.writeText(gson.toJson(all))
    }
}
