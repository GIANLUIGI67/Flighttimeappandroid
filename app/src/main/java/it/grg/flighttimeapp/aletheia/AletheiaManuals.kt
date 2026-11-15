package it.grg.flighttimeapp.aletheia

/**
 * On-device manuals-first index for Offline and Online client routing.
 * Returns study-aid framing that points crews to AFM / FCOM / SOP —
 * never invents aircraft-specific fuel, flap, IRS, or FTL numbers.
 */
object AletheiaManuals {
    data class Entry(
        val keywords: List<String>,
        val title: String,
        val body: String,
        /** Higher wins when multiple keyword sets match. */
        val priority: Int = 0
    )

    private val entries = listOf(
        Entry(
            keywords = listOf("v1", "decision speed"),
            title = "V1 — Decision speed",
            priority = 10,
            body = """
                Study aid only — not operational guidance for any aircraft or runway.
                1. In certification and performance literature, V1 is the take-off decision speed used when building take-off data.
                2. Exact reject / continue criteria, speeds, and procedures are aircraft-specific and condition-specific.
                3. Before any real-world decision, use the current AFM / FCOM performance data and your operator SOPs for that aircraft, runway, weight, and conditions.
                4. Do not treat this summary as a substitute for those manuals.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("v2", "take-off safety", "takeoff safety"),
            title = "V2 — Take-off safety speed",
            priority = 10,
            body = """
                Study aid only.
                1. V2 is the take-off safety speed used for the initial climb with one engine inoperative.
                2. Climb gradient and obstacle clearance come from the certified AFM performance profile for that aircraft and conditions.
                3. Use the AFM / FCOM numbers for your weight, thrust, flap, and runway — do not invent speeds here.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("vref", "reference landing", "landing speed"),
            title = "VREF — Reference landing speed",
            priority = 10,
            body = """
                Study aid only.
                1. VREF is the reference landing approach speed in the landing configuration.
                2. Operators add corrections for wind, ice, and malfunctions as published in the AFM / FCOM / SOP.
                3. Stabilised approach criteria still apply before continuing to land.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("go-around", "go around", "missed approach"),
            title = "Go-around / missed approach",
            priority = 8,
            body = """
                Study aid only.
                1. A go-around is flown whenever the approach is unstable, visual references are lost, or ATC / crew decision requires it.
                2. Apply go-around thrust, set the correct pitch attitude, configure per memory items / FCOM, and follow the missed approach path.
                3. Confirm memory items and missed-approach procedure in the current QRH / FCOM / SOP for your aircraft.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("decision height", "dh ", " mda", "minimum descent"),
            title = "DH vs MDA",
            priority = 8,
            body = """
                Study aid only.
                1. Decision Height (DH/DA) applies to precision approaches with vertical guidance.
                2. Minimum Descent Altitude (MDA) applies to non-precision approaches without continuous vertical guidance to the runway.
                3. At DH, decide to land or go around. At MDA, do not descend further without the required visual references — use the approach plate and operator SOP.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("sterile", "sterile cockpit", "critical phase"),
            title = "Sterile cockpit",
            priority = 5,
            body = """
                Study aid only.
                1. During critical phases of flight, crew conversation is limited to duties essential for safe operation.
                2. Typical sterile periods include taxi, take-off, climb below sterile altitude, approach and landing as defined by the operator OM-A / SOP.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf(
                "memory item", "memory items", "ecam",
                "engine failure after v1", "engine-out", "engine out",
                "engine failure", "one engine", "oei"
            ),
            title = "Memory items / engine-out — study framing",
            priority = 20,
            body = """
                Study aid only — not a substitute for QRH / ECAM / AFM memory items.
                1. Memory items are immediate actions performed without the checklist when delay would be unsafe.
                2. After memory items, confirm with the written checklist / ECAM / QRH for that aircraft and failure.
                3. Engine-out after V1 / OEI climb profiles, pitch targets, and acceleration altitudes are type-specific — open the current AFM / FCOM / QRH for your A320 / aircraft and operator SOP.
                4. This app does not invent thrust, pitch, speed, or configuration numbers. Ask for the specific memory-item title if you want a study outline of the sequence (without numbers).
            """.trimIndent()
        ),
        Entry(
            keywords = listOf(
                "fuel limitation", "fuel limitations", "limitazioni del carburante",
                "carburante", "fuel capacity", "fuel tank", "jet a1", "jet a-1", "jet a‑1"
            ),
            title = "Fuel limitations — consult AFM / FCOM",
            priority = 25,
            body = """
                Study aid only — no invented fuel figures.
                1. Usable fuel capacity, tank distribution, unusable fuel, temperature / freeze limits, and imbalance limits are aircraft- and configuration-specific.
                2. For A320 family (including Neo), open the current AFM / FCOM LIMITATIONS and FUEL chapters for that MSN / engine option — do not use memorised or chat-invented kg/L numbers.
                3. Jet A-1 storage and freeze properties belong in the fuel specification and operator fuel policy; confirm with company manuals before planning.
                4. If you need a study outline, say which limit class (capacity, imbalance, temperature, feeding) and which aircraft — answers here stay qualitative until verified in AFM/FCOM.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf(
                "flap", "flaps", "slat", "slats", "vfe", "conf 1", "conf 2", "conf 3"
            ),
            title = "Flap / slat limitations — consult AFM speeds",
            priority = 25,
            body = """
                Study aid only — no invented flap speeds.
                1. Maximum flap / slat operating speeds (VFE) and altitude with high lift extended are published in the AFM / FCOM LIMITATIONS for that aircraft.
                2. For A320 family (Ceo/Neo), use the current AFM speed card / FCOM LIMITATIONS — do not invent kt/s rates or CONF speeds from chat memory.
                3. Configuration changes follow FCOM SOPs and memory items; verify against the QRH when abnormal.
                4. Study framing only: high-lift devices have maximum speeds that decrease as more flap is selected — exact numbers are in the AFM.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf("irs ", " irs", "inertial", "adr ", "adirs"),
            title = "IRS / ADIRS limitations — consult AFM / FCOM",
            priority = 25,
            body = """
                Study aid only — no invented IRS accuracy or alignment numbers.
                1. IRS / ADIRS alignment times, operating envelopes, and navigation-mode limitations are in the AFM / FCOM for your aircraft.
                2. For A320neo (or any type), open LIMITATIONS and NAVIGATION / ADIRS chapters — do not invent position-error metres or update rates here.
                3. Abnormal IRS procedures belong in the QRH / ECAM — follow those, not chat summaries.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf(
                "limitazioni", "limitations", "a320 neo", "a320neo", "airbus limit"
            ),
            title = "Aircraft limitations — manuals first",
            priority = 5,
            body = """
                Study aid only.
                1. Operating limitations (altitude, speeds, weights, temperatures, systems) live in the AFM / FCOM LIMITATIONS for that exact aircraft and engine option.
                2. This client will not invent SOP, fuel, IRS, flap, or performance numbers.
                3. Name the system (fuel, flaps, IRS, speeds, …) and open the matching AFM / FCOM section, or switch to a manuals-matched keyword question.
            """.trimIndent()
        ),
        Entry(
            keywords = listOf(
                "ftl", "far 117", "far117", "easa ftl", "gaca", "duty time", "flight time",
                "ore di volo", "tempo di servizio", "fdp", "wocl", "riposo", "legality",
                "legalità", "legalita", "settori", "sectors", "inizio duty", "report time"
            ),
            title = "FTL / duty — study framing",
            priority = 30,
            body = """
                Study aid for flight- and duty-time schemes (FAR 117, EASA FTL, GACA, OM-A).
                1. To check legality Offline I need: applicable scheme (FAR 117 / EASA FTL / GACA / company OM-A), local report/duty start time, and number of sectors (flown + planned).
                2. FDP maxima, WOCL effects, extensions, and rest depend on those inputs and the current regulation — I will not invent a FDP number without them.
                3. Always verify with your operator OM-A, rostering tool, and the applicable authority rules before signing on.
                4. Example Offline ask: "GACA, 05:00 inizio duty, 4 settori" — then compare the study outline to OM-A.
            """.trimIndent()
        )
    )

    fun lookup(question: String): Pair<String, AletheiaSource>? {
        val q = question.lowercase()
        val hit = entries
            .filter { entry -> entry.keywords.any { kw -> q.contains(kw) } }
            .maxByOrNull { it.priority }
            ?: return null
        val answer = buildString {
            append(hit.title)
            append("\n\n")
            append(hit.body)
            append("\n\n")
            append("Source: AFM / FCOM / operator SOP concepts. Confirm with your current aircraft and company documentation.")
        }
        return answer to AletheiaSource.MANUALS
    }

    /** True when the prompt is clearly aviation / FTL / procedures study. */
    fun isAviationTopic(question: String): Boolean {
        if (lookup(question) != null) return true
        val q = question.lowercase()
        val hints = listOf(
            "aircraft", "airplane", "aeroplane", "aereo", "flight", "volo", "pilot", "crew",
            "runway", "taxi", "takeoff", "take-off", "landing", "approach", "cruise",
            "airbus", "boeing", "a320", "a330", "b737", "qrh", "fcom", "afm", "sop",
            "mel", "ecam", "efis", "autopilot", "thrust", "flap", "gear", "metar", "atis",
            "notam", "holdover", "anti-ice", "cabin", "pressur", "ftl", "duty", "fdp"
        )
        return hints.any { q.contains(it) }
    }

    /**
     * Off-domain for Offline GGUF (religion, generic coding, pure tourism) —
     * Online may still answer; Offline should refuse gracefully.
     */
    fun isClearlyOffDomainForOffline(question: String): Boolean {
        if (isAviationTopic(question)) return false
        val q = question.lowercase()
        val off = listOf(
            "bible", "bibbia", "quran", "corano", "torah", "thora", "torah",
            "profet", "prophet", "jesus", "muhammad", "allah", "church", "mosque",
            "python", "javascript", "function in", "scrivi una funzione", "code snippet",
            "turista", "tourist", "taksim", "istanbul visit", "jeddah", "cosa posso vedere",
            "what can i visit", "what can i do in", "re dell", "king of saudi"
        )
        return off.any { q.contains(it) }
    }
}
