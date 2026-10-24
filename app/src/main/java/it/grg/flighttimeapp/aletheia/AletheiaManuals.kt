package it.grg.flighttimeapp.aletheia

/**
 * Lightweight on-device aviation manuals index for Offline / Manuali badges.
 * Full GGUF parity is handled by [AletheiaOfflineEngine]; this layer provides
 * manuals-first answers when a clear domain match exists.
 */
object AletheiaManuals {
    data class Entry(val keywords: List<String>, val title: String, val body: String)

    private val entries = listOf(
        Entry(
            keywords = listOf("v1", "decision speed"),
            title = "V1 — Decision speed",
            body = """
                1. V1 is the take-off decision speed.
                2. Below V1 an abort (rejected take-off) remains the primary option if a critical failure occurs.
                3. At or above V1 the take-off is continued and treated as a continued take-off with engine failure procedures as applicable.
                4. Always use the AFM / FCOM performance data for the runway, weight and conditions of the day.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("v2", "take-off safety", "takeoff safety"),
            title = "V2 — Take-off safety speed",
            body = """
                1. V2 is the take-off safety speed used for the initial climb with one engine inoperative.
                2. It provides the required climb gradient and obstacle clearance for the certified profile.
                3. Target V2 by screen height as published in the AFM performance section.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("vref", "reference landing", "landing speed"),
            title = "VREF — Reference landing speed",
            body = """
                1. VREF is the reference landing approach speed in the landing configuration.
                2. Operators add corrections for wind, ice, and malfunctions as per the AFM / FCOM.
                3. Stabilised approach criteria still apply before continuing to land.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("go-around", "go around", "missed approach"),
            title = "Go-around / missed approach",
            body = """
                1. A go-around is flown whenever the approach is unstable, visual references are lost, or ATC / crew decision requires it.
                2. Apply go-around thrust, set the correct pitch attitude, configure per memory items / FCOM, and follow the missed approach path.
                3. Do not rush configuration changes; fly the aeroplane first.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("decision height", "dh ", " mda", "minimum descent"),
            title = "DH vs MDA",
            body = """
                1. Decision Height (DH/DA) applies to precision approaches with vertical guidance.
                2. Minimum Descent Altitude (MDA) applies to non-precision approaches without continuous vertical guidance to the runway.
                3. At DH, decide to land or go around. At MDA, do not descend further without the required visual references.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("sterile", "sterile cockpit", "critical phase"),
            title = "Sterile cockpit",
            body = """
                1. During critical phases of flight, crew conversation is limited to duties essential for safe operation.
                2. Typical sterile periods include taxi, take-off, climb below sterile altitude, approach and landing as defined by the operator.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("memory item", "memory items", "ecam", "engine failure after v1"),
            title = "Memory items — general",
            body = """
                1. Memory items are immediate actions performed without reference to the checklist when delay would be unsafe.
                2. After memory items, confirm with the written checklist / ECAM procedures.
                3. Always prefer the aircraft Flight Manual and operator SOPs over study aids.
            """.trimIndent()
        )
    )

    fun lookup(question: String): Pair<String, AletheiaSource>? {
        val q = question.lowercase()
        val hit = entries.firstOrNull { entry ->
            entry.keywords.any { kw -> q.contains(kw) }
        } ?: return null
        val answer = buildString {
            append(AletheiaFeature.BRAND)
            append("\n\n")
            append(hit.title)
            append("\n\n")
            append(hit.body)
            append("\n\n")
            append("Source: operator manuals / AFM concepts. Confirm with your current aircraft documentation.")
        }
        return answer to AletheiaSource.MANUALS
    }
}
