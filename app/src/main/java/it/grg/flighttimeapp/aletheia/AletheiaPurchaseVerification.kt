package it.grg.flighttimeapp.aletheia

/** Strict parsing only. Authenticity and atomic grants MUST be enforced by the backend. */
object AletheiaPurchaseVerification {
    fun balance(response: Map<String, Any>, productId: String): Int? {
        if (productId !in AletheiaProducts.ALL_PRODUCT_IDS ||
            response["ok"] != true || response["productId"] != productId ||
            response["grantCommitted"] != true) return null
        val value = response["creditsRemaining"] as? Number ?: return null
        val number = value.toDouble()
        if (!number.isFinite() || number < 0 || number > Int.MAX_VALUE ||
            number != number.toInt().toDouble()) return null
        return number.toInt()
    }
}
