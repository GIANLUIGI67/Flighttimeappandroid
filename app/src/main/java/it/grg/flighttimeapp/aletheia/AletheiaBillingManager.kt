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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Play Billing for Aletheia Plus / Pro / Max + credit packs.
 * Entitlements are granted only after [AletheiaApi.verifyPlayPurchase] succeeds.
 * Credit packs are consumed after a verified grant so they can be bought again.
 */
class AletheiaBillingManager(
    context: Context,
    private val prefs: AletheiaPrefs,
    private val api: AletheiaApi,
    private val scope: CoroutineScope
) {
    interface Listener {
        fun onProductsUpdated(products: List<ProductDetails>)
        /** Restored / refreshed owned purchases — must not close the paywall. */
        fun onPurchasesRestored(activePlanId: String?, creditsHint: Int)
        /** A newly verified purchase completed — paywall may continue/finish. */
        fun onPurchaseCompleted(activePlanId: String?, creditsHint: Int)
        fun onBillingMessage(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    var listener: Listener? = null

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                scope.launch(Dispatchers.IO) {
                    handlePurchases(purchases, fromUserPurchase = true)
                }
            } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                listener?.onBillingMessage(result.debugMessage.ifBlank { "Billing error ${result.responseCode}" })
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

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

    fun launchPurchase(activity: Activity, productId: String) {
        val details = productDetails.firstOrNull { it.productId == productId }
        if (details == null) {
            listener?.onBillingMessage("Product not available yet in Play Console: $productId")
            return
        }
        if (!client.isReady) {
            listener?.onBillingMessage("Billing not ready — reconnecting…")
            connectBilling()
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply {
                details.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let { setOfferToken(it) }
            }
            .build()
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val launchResult = client.launchBillingFlow(activity, flow)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            listener?.onBillingMessage(
                launchResult.debugMessage.ifBlank { "Unable to start purchase (${launchResult.responseCode})" }
            )
        }
    }

    suspend fun refreshPurchases() {
        val subs = queryPurchases(BillingClient.ProductType.SUBS)
        val inapp = queryPurchases(BillingClient.ProductType.INAPP)
        handlePurchases(subs + inapp, fromUserPurchase = false)
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
                    listener?.onBillingMessage(billingResult.debugMessage)
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
            listener?.onBillingMessage("Billing disconnected. Close and reopen plans to retry.")
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
            val listener = ProductDetailsResponseListener { result, details ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(details.orEmpty())
                } else {
                    Log.w(TAG, "queryProductDetails ${result.debugMessage}")
                    cont.resume(emptyList())
                }
            }
            client.queryProductDetailsAsync(params, listener)
        }
    }

    private suspend fun queryPurchases(type: String): List<Purchase> {
        return suspendCancellableCoroutine { cont ->
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            client.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(purchases)
                } else {
                    cont.resume(emptyList())
                }
            }
        }
    }

    private suspend fun handlePurchases(purchases: List<Purchase>, fromUserPurchase: Boolean) {
        var plan: String? = null
        var credits = prefs.cachedCreditsRemaining
        var grantedSomething = false

        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val token = purchase.purchaseToken
            for (pid in purchase.products) {
                val alreadyGranted = prefs.isPurchaseTokenGranted(token)
                if (alreadyGranted) {
                    if (AletheiaProducts.isSubscription(pid)) {
                        plan = pid
                        acknowledge(purchase)
                    } else {
                        // Ensure consumables are released so the pack can be bought again.
                        consume(purchase)
                    }
                    continue
                }

                val verified = try {
                    api.verifyPlayPurchase(pid, token)
                } catch (e: Exception) {
                    Log.w(TAG, "verify failed for $pid: ${e.message}")
                    false
                }
                if (!verified) {
                    listener?.onBillingMessage(
                        "Purchase pending server verification (aletheiaVerifyPlayPurchase). Entitlement not granted yet."
                    )
                    continue
                }

                prefs.markPurchaseTokenGranted(token)
                grantedSomething = true
                if (AletheiaProducts.isSubscription(pid)) {
                    plan = pid
                    acknowledge(purchase)
                } else {
                    credits += AletheiaProducts.creditsForPack(pid)
                    consume(purchase)
                }
            }
        }

        // Derive plan only from current Play results (clear expired/canceled).
        // If we saw no active sub in this batch, clear cached plan.
        val hasActiveSubInBatch = purchases.any { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                purchase.products.any { AletheiaProducts.isSubscription(it) }
        }
        if (!hasActiveSubInBatch) {
            plan = null
        }
        prefs.activePlanId = plan
        prefs.cachedCreditsRemaining = credits

        if (fromUserPurchase && grantedSomething) {
            listener?.onPurchaseCompleted(plan, credits)
        } else {
            listener?.onPurchasesRestored(plan, credits)
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
