package it.grg.flighttimeapp.aletheia

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import it.grg.flighttimeapp.crewl.CrewAuthManager
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One HTTPS callable round trip: name + request data -> response map. */
fun interface AletheiaCallableTransport {
    suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?>
}

/** Production transport: Firebase Functions us-central1 (same region as iOS). */
class FirebaseCallableTransport(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("us-central1")
) : AletheiaCallableTransport {
    @Suppress("UNCHECKED_CAST")
    override suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            try {
                val task = functions.getHttpsCallable(name).call(data)
                val snapshot = Tasks.await(task, 120, TimeUnit.SECONDS)
                when (val raw = snapshot.data) {
                    is Map<*, *> -> raw as Map<String, Any?>
                    else -> emptyMap()
                }
            } catch (e: Exception) {
                throw AletheiaApi.mapException(e)
            }
        }
}

/**
 * Client for the Firebase callables on flighttimeapp-6f816 (us-central1):
 * aletheiaSetConsent, aletheiaGetStatus, aletheiaChat, aletheiaLogTurn,
 * aletheiaSubmitFeedback, aletheiaDeleteMyData, aletheiaVerifyPlayPurchase.
 * Payloads come from [AletheiaPayloads] so the contract tests check the exact bytes sent.
 */
class AletheiaApi(
    private val transport: AletheiaCallableTransport,
    private val clientInfo: AletheiaClientInfo,
    private val ensureAuth: suspend () -> String
) : AletheiaBackend {

    constructor(context: Context) : this(
        transport = FirebaseCallableTransport(),
        clientInfo = AletheiaClientInfo.fromBuildConfig(),
        ensureAuth = { anonymousAuth(context.applicationContext) }
    )

    override suspend fun setConsent(granted: Boolean, source: String): Boolean {
        ensureAuth()
        val result = transport.call("aletheiaSetConsent", AletheiaPayloads.setConsent(granted, source))
        val ok = result["ok"] as? Boolean ?: true
        if (!ok) throw AletheiaError.Backend("Consent update rejected")
        return result["trainingConsent"] as? Boolean ?: granted
    }

    override suspend fun getStatus(): AletheiaStatus {
        ensureAuth()
        return AletheiaPayloads.parseStatus(transport.call("aletheiaGetStatus", emptyMap()))
    }

    override suspend fun chat(request: AletheiaChatRequest): AletheiaChatResult {
        ensureAuth()
        val result = transport.call("aletheiaChat", AletheiaPayloads.chat(request, clientInfo))
        return AletheiaPayloads.parseChat(result, request.clientRequestId)
    }

    override suspend fun logTurn(turn: AletheiaTurnLog): Boolean {
        ensureAuth()
        val result = transport.call("aletheiaLogTurn", AletheiaPayloads.logTurn(turn, clientInfo))
        return result["ok"] as? Boolean ?: true
    }

    override suspend fun submitFeedback(feedback: AletheiaFeedbackPayload): Boolean {
        ensureAuth()
        val result = transport.call(
            "aletheiaSubmitFeedback",
            AletheiaPayloads.feedback(feedback, clientInfo)
        )
        val ok = result["ok"] as? Boolean ?: false
        if (!ok) throw AletheiaError.Backend("Feedback rejected")
        return true
    }

    override suspend fun deleteMyData(source: String): AletheiaDeleteResult {
        ensureAuth()
        val result = transport.call("aletheiaDeleteMyData", AletheiaPayloads.deleteMyData(source, clientInfo))
        val parsed = AletheiaPayloads.parseDelete(result)
        if (!parsed.ok) throw AletheiaError.Backend("Delete request rejected")
        return parsed
    }

    /**
     * Play purchase verification - verify-before-grant (server: aletheiaVerifyPlayPurchase).
     * Request: productId, purchaseToken, packageName "it.grg.flighttimeapp", platform "android".
     * Success: { ok: true, plan, creditsRemaining, ... }. NOT_FOUND = callable not deployed:
     * the purchase stays pending and nothing is granted locally.
     */
    override suspend fun verifyPlayPurchase(productId: String, purchaseToken: String): AletheiaPurchaseResult {
        ensureAuth()
        return try {
            AletheiaPayloads.parsePurchase(
                transport.call(
                    "aletheiaVerifyPlayPurchase",
                    AletheiaPayloads.verifyPlayPurchase(productId, purchaseToken, clientInfo)
                )
            )
        } catch (e: AletheiaError.Backend) {
            if (e.message?.contains("NOT_FOUND", ignoreCase = true) == true ||
                e.message?.contains("404", ignoreCase = true) == true
            ) {
                AletheiaPurchaseResult(ok = false, plan = null, creditsRemaining = null, notDeployed = true)
            } else {
                throw e
            }
        }
    }

    companion object {
        const val PACKAGE_NAME = "it.grg.flighttimeapp"

        private suspend fun anonymousAuth(context: Context): String = suspendCoroutine { cont ->
            CrewAuthManager.ensureSignedInDetailed(context) { result ->
                if (result.isSuccess && !result.uid.isNullOrBlank()) {
                    cont.resume(result.uid!!)
                } else {
                    cont.resumeWithException(AletheiaError.Network(result.message ?: "Sign-in required"))
                }
            }
        }

        fun mapException(e: Exception): Exception {
            val functionsEx = e as? FirebaseFunctionsException
                ?: (e.cause as? FirebaseFunctionsException)
            if (functionsEx != null) {
                val msg = functionsEx.message.orEmpty()
                // firebase-functions 22.x reports a failed connection (an IOException from OkHttp)
                // with code INTERNAL too; only the IOException cause tells it apart from a server 500.
                val transportFailure = functionsEx.cause is java.io.IOException
                return mapFunctionsError(functionsEx.code.name, msg, transportFailure)
            }
            return if (e is AletheiaError) e else AletheiaError.Network(e.message ?: e.toString())
        }

        /**
         * Pure mapping of a callable error (code name + message), unit-tested.
         * [transportFailure] = the request never got an HTTP answer (network problem).
         */
        fun mapFunctionsError(code: String, message: String, transportFailure: Boolean = false): AletheiaError {
            val lower = message.lowercase()
            return when {
                // Network problem: keeps the text it had before (no server-problem copy).
                transportFailure -> AletheiaError.Network(message.ifBlank { code })
                lower.contains("consent choice required") -> AletheiaError.ConsentRequired()
                code == "RESOURCE_EXHAUSTED" ||
                    lower.contains("daily online limit") ||
                    lower.contains("limit") ||
                    lower.contains("quota") -> AletheiaError.DailyLimitReached()
                code == "NOT_FOUND" -> AletheiaError.Backend("NOT_FOUND")
                code == "UNAUTHENTICATED" -> AletheiaError.Network("Sign in required.")
                // HTTP 500 / unmapped status: the server failed while the network worked.
                code == "INTERNAL" || code == "UNKNOWN" -> AletheiaError.ServiceProblem(message.ifBlank { code })
                else -> AletheiaError.Backend(message.ifBlank { code })
            }
        }
    }
}

/**
 * Exact request bodies and response parsing for each callable. Kept pure (no Android
 * types) so contract tests can compare them with the server in functions/aletheia.js.
 */
object AletheiaPayloads {
    /** Server sanitizeClientRequestId (functions/aletheia.js): [a-zA-Z0-9_-], 8..64 chars. */
    private val requestIdAllowed = Regex("[^a-zA-Z0-9_-]")

    fun sanitizeClientRequestId(raw: String): String {
        val id = raw.trim().take(64).replace(requestIdAllowed, "")
        return if (id.length >= 8) id else ""
    }

    fun setConsent(granted: Boolean, source: String): Map<String, Any?> = linkedMapOf(
        "granted" to granted,
        "source" to source.take(64)
    )

    fun chat(request: AletheiaChatRequest, info: AletheiaClientInfo): Map<String, Any?> {
        val history = request.history
            .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
            .takeLast(AletheiaChatRequest.MAX_HISTORY)
            .map { linkedMapOf("role" to it.role, "content" to it.content) }
        val payload = linkedMapOf<String, Any?>(
            "message" to request.message,
            "history" to history,
            "locale" to request.locale,
            "preferHaiku" to request.preferHaiku
        )
        val rid = sanitizeClientRequestId(request.clientRequestId)
        if (rid.isNotEmpty()) payload["clientRequestId"] = rid
        payload.putAll(info.provenance())
        return payload
    }

    fun logTurn(turn: AletheiaTurnLog, info: AletheiaClientInfo): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "messageId" to turn.messageId.take(64),
            "prompt" to turn.prompt,
            "answer" to turn.answer,
            "mode" to turn.mode,
            "locale" to turn.locale
        )
        payload.putAll(info.provenance())
        turn.engine?.let { engine ->
            payload["engine"] = engine.apiEngine
            engine.modelRoute?.let { payload["modelRoute"] = it }
        }
        return payload
    }

    fun feedback(fb: AletheiaFeedbackPayload, info: AletheiaClientInfo): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "messageId" to fb.messageId.take(64),
            "rating" to fb.rating.apiValue,
            "prompt" to fb.prompt,
            "answer" to fb.answer,
            "mode" to fb.mode,
            "locale" to fb.locale
        )
        fb.note?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["note"] = it }
        payload.putAll(info.provenance())
        fb.engine?.let { engine ->
            payload["engine"] = engine.apiEngine
            engine.modelRoute?.let { payload["modelRoute"] = it }
        }
        return payload
    }

    fun deleteMyData(source: String, info: AletheiaClientInfo): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>("source" to source.take(64))
        payload.putAll(info.provenance())
        return payload
    }

    fun verifyPlayPurchase(productId: String, purchaseToken: String, info: AletheiaClientInfo): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "productId" to productId,
            "purchaseToken" to purchaseToken,
            "packageName" to AletheiaApi.PACKAGE_NAME
        )
        payload.putAll(info.provenance())
        return payload
    }

    fun parseStatus(data: Map<String, Any?>): AletheiaStatus = AletheiaStatus(
        trainingConsent = data["trainingConsent"] as? Boolean,
        plan = (data["plan"] as? String)?.takeIf { it.isNotBlank() } ?: "free",
        dailyUsed = intOf(data["dailyUsed"], 0),
        dailyLimit = intOf(data["dailyLimit"], AletheiaPrefs.FREE_DAILY_LIMIT),
        remainingDaily = intOf(data["remainingDaily"], AletheiaPrefs.FREE_DAILY_LIMIT),
        remainingHaiku = intOf(data["remainingHaiku"], 0),
        haikuMonthlyLimit = intOf(data["haikuMonthlyLimit"], 0),
        creditsRemaining = intOf(data["creditsRemaining"], 0)
    )

    fun parseChat(data: Map<String, Any?>, clientRequestId: String): AletheiaChatResult {
        val route = data["route"] as? String
        val rawAnswer = data["answer"] as? String
            ?: throw AletheiaError.Backend("Invalid response from Aletheia.")
        val source = AletheiaSource.fromServer(data["source"] as? String, route)
        // Server guarded answers (manuals package / refuse-invent) are deterministic: no model ran.
        val engine = if (source == AletheiaSource.MANUALS) AletheiaEngine.DETERMINISTIC else AletheiaEngine.ONLINE
        return AletheiaChatResult(
            messageId = clientRequestId,
            answer = AletheiaAnswerFormatter.sanitize(rawAnswer, forOnline = true),
            brand = (data["brand"] as? String) ?: AletheiaFeature.BRAND,
            source = if (source == AletheiaSource.OFFLINE) AletheiaSource.ONLINE else source,
            route = route,
            remainingDaily = intOf(data["remainingDaily"], 0),
            remainingHaiku = intOf(data["remainingHaiku"], 0),
            creditsRemaining = intOf(data["creditsRemaining"], 0),
            dailyUsed = intOf(data["dailyUsed"], 0),
            dailyLimit = intOf(data["dailyLimit"], AletheiaPrefs.FREE_DAILY_LIMIT),
            trainingConsent = data["trainingConsent"] as? Boolean ?: false,
            engine = engine
        )
    }

    fun parseDelete(data: Map<String, Any?>): AletheiaDeleteResult = AletheiaDeleteResult(
        ok = data["ok"] as? Boolean ?: false,
        alreadyDeleted = data["alreadyDeleted"] as? Boolean ?: false,
        turnsTombstoned = intOf(data["turnsTombstoned"], 0),
        feedbackTombstoned = intOf(data["feedbackTombstoned"], 0),
        cachedAnswersDeleted = intOf(data["cachedAnswersDeleted"], 0)
    )

    fun parsePurchase(data: Map<String, Any?>): AletheiaPurchaseResult = AletheiaPurchaseResult(
        ok = data["ok"] as? Boolean ?: false,
        plan = data["plan"] as? String,
        creditsRemaining = (data["creditsRemaining"] as? Number)?.toInt()
    )

    private fun intOf(raw: Any?, default: Int): Int = (raw as? Number)?.toInt() ?: default
}
