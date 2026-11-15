package it.grg.flighttimeapp.aletheia

import it.grg.flighttimeapp.BuildConfig
import java.util.Locale

/**
 * Provenance fields every Aletheia callable carries (server: functions/aletheia_turns.js
 * buildProvenance). Values are facts about this build, never guesses.
 */
data class AletheiaClientInfo(
    val platform: String = PLATFORM,
    val appVersion: String,
    val appBuild: String,
    val aletheiaVersion: String,
    val surface: String = SURFACE
) {
    fun provenance(): Map<String, Any> = linkedMapOf(
        "platform" to platform,
        "appVersion" to appVersion,
        "appBuild" to appBuild,
        "aletheiaVersion" to aletheiaVersion,
        "surface" to surface
    )

    companion object {
        const val PLATFORM = "android"
        /** FlightTime -> Crew Tools -> Aletheia chat. */
        const val SURFACE = "flighttime_crew_tools"

        fun fromBuildConfig(): AletheiaClientInfo = AletheiaClientInfo(
            appVersion = BuildConfig.VERSION_NAME,
            appBuild = BuildConfig.VERSION_CODE.toString(),
            aletheiaVersion = BuildConfig.ALETHEIA_VERSION
        )

        /** Two-letter UI language like iOS `Locale.current.language.languageCode` ("it", "en"). */
        fun currentLocaleCode(locale: Locale = Locale.getDefault()): String =
            locale.language.ifBlank { "en" }.lowercase().take(16)
    }
}
