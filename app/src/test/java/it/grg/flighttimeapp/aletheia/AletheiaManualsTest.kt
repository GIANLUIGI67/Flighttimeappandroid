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
        assertFalse(body.startsWith("Aletheia Beta"))
        assertTrue(body.contains("V1 — Decision speed"))
        assertTrue(body.contains("Study aid only"))
        assertTrue(body.contains("AFM"))
        assertTrue(body.contains("FCOM"))
        assertTrue(body.contains("SOP"))
        assertFalse(body.contains("rejected take-off) remains the primary option"))
        assertFalse(body.lowercase().contains("abort below v1"))
        assertFalse(body.lowercase().contains("continue above v1"))
        assertFalse(body.lowercase().contains("reject below"))
        assertFalse(body.lowercase().contains("continue at or above"))
    }

    @Test
    fun lookup_fuel_doesNotInventCapacity_pointsToAfm() {
        val hit = AletheiaManuals.lookup("Quali sono le limitazioni del carburante per a320 neo?")
        assertNotNull(hit)
        assertEquals(AletheiaSource.MANUALS, hit!!.second)
        val body = hit.first.lowercase()
        assertTrue(body.contains("afm") || body.contains("fcom"))
        assertTrue(body.contains("invent").not() || body.contains("no invented") || body.contains("do not invent") || body.contains("non invent"))
        // Must not ship invented kg/L figures from the bad Firebase sample.
        assertFalse(body.contains("24 000"))
        assertFalse(body.contains("24000"))
        assertTrue(body.contains("fuel"))
    }

    @Test
    fun lookup_flaps_doesNotInventSpeeds() {
        val hit = AletheiaManuals.lookup("What is the flaps limitations for a320 Neo?")
        assertNotNull(hit)
        val body = hit!!.first.lowercase()
        assertFalse(body.contains("15 knots per second"))
        assertTrue(body.contains("afm") || body.contains("fcom"))
        assertTrue(body.contains("vfe") || body.contains("flap"))
    }

    @Test
    fun lookup_ftl_asksForInputs_notInventedFdp() {
        val hit = AletheiaManuals.lookup("What is my FTL legality?")
        assertNotNull(hit)
        val body = hit!!.first
        assertTrue(body.contains("GACA") || body.contains("FAR 117") || body.contains("EASA"))
        assertTrue(body.contains("sectors") || body.contains("settori") || body.contains("report"))
    }

    @Test
    fun lookup_engineOut_studyFraming() {
        val hit = AletheiaManuals.lookup("A320 memory item for engine failure after V1")
        assertNotNull(hit)
        assertTrue(hit!!.first.contains("QRH") || hit.first.contains("memory", ignoreCase = true))
        assertTrue(hit.first.contains("Study aid") || hit.first.contains("study", ignoreCase = true))
    }

    @Test
    fun offDomain_religionAndCode_forOffline() {
        assertTrue(AletheiaManuals.isClearlyOffDomainForOffline("What is the bible?"))
        assertTrue(AletheiaManuals.isClearlyOffDomainForOffline("Scrivi una funzione in python"))
        assertFalse(AletheiaManuals.isClearlyOffDomainForOffline("GACA, 5:00 inizio duty, 4 settori"))
        assertFalse(AletheiaManuals.isClearlyOffDomainForOffline("What is V1?"))
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

    @Test
    fun source_fromServer_mapsRoutes() {
        assertEquals(AletheiaSource.MANUALS, AletheiaSource.fromServer("manuals", null))
        assertEquals(AletheiaSource.MANUALS, AletheiaSource.fromServer(null, "client"))
        assertEquals(AletheiaSource.OFFLINE, AletheiaSource.fromServer(null, "offline_local"))
        assertEquals(AletheiaSource.ONLINE, AletheiaSource.fromServer(null, "groq"))
    }
}
