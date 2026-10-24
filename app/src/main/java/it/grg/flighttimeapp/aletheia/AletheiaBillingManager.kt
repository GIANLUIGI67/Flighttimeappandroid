package it.grg.flighttimeapp.aletheia

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
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

/**
 * Play Billing for Aletheia Plus / Pro / Max + credit packs.
 * Server entitlement unlock requires Cloud Function aletheiaVerifyPlayPurchase
 * (not deployed yet at port time) — tokens are still acknowledged locally and
 * forwarded when the function becomes available.
 */
class AletheiaBillingManager(
    context: Context,
    private val prefs: AletheiaPrefs,
    private val api: AletheiaApi,
    private val scope: CoroutineScope
) {
    interface Listener {
        fun onProductsUpdated(products: List<ProductDetails>)
        fun onPurchasesUpdated(activePlanId: String?, creditsHint: Int)
        fun onBillingMessage(message: String)
    }

    private val appContext = context.applicationContext
    var listener: Listener? = null

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                handlePurchases(purchases)
            } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                listener?.onBillingMessage(result.debugMessage.ifBlank { "Billing error ${result.responseCode}" })
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    private var productDetails: List<ProductDetails> = emptyList()

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch(Dispatchers.IO) {
                        queryProducts()
                        refreshPurchases()
                    }
                } else {
                    listener?.onBillingMessage(billingResult.debugMessage)
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected")
            }
        })
    }

    fun destroy() {
        runCatching { client.endConnection() }
    }

    fun products(): List<ProductDetails> = productDetails

    fun launchPurchase(activity: Activity, productId: String) {
        val details = productDetails.firstOrNull { it.productId == productId }
        if (details == null) {
            listener?.onBillingMessage("Product not available yet in Play Console: $productId")
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
        client.launchBillingFlow(activity, flow)
    }

    suspend fun refreshPurchases() {
        val subs = queryPurchases(BillingClient.ProductType.SUBS)
        val inapp = queryPurchases(BillingClient.ProductType.INAPP)
        handlePurchases(subs + inapp)
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

    private fun handlePurchases(purchases: List<Purchase>) {
        var plan: String? = prefs.activePlanId
        var credits = prefs.cachedCreditsRemaining
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            for (pid in purchase.products) {
                if (AletheiaProducts.isSubscription(pid)) {
                    plan = pid
                } else {
                    credits += AletheiaProducts.creditsForPack(pid)
                }
                acknowledge(purchase)
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        api.verifyPlayPurchase(pid, purchase.purchaseToken)
                    }.onFailure {
                        Log.i(TAG, "Play verify deferred (function may be missing): ${it.message}")
                    }
                }
            }
        }
        prefs.activePlanId = plan
        prefs.cachedCreditsRemaining = credits
        listener?.onPurchasesUpdated(plan, credits)
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

    companion object {
        private const val TAG = "AletheiaBilling"
    }
}
