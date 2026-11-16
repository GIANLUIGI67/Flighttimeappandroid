package it.grg.flighttimeapp.aletheia

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Play Billing for Aletheia Plus / Pro / Max + credit packs (`aletheia_*` product IDs).
 * Entitlements are granted only after [AletheiaApi.verifyPlayPurchase] succeeds
 * (callable `aletheiaVerifyPlayPurchase` on flighttimeapp-6f816 — see API KDoc).
 * Credit packs are consumed after a verified grant so they can be bought again.
 */
class AletheiaBillingManager(
    context: Context,
    private val prefs: AletheiaPrefs,
    private val api: AletheiaBackend,
    private val scope: CoroutineScope
) {
    interface Listener {
        fun onProductsUpdated(products: List<ProductDetails>)
        /** Restored / refreshed owned purchases — must not close the paywall. */
        fun onPurchasesRestored(activePlanId: String?, creditsHint: Int)
        /** A newly verified purchase completed — paywall may continue/finish. */
        fun onPurchaseCompleted(activePlanId: String?, creditsHint: Int)
        /** User-facing notice; the Activity maps it to a localized string (no internal names). */
        fun onBillingNotice(notice: AletheiaBillingNotice, detail: String? = null)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val purchasesMutex = Mutex()
    var listener: Listener? = null

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                scope.launch(Dispatchers.IO) {
                    // Purchase-updated callbacks are often credit-only or single-SKU;
                    // never clear an active subscription from a partial batch.
                    handlePurchases(
                        purchases = purchases,
                        fromUserPurchase = true,
                        allowPlanClear = false
                    )
                }
            } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                listener?.onBillingNotice(AletheiaBillingNotice.BILLING_ERROR, result.responseCode.toString())
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    private val grantLogic = AletheiaPurchaseGrantLogic(prefs) { pid, token -> api.verifyPlayPurchase(pid, token) }
    private var productDetails: List<ProductDetails> = emptyList()
    private var reconnectAttempts = 0
    private var destroyed = false

    fun start() {
        destroyed = false
        connectBilling()
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { client.endConnection() }
    }

    fun products(): List<ProductDetails> = productDetails

    /** Store price as Play formats it: base plan's recurring phase for subscriptions, one-time price for packs. */
    fun formattedPrice(productId: String): String? {
        val details = productDetails.firstOrNull { it.productId == productId } ?: return null
        details.oneTimePurchaseOfferDetails?.formattedPrice?.let { return it }
        val offers = details.subscriptionOfferDetails ?: return null
        val offer = offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull() ?: return null
        return offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice
    }

    fun launchPurchase(activity: Activity, productId: String) {
        val details = productDetails.firstOrNull { it.productId == productId }
        if (details == null) {
            listener?.onBillingNotice(AletheiaBillingNotice.PRODUCT_UNAVAILABLE)
            return
        }
        if (!client.isReady) {
            listener?.onBillingNotice(AletheiaBillingNotice.RECONNECTING)
            connectBilling()
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply {
                details.subscriptionOfferDetails
                    ?.map { it.offerId to it.offerToken }
                    ?.let { AletheiaUiRules.chooseOfferToken(it) }
                    ?.let { setOfferToken(it) }
            }
            .build()
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val launchResult = client.launchBillingFlow(activity, flow)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            listener?.onBillingNotice(AletheiaBillingNotice.LAUNCH_FAILED, launchResult.responseCode.toString())
        }
    }

    suspend fun refreshPurchases() {
        val subs = queryPurchases(BillingClient.ProductType.SUBS)
        val inapp = queryPurchases(BillingClient.ProductType.INAPP)
        val combined = buildList {
            if (subs.ok) addAll(subs.purchases)
            if (inapp.ok) addAll(inapp.purchases)
        }
        // Only clear expired/canceled plans when subscription inventory query succeeded.
        // A failed SUBS query must not wipe a cached active plan.
        handlePurchases(
            purchases = combined,
            fromUserPurchase = false,
            allowPlanClear = subs.ok
        )
        if (!subs.ok) {
            listener?.onBillingNotice(AletheiaBillingNotice.SUBS_STATUS_UNAVAILABLE)
        }
    }

    private fun connectBilling() {
        if (destroyed) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconnectAttempts = 0
                    scope.launch(Dispatchers.IO) {
                        queryProducts()
                        refreshPurchases()
                    }
                } else {
                    listener?.onBillingNotice(AletheiaBillingNotice.BILLING_ERROR, billingResult.responseCode.toString())
                    scheduleReconnect()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            listener?.onBillingNotice(AletheiaBillingNotice.DISCONNECTED)
            return
        }
        val delayMs = min(1_000L * (1L shl reconnectAttempts), 8_000L)
        reconnectAttempts++
        mainHandler.postDelayed({ connectBilling() }, delayMs)
    }

    private suspend fun queryProducts() {
        val subs = queryDetails(AletheiaProducts.SUBSCRIPTION_IDS, BillingClient.ProductType.SUBS)
        val packs = queryDetails(AletheiaProducts.CREDIT_PACK_IDS, BillingClient.ProductType.INAPP)
        productDetails = subs + packs
        listener?.onProductsUpdated(productDetails)
    }

    private suspend fun queryDetails(ids: List<String>, type: String): List<ProductDetails> {
        if (ids.isEmpty()) return emptyList()
        val productList = ids.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(type)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        return suspendCancellableCoroutine { cont ->
            // PBL 8+: listener receives QueryProductDetailsResult (not List<ProductDetails>).
            val listener = ProductDetailsResponseListener { result, productDetailsResult ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(productDetailsResult.productDetailsList.orEmpty())
                } else {
                    Log.w(TAG, "queryProductDetails ${result.debugMessage}")
                    cont.resume(emptyList())
                }
            }
            client.queryProductDetailsAsync(params, listener)
        }
    }

    private data class PurchasesQuery(val ok: Boolean, val purchases: List<Purchase>)

    private suspend fun queryPurchases(type: String): PurchasesQuery {
        return suspendCancellableCoroutine { cont ->
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            client.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(PurchasesQuery(ok = true, purchases = purchases))
                } else {
                    Log.w(TAG, "queryPurchases($type) failed: ${result.debugMessage}")
                    cont.resume(PurchasesQuery(ok = false, purchases = emptyList()))
                }
            }
        }
    }

    private suspend fun handlePurchases(
        purchases: List<Purchase>,
        fromUserPurchase: Boolean,
        allowPlanClear: Boolean
    ) = purchasesMutex.withLock {
        val byToken = purchases.associateBy { it.purchaseToken }
        val outcome = grantLogic.process(
            purchases = purchases.map {
                AletheiaOwnedPurchase(
                    token = it.purchaseToken,
                    products = it.products,
                    purchased = it.purchaseState == Purchase.PurchaseState.PURCHASED
                )
            },
            allowPlanClear = allowPlanClear
        )
        outcome.notices.forEach { listener?.onBillingNotice(it) }
        outcome.acknowledgeTokens.forEach { t -> byToken[t]?.let { acknowledge(it) } }
        outcome.consumeTokens.forEach { t -> byToken[t]?.let { consume(it) } }

        if (fromUserPurchase && outcome.grantedSomething) {
            listener?.onPurchaseCompleted(outcome.plan, outcome.credits)
        } else {
            listener?.onPurchasesRestored(outcome.plan, outcome.credits)
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "ack failed: ${result.debugMessage}")
            }
        }
    }

    private fun consume(purchase: Purchase) {
        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.consumeAsync(params) { result, _ ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "consume failed: ${result.debugMessage}")
            }
        }
    }

    companion object {
        private const val TAG = "AletheiaBilling"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
}

/** Billing states shown to the user (mapped to string resources by the paywall). */
enum class AletheiaBillingNotice {
    PRODUCT_UNAVAILABLE,
    RECONNECTING,
    LAUNCH_FAILED,
    VERIFY_PENDING,
    SUBS_STATUS_UNAVAILABLE,
    DISCONNECTED,
    BILLING_ERROR
}

/** A Play purchase reduced to what the grant decision needs (testable without Play). */
data class AletheiaOwnedPurchase(val token: String, val products: List<String>, val purchased: Boolean)

/**
 * Verify-before-grant decision (CT-BUY-SUBSCRIPTION / CT-BUY-CREDITS / CT-PURCHASE-VERIFY /
 * CT-RESTORE). Nothing is granted unless aletheiaVerifyPlayPurchase confirms it; a token is
 * granted once; the server credit balance wins over the local estimate; subscriptions are
 * acknowledged, credit packs consumed; a failed inventory never clears an active plan.
 */
class AletheiaPurchaseGrantLogic(
    private val prefs: AletheiaPrefs,
    private val verify: suspend (productId: String, token: String) -> AletheiaPurchaseResult
) {
    data class Outcome(
        val plan: String?,
        val credits: Int,
        val grantedSomething: Boolean,
        val acknowledgeTokens: List<String>,
        val consumeTokens: List<String>,
        val notices: List<AletheiaBillingNotice>
    )

    suspend fun process(purchases: List<AletheiaOwnedPurchase>, allowPlanClear: Boolean): Outcome {
        var plan: String? = null
        var sawSubscription = false
        var credits = prefs.cachedCreditsRemaining
        var serverCredits: Int? = null
        var grantedSomething = false
        val ack = linkedSetOf<String>()
        val consume = linkedSetOf<String>()
        val notices = mutableListOf<AletheiaBillingNotice>()

        for (purchase in purchases) {
            if (!purchase.purchased) continue
            val token = purchase.token
            for (pid in purchase.products) {
                if (prefs.isPurchaseTokenGranted(token)) {
                    if (AletheiaProducts.isSubscription(pid)) {
                        plan = pid
                        sawSubscription = true
                        ack += token
                        // Founder 16/11/2026: the server enforces subscription expiry. Play keeps the same token
                        // across renewals, so re-verify it on every refresh to give the server the new expiry.
                        // Nothing is granted twice: the token is already marked granted.
                        val refreshed = try {
                            verify(pid, token)
                        } catch (_: Exception) {
                            null
                        }
                        if (refreshed?.ok == true) serverCredits = refreshed.creditsRemaining ?: serverCredits
                    } else {
                        // Ensure consumables are released so the pack can be bought again.
                        consume += token
                    }
                    continue
                }

                val verification = try {
                    verify(pid, token)
                } catch (_: Exception) {
                    null
                }
                if (verification == null || !verification.ok) {
                    notices += AletheiaBillingNotice.VERIFY_PENDING
                    continue
                }
                serverCredits = verification.creditsRemaining ?: serverCredits

                // Atomic mark: overlapping refresh/purchase callbacks must not double-grant.
                if (!prefs.markPurchaseTokenGranted(token)) {
                    if (AletheiaProducts.isSubscription(pid)) {
                        plan = pid
                        sawSubscription = true
                    }
                    continue
                }

                grantedSomething = true
                if (AletheiaProducts.isSubscription(pid)) {
                    plan = pid
                    sawSubscription = true
                    ack += token
                } else {
                    // The server granted the pack; its balance is the truth when it sends one.
                    credits = serverCredits ?: (credits + AletheiaProducts.creditsForPack(pid))
                    consume += token
                }
            }
        }

        if (sawSubscription) {
            prefs.activePlanId = plan
        } else if (allowPlanClear) {
            // Full successful SUBS inventory with no active subscription: expired/canceled.
            plan = null
            prefs.activePlanId = null
        } else {
            // Partial purchase callback or failed SUBS query: keep cached plan.
            plan = prefs.activePlanId
        }
        prefs.cachedCreditsRemaining = credits
        return Outcome(plan, credits, grantedSomething, ack.toList(), consume.toList(), notices.distinct())
    }
}
