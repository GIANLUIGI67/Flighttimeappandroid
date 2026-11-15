package it.grg.flighttimeapp.aletheia

/**
 * Play Billing product IDs for package [it.grg.flighttimeapp].
 * Coordinator must create these in Play Console → Monetize (do not publish from this PR).
 *
 * Naming mirrors the iOS Plus / Pro / Max monthly + credits S / M / L set with
 * Play-friendly snake_case IDs. If iOS StoreKit IDs differ, keep these Android IDs
 * stable in Play Console and map server-side.
 */
object AletheiaProducts {
    const val PLUS_MONTHLY = "aletheia_plus_monthly"
    const val PRO_MONTHLY = "aletheia_pro_monthly"
    const val MAX_MONTHLY = "aletheia_max_monthly"

    const val CREDITS_S = "aletheia_credits_s"
    const val CREDITS_M = "aletheia_credits_m"
    const val CREDITS_L = "aletheia_credits_l"

    val SUBSCRIPTION_IDS = listOf(PLUS_MONTHLY, PRO_MONTHLY, MAX_MONTHLY)
    val CREDIT_PACK_IDS = listOf(CREDITS_S, CREDITS_M, CREDITS_L)
    val ALL_PRODUCT_IDS = SUBSCRIPTION_IDS + CREDIT_PACK_IDS

    /**
     * Online replies granted by each pack. Must equal the server table
     * (functions/aletheia_core.js IOS_CREDIT_PACK_AMOUNTS via ANDROID_TO_IOS_PRODUCT_IDS: 40/150/300)
     * and the iOS store texts. The server grants the credits; this value is only shown / used
     * as a fallback when the verify response carries no balance.
     */
    fun creditsForPack(productId: String): Int = when (productId) {
        CREDITS_S -> 40
        CREDITS_M -> 150
        CREDITS_L -> 300
        else -> 0
    }

    /** Server plan id (free/plus/pro/max) for a Play subscription product id. */
    fun planIdFor(productId: String?): String? = when (productId) {
        PLUS_MONTHLY -> "plus"
        PRO_MONTHLY -> "pro"
        MAX_MONTHLY -> "max"
        else -> null
    }

    fun isSubscription(productId: String): Boolean = productId in SUBSCRIPTION_IDS
}
