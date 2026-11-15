package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CT-OFF-STUDY-AIDS / CT-OFF-MEMORY-ITEM / CT-OFF-LIMITATIONS-PACKAGE / CT-OFF-FTL-MISSING-INPUTS: one ask per card. */
class AletheiaStudyCardsTest {
    private fun title(q: String): String? = AletheiaManuals.lookup(q)?.first?.substringBefore("\n")

    @Test
    fun everyStudyCard_isReachable() {
        val cases = linkedMapOf(
            "What is V1?" to "V1 — Decision speed",
            "What does V2 mean?" to "V2 — Take-off safety speed",
            "What is VREF?" to "VREF — Reference landing speed",
            "How do I fly a go-around?" to "Go-around / missed approach",
            "DH vs MDA difference" to "DH vs MDA",
            "What is the sterile cockpit rule?" to "Sterile cockpit",
            "memory items for engine failure" to "Memory items / engine-out — study framing",
            "A320 fuel limitations" to "Fuel limitations — consult AFM / FCOM",
            "Where are the fuel tanks?" to "Fuel limitations — consult AFM / FCOM",
            "flap limit speeds VFE" to "Flap / slat limitations — consult AFM speeds",
            "IRS alignment time" to "IRS / ADIRS limitations — consult AFM / FCOM",
            "A320neo limitations" to "Aircraft limitations — manuals first",
            "GACA FDP with 4 sectors" to "FTL / duty — study framing"
        )
        for ((ask, expected) in cases) assertEquals(ask, expected, title(ask))
    }

    @Test
    fun lettersInsideOtherWords_doNotOpenACard() {
        assertFalse(title("Riyadh airport taxi routes") == "DH vs MDA")
        assertFalse(title("Who flies the first leg?") == "IRS / ADIRS limitations — consult AFM / FCOM")
        assertFalse(title("IAE V2500 engine oil quantity") == "V2 — Take-off safety speed")
        assertFalse(title("Jeddah to Riyadh block time") == "DH vs MDA")
    }

    @Test
    fun containsKeyword_wordStartsAndShortWholeWords() {
        assertTrue(AletheiaManuals.containsKeyword("what is v1?", "v1"))
        assertFalse(AletheiaManuals.containsKeyword("v10 table", "v1"))
        assertFalse(AletheiaManuals.containsKeyword("riyadh", "dh "))
        assertTrue(AletheiaManuals.containsKeyword("dh?", "dh "))
        assertTrue(AletheiaManuals.containsKeyword("fuel tanks", "fuel tank"))
        assertFalse(AletheiaManuals.containsKeyword("first officer", "irs "))
    }

    @Test
    fun cards_neverInventNumbers_andPointToManuals() {
        for (ask in listOf("A320 fuel limitations", "flap limit speeds VFE", "IRS alignment time", "GACA FDP with 4 sectors")) {
            val body = AletheiaManuals.lookup(ask)!!.first
            assertTrue(ask, body.contains("AFM") || body.contains("OM-A") || body.contains("FCOM"))
        }
    }
}
