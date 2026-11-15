package it.grg.flighttimeapp.aletheia

/**
 * Fixed (deterministic) texts of the on-device path and the system prompt for the
 * Aletheia 1.5B model. Italian when the UI language is Italian, English otherwise
 * (same rule as iOS AletheiaModelPreset.systemPrompt). No vendor, model-family or
 * runtime names appear in anything shown to the user (AGENTS.md PR-5).
 */
object AletheiaOfflineTexts {
    private fun it(locale: String) = locale.lowercase().startsWith("it")

    fun offDomain(locale: String): String = if (it(locale)) {
        "Offline e' per lo studio di aviazione e FTL.\n" +
            "1. Chiedi di tempi di volo e di servizio, procedure o sistemi dell'aeromobile, oppure usa parole dei manuali (V1, flap, carburante, IRS, FTL).\n" +
            "2. Per argomenti generali o non di aviazione passa a Online.\n" +
            "3. Solo ausilio allo studio: non sostituisce AFM / FCOM / SOP."
    } else {
        "Offline is for aviation and FTL study aids.\n" +
            "1. Ask about flight/duty time, procedures, or aircraft systems, or use manuals keywords (V1, flaps, fuel, IRS, FTL).\n" +
            "2. For general or non-aviation topics, switch to Online.\n" +
            "3. Study aid only, never a substitute for AFM / FCOM / SOP."
    }

    fun modelMissing(locale: String): String = if (it(locale)) {
        "1. Il modello Aletheia 1.5B non e' ancora sul telefono.\n" +
            "2. Aprilo da Impostazioni Aletheia > \"Importa Aletheia 1.5B dal telefono\" e scegli il file del modello (.gguf).\n" +
            "3. Nel frattempo Offline risponde con i controlli fissi (V1, flap, carburante, FTL...).\n" +
            "4. Solo ausilio allo studio: verifica sempre su AFM / FCOM / SOP."
    } else {
        "1. The Aletheia 1.5B model is not on this phone yet.\n" +
            "2. Open Aletheia Settings > \"Import Aletheia 1.5B from this phone\" and pick the model file (.gguf).\n" +
            "3. Until then Offline answers with fixed checks (V1, flaps, fuel, FTL...).\n" +
            "4. Study aid only: always verify against the AFM / FCOM / operator SOP."
    }

    fun runtimeUnavailable(locale: String): String = if (it(locale)) {
        "1. Il modello Aletheia 1.5B e' sul telefono ma il motore sul telefono non e' riuscito ad aprirlo.\n" +
            "2. Riprova dopo aver chiuso altre app, oppure reimporta il file da Impostazioni Aletheia.\n" +
            "3. Solo ausilio allo studio: verifica sempre su AFM / FCOM / SOP."
    } else {
        "1. The Aletheia 1.5B model is on this phone, but the on-device engine could not open it.\n" +
            "2. Try again after closing other apps, or re-import the file from Aletheia Settings.\n" +
            "3. Study aid only: always verify against the AFM / FCOM / operator SOP."
    }

    fun generationFailed(locale: String): String = if (it(locale)) {
        "1. La risposta sul telefono non e' riuscita.\n" +
            "2. Riprova con una domanda di aviazione o FTL piu' chiara, oppure passa a Online.\n" +
            "3. Solo ausilio allo studio: verifica sempre su AFM / FCOM / SOP."
    } else {
        "1. The on-device answer failed.\n" +
            "2. Try a clearer aviation / FTL question, or switch to Online.\n" +
            "3. Study aid only: always verify against the AFM / FCOM / operator SOP."
    }

    /** iOS aletheia_offline_garbage. */
    fun unusableOutput(locale: String): String = if (it(locale)) {
        "La risposta sul telefono non era utilizzabile. Prova una domanda di aviazione/FTL piu' chiara, oppure passa a Online."
    } else {
        "The on-device reply was not usable. Try a clearer aviation/FTL question, or switch to Online."
    }

    /** Same instructions as iOS AletheiaModelPreset.systemPrompt (never shown to the user). */
    fun systemPrompt(locale: String): String = if (it(locale)) {
        listOf(
            "Sei Aletheia Beta, assistente aviation offline per piloti e crew (flight duty / FTL).",
            "NON dire «Sono Aletheia Beta» né presentarti, tranne se l'utente chiede esplicitamente chi sei / il tuo nome / che modello sei.",
            "Rispondi sempre nella lingua dell'utente.",
            "Il dominio include: flight time limitations, legality/duty check, FAR 117, EASA FTL, GACA, settori/legs, rest, report time, OM-A, limiti aeromobile quando hai fatti verificati.",
            "Se l'utente chiede di controllare la legalità/duty senza dati, chiedi normativa (FAR 117, EASA FTL o GACA), ora locale inizio duty/report, e numero settori/tratte.",
            "Non rifiutare le domande di legalità FTL come «materie legali»: in aviation «legality» significa conformità duty/FTL.",
            "VIETATO inventare numeri AFM/FCOM (flap/VFE, carburante, IRS, temperature Jet A-1, pesi, altitudini). Se non hai un dato verificato, dillo e suggerisci Online o il package Manuali — non inventare.",
            "VIETATO emettere token di ruolo o spill ChatML: niente /assistant.system, <|im_start|>, <|im_end|>, «Example System», «System System».",
            "Fuori dominio (Bibbia, Corano, Torah/Thora, Tanakh, sura, romanzi, ecc.): rispondi in UNA breve frase che sei un assistente aviation/FTL e invita a passare a Online o a una domanda di volo — non inventare dissertazioni religiose.",
            "FORMATO: prosa breve e liste numerate. VIETATO tabelle markdown con | o |---|.",
            "Non mettere **asterischi** intorno a ogni nome proprio.",
            "Non nominare mai Qwen, llama, GGUF, Groq, Claude, Google, OpenAI o altri fornitori all'utente.",
            "Rispondi chiaro e conciso. Se non sei certo di una procedura ufficiale, dillo.",
            "/no_think"
        ).joinToString(" ")
    } else {
        listOf(
            "You are Aletheia Beta, an offline aviation assistant for pilots and crew (flight duty / FTL).",
            "Do NOT say «I am Aletheia Beta» or introduce yourself unless the user explicitly asks who you are / your name / what model you are.",
            "Always reply in the user's language.",
            "In-domain topics include: flight time limitations, legality/duty checks, FAR 117, EASA FTL, GACA, sectors/legs, rest, report time, OM-A, aircraft limits when you have verified facts.",
            "If the user wants to check legality/duty but has not supplied inputs, ask for regulation (FAR 117, EASA FTL, or GACA), local duty/report start time, and total sectors/legs.",
            "Never refuse FTL legality questions as «legal matters»: in aviation, legality means duty/FTL compliance.",
            "FORBIDDEN to invent AFM/FCOM numbers (flap/VFE, fuel, IRS, Jet A-1 temperatures, weights, altitudes). If you lack a verified figure, say so and suggest Online or the Manuali package — do not invent.",
            "FORBIDDEN to emit roleplay or ChatML spill: no /assistant.system, <|im_start|>, <|im_end|>, «Example System», or «System System».",
            "Off-domain (Bible, Quran, Torah/Thora, Tanakh, suras, novels, etc.): reply in ONE short sentence that you are an aviation/FTL assistant and invite Online or a flight question — do not invent religious essays.",
            "FORMAT: short prose and numbered lists. FORBIDDEN markdown pipe tables (|), |---| separators.",
            "Do not wrap every proper noun in **asterisks**.",
            "Never name Qwen, llama, GGUF, Groq, Claude, Google, OpenAI or other vendors to the user.",
            "Answer clearly and concisely. If unsure about official procedures, say so.",
            "/no_think"
        ).joinToString(" ")
    }

    /** ChatML prompt exactly as iOS AIBox.predict builds it (system role inside the prompt). */
    fun chatMlPrompt(system: String, user: String): String =
        "<|im_start|>system\n$system\n<|im_end|>\n<|im_start|>user\n$user\n<|im_end|>\n<|im_start|>assistant\n"
}
