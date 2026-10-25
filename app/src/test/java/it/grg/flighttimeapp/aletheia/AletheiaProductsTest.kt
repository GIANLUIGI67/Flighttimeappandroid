package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AletheiaProductsTest {
    @Test
    fun playProductIdsMatchAletheiaSkuPlan() {
        assertEquals(
            listOf(
                "aletheia_plus_monthly",
                "aletheia_pro_monthly",
                "aletheia_max_monthly",
                "aletheia_credits_s",
                "aletheia_credits_m",
                "aletheia_credits_l"
            ),
            AletheiaProducts.ALL_PRODUCT_IDS
        )
        assertTrue(AletheiaProducts.isSubscription(AletheiaProducts.PRO_MONTHLY))
        assertEquals(50, AletheiaProducts.creditsForPack(AletheiaProducts.CREDITS_S))
        assertEquals(150, AletheiaProducts.creditsForPack(AletheiaProducts.CREDITS_M))
        assertEquals(400, AletheiaProducts.creditsForPack(AletheiaProducts.CREDITS_L))
    }
}
