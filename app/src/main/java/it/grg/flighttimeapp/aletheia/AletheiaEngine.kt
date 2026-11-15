package it.grg.flighttimeapp.aletheia

/**
 * Which engine ACTUALLY produced an assistant reply (AGENTS.md PR-1 / PR-5).
 *
 * The value is set from the real answer path, never from configuration:
 * - [ONLINE]: the server callable `aletheiaChat` generated the answer.
 * - [LOCAL_MODEL]: the on-device Aletheia 1.5B model generated the answer.
 * - [DETERMINISTIC]: a fixed answer from deterministic checks (manuals package,
 *   off-domain refusal, "model not imported" notice, error text) - no model ran.
 *
 * [apiEngine] / [modelRoute] are the provenance values the server stores in
 * `ai_turns` (functions/aletheia_turns.js ENGINES / MODEL_ROUTES). They are
 * internal identities for training/audit and are never shown to the user.
 */
enum class AletheiaEngine(val apiEngine: String, val modelRoute: String?) {
    ONLINE("online", null),
    LOCAL_MODEL("offline", "local"),
    DETERMINISTIC("deterministic", "guard");

    companion object {
        fun fromApi(raw: String?): AletheiaEngine? = when (raw?.trim()?.lowercase()) {
            "online" -> ONLINE
            "offline" -> LOCAL_MODEL
            "deterministic" -> DETERMINISTIC
            else -> null
        }
    }
}

/** User-visible engine label keys; mapped to string resources in the UI layer. */
enum class AletheiaEngineLabel {
    /** "Aletheia online" */
    ONLINE,
    /** "Aletheia 1.5B (on this phone)" - file identity verified by SHA-256. */
    LOCAL_VERIFIED,
    /** "Aletheia 1.5B (on this phone, not verified)" - name matches, hash does not. */
    LOCAL_UNVERIFIED,
    /** "Fixed checks, no model" - deterministic answer, no model ran. */
    DETERMINISTIC;

    companion object {
        fun of(engine: AletheiaEngine, modelVerified: Boolean): AletheiaEngineLabel = when (engine) {
            AletheiaEngine.ONLINE -> ONLINE
            AletheiaEngine.LOCAL_MODEL -> if (modelVerified) LOCAL_VERIFIED else LOCAL_UNVERIFIED
            AletheiaEngine.DETERMINISTIC -> DETERMINISTIC
        }
    }
}

/** Structured reply from the on-device path, with the engine that really answered. */
data class AletheiaEngineReply(
    val text: String,
    /** Channel badge / Firebase `mode`: offline, manuals or online. */
    val source: AletheiaSource,
    val engine: AletheiaEngine,
    /** True only when [engine] is LOCAL_MODEL and the imported file matched the known SHA-256. */
    val modelVerified: Boolean = false
)
