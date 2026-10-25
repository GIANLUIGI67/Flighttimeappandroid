package it.grg.flighttimeapp.aletheia

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import it.grg.flighttimeapp.crewl.CrewAuthManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin client for existing Firebase Functions on project flighttimeapp-6f816
 * (us-central1): aletheiaSetConsent, aletheiaChat, aletheiaSubmitFeedback, aletheiaLogTurn.
 */
class AletheiaApi(
    private val context: Context,
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("us-central1")
) {
    suspend fun ensureAuth(): String = suspendCoroutine { cont ->
        CrewAuthManager.ensureSignedInDetailed(context) { result ->
            if (result.isSuccess && !result.uid.isNullOrBlank()) {
                cont.resume(result.uid!!)
            } else {
                cont.resumeWithException(
                    AletheiaError.Network(result.message ?: "Sign-in required")
                )
            }
        }
    }

    suspend fun setConsent(granted: Boolean): Boolean {
        ensureAuth()
        val result = call("aletheiaSetConsent", mapOf("granted" to granted))
        val ok = result["ok"] as? Boolean ?: true
        if (!ok) throw AletheiaError.Backend("Consent update rejected")
        return result["trainingConsent"] as? Boolean ?: granted
    }

    suspend fun chat(message: String, messageId: String = UUID.randomUUID().toString()): AletheiaChatResult {
        ensureAuth()
        val result = call(
            "aletheiaChat",
            mapOf(
                "message" to message,
                "messageId" to messageId,
                "platform" to "android",
                "clientVersion" to AletheiaFeature.versionName
            )
        )
        return AletheiaChatResult(
            messageId = messageId,
            answer = (result["answer"] as? String).orEmpty(),
            brand = (result["brand"] as? String) ?: AletheiaFeature.BRAND,
            source = AletheiaSource.fromServer(result["source"] as? String),
            route = result["route"] as? String,
            remainingDaily = (result["remainingDaily"] as? Number)?.toInt() ?: 0,
            remainingHaiku = (result["remainingHaiku"] as? Number)?.toInt() ?: 0,
            creditsRemaining = (result["creditsRemaining"] as? Number)?.toInt() ?: 0,
            dailyUsed = (result["dailyUsed"] as? Number)?.toInt() ?: 0,
            dailyLimit = (result["dailyLimit"] as? Number)?.toInt() ?: AletheiaPrefs.FREE_DAILY_LIMIT,
            trainingConsent = result["trainingConsent"] as? Boolean ?: false
        )
    }

    suspend fun submitFeedback(
        messageId: String,
        rating: AletheiaFeedbackRating,
        note: String? = null
    ): Boolean {
        ensureAuth()
        val payload = mutableMapOf<String, Any>(
            "messageId" to messageId,
            "rating" to rating.apiValue
        )
        if (!note.isNullOrBlank()) {
            payload["note"] = note.trim()
        }
        val result = call("aletheiaSubmitFeedback", payload)
        val ok = result["ok"] as? Boolean ?: false
        if (!ok) throw AletheiaError.Backend("Feedback rejected")
        return true
    }

    /** Security log / offline outbox flush — always allowed; RL eligibility follows server consent. */
    suspend fun logTurn(
        prompt: String,
        answer: String,
        messageId: String,
        source: AletheiaSource
    ): Boolean {
        ensureAuth()
        val result = call(
            "aletheiaLogTurn",
            mapOf(
                "prompt" to prompt,
                "answer" to answer,
                "messageId" to messageId,
                "source" to source.name.lowercase(),
                "route" to source.name.lowercase(),
                "platform" to "android",
                "brand" to AletheiaFeature.BRAND
            )
        )
        return result["ok"] as? Boolean ?: true
    }

    /**
     * Play purchase verification — **verify-before-grant** hook used by [AletheiaBillingManager].
     *
     * ## Callable contract (Firebase project `flighttimeapp-6f816`, region `us-central1`)
     *
     * **Name:** `aletheiaVerifyPlayPurchase` (HTTPS callable)
     *
     * **Auth:** Firebase Auth required (anonymous OK, same as other Aletheia callables).
     *
     * **Request data:**
     * ```
     * {
     *   "productId": string,       // e.g. aletheia_plus_monthly | aletheia_credits_s
     *   "purchaseToken": string,   // Play Billing purchase token
     *   "packageName": string,     // must be it.grg.flighttimeapp
     *   "platform": "android"
     * }
     * ```
     *
     * **Success response:** `{ "ok": true, "productId"?: string, "creditsGranted"?: number }`
     * Client treats truthy `ok` as verified. Optional `creditsGranted` is ignored today
     * (client uses [AletheiaProducts.creditsForPack] locally after verify).
     *
     * **Failure:** `{ "ok": false, "error"?: string }` or Functions error codes
     * `INVALID_ARGUMENT` / `PERMISSION_DENIED` / `FAILED_PRECONDITION`.
     *
     * **Server duties (not in this Android repo):**
     * 1. Call Google Play Developer API `purchases.subscriptionsv2.get` or
     *    `purchases.products.get` with a Play Console service account.
     * 2. Confirm packageName, productId, purchase state PURCHASED, and token uniqueness.
     * 3. Persist entitlement / credit ledger keyed by uid + purchaseToken (idempotent).
     *
     * TODO(backend): Deploy `aletheiaVerifyPlayPurchase` on `flighttimeapp-6f816`.
     * Until then this client returns false on NOT_FOUND and does **not** grant locally.
     * Do **not** invent a second Firebase project.
     */
    suspend fun verifyPlayPurchase(
        productId: String,
        purchaseToken: String,
        packageName: String = "it.grg.flighttimeapp"
    ): Boolean {
        ensureAuth()
        return try {
            // Client hook ready — callable name and payload match the contract above.
            val result = call(
                "aletheiaVerifyPlayPurchase",
                mapOf(
                    "productId" to productId,
                    "purchaseToken" to purchaseToken,
                    "packageName" to packageName,
                    "platform" to "android"
                )
            )
            result["ok"] as? Boolean ?: false
        } catch (e: AletheiaError.Backend) {
            if (e.message?.contains("NOT_FOUND", ignoreCase = true) == true ||
                e.message?.contains("404", ignoreCase = true) == true
            ) {
                // Function not deployed yet on flighttimeapp-6f816 — keep purchase pending.
                false
            } else {
                throw e
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun call(name: String, data: Map<String, Any>): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            try {
                val task = functions.getHttpsCallable(name).call(data)
                val snapshot = Tasks.await(task, 120, TimeUnit.SECONDS)
                val raw = snapshot.data
                when (raw) {
                    is Map<*, *> -> raw as Map<String, Any>
                    else -> emptyMap()
                }
            } catch (e: Exception) {
                throw mapException(e)
            }
        }
    }

    private fun mapException(e: Exception): Exception {
        val functionsEx = e as? FirebaseFunctionsException
            ?: (e.cause as? FirebaseFunctionsException)
        if (functionsEx != null) {
            val msg = functionsEx.message.orEmpty()
            return when {
                msg.contains("Consent choice required", ignoreCase = true) ->
                    AletheiaError.ConsentRequired()
                msg.contains("limit", ignoreCase = true) ||
                    msg.contains("quota", ignoreCase = true) ||
                    functionsEx.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    AletheiaError.DailyLimitReached()
                functionsEx.code == FirebaseFunctionsException.Code.NOT_FOUND ->
                    AletheiaError.Backend("NOT_FOUND")
                functionsEx.code == FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    AletheiaError.Network("Sign in required.")
                else -> AletheiaError.Backend(msg.ifBlank { functionsEx.code.name })
            }
        }
        return if (e is AletheiaError) e else AletheiaError.Network(e.message ?: e.toString())
    }
}
