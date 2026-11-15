package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AletheiaProductsTest {
    @Test
    fun playProductIdsMatchAletheiaSkuPlan_literals() {
        assertEquals("aletheia_plus_monthly", AletheiaProducts.PLUS_MONTHLY)
        assertEquals("aletheia_pro_monthly", AletheiaProducts.PRO_MONTHLY)
        assertEquals("aletheia_max_monthly", AletheiaProducts.MAX_MONTHLY)
        assertEquals("aletheia_credits_s", AletheiaProducts.CREDITS_S)
        assertEquals("aletheia_credits_m", AletheiaProducts.CREDITS_M)
        assertEquals("aletheia_credits_l", AletheiaProducts.CREDITS_L)
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
        assertTrue(AletheiaProducts.isSubscription(AletheiaProducts.PLUS_MONTHLY))
        assertTrue(AletheiaProducts.isSubscription(AletheiaProducts.PRO_MONTHLY))
        assertTrue(AletheiaProducts.isSubscription(AletheiaProducts.MAX_MONTHLY))
        assertFalse(AletheiaProducts.isSubscription(AletheiaProducts.CREDITS_S))
        assertEquals(50, AletheiaProducts.creditsForPack(AletheiaProducts.CREDITS_S))
        assertEquals(150, AletheiaProducts.creditsForPack(AletheiaProducts.CREDITS_M))
        assertEquals(400, AletheiaProducts.creditsForPack(AletheiaProducts.CREDITS_L))
        assertEquals(0, AletheiaProducts.creditsForPack("unknown_sku"))
    }
}
