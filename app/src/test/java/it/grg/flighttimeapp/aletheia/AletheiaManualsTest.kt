package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AletheiaManualsTest {
    @Test
    fun lookup_v1_isStudyAid_pointsToAfmFcomSop_notPrescriptiveOps() {
        val hit = AletheiaManuals.lookup("What is V1 decision speed?")
        assertNotNull(hit)
        assertEquals(AletheiaSource.MANUALS, hit!!.second)
        val body = hit.first
        assertTrue(body.contains("Aletheia Beta"))
        assertTrue(body.contains("V1 — Decision speed"))
        assertTrue(body.contains("Study aid only"))
        assertTrue(body.contains("AFM"))
        assertTrue(body.contains("FCOM"))
        assertTrue(body.contains("SOP"))
        // Must not ship legacy reject/continue operational shortcuts.
        assertFalse(body.contains("rejected take-off) remains the primary option"))
        assertFalse(body.lowercase().contains("abort below v1"))
        assertFalse(body.lowercase().contains("continue above v1"))
        assertFalse(body.lowercase().contains("reject below"))
        assertFalse(body.lowercase().contains("continue at or above"))
    }

    @Test
    fun lookup_unrelated_returnsNull() {
        assertNull(AletheiaManuals.lookup("favourite colour of the sky"))
    }

    @Test
    fun productIds_areLiteralPlaySkus() {
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
