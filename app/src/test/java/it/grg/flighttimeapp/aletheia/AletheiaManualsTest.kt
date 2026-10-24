package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AletheiaManualsTest {
    @Test
    fun lookup_v1_returnsManualsSource() {
        val hit = AletheiaManuals.lookup("What is V1 decision speed?")
        assertNotNull(hit)
        assertEquals(AletheiaSource.MANUALS, hit!!.second)
        assertTrue(hit.first.contains("Aletheia Beta"))
        assertTrue(hit.first.contains("V1 — Decision speed"))
        assertTrue(hit.first.contains("Study aid only"))
        assertFalse(hit.first.contains("rejected take-off) remains the primary option"))
    }

    @Test
    fun lookup_unrelated_returnsNull() {
        assertNull(AletheiaManuals.lookup("favourite colour of the sky"))
    }

    @Test
    fun productIds_coverPlansAndCredits() {
        assertEquals(6, AletheiaProducts.ALL_PRODUCT_IDS.size)
        assertEquals("aletheia_pro_monthly", AletheiaProducts.PRO_MONTHLY)
        assertEquals("aletheia_credits_m", AletheiaProducts.CREDITS_M)
        assertTrue(AletheiaProducts.isSubscription("aletheia_pro_monthly"))
        assertFalse(AletheiaProducts.isSubscription("aletheia_credits_m"))
        assertEquals(150, AletheiaProducts.creditsForPack("aletheia_credits_m"))
        assertEquals(50, AletheiaProducts.creditsForPack("aletheia_credits_s"))
        assertEquals(400, AletheiaProducts.creditsForPack("aletheia_credits_l"))
    }
}
