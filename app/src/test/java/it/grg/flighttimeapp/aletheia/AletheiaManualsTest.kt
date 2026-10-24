package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertEquals
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
        assertTrue(hit.first.contains("1."))
    }

    @Test
    fun lookup_unrelated_returnsNull() {
        assertNull(AletheiaManuals.lookup("favourite colour of the sky"))
    }

    @Test
    fun productIds_coverPlansAndCredits() {
        assertEquals(6, AletheiaProducts.ALL_PRODUCT_IDS.size)
        assertTrue(AletheiaProducts.isSubscription(AletheiaProducts.PRO_MONTHLY))
        assertEquals(150, AletheiaProducts.creditsForPack(AletheiaProducts.CREDITS_M))
    }
}
