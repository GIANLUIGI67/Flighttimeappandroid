package it.grg.flighttimeapp.aletheia

import it.grg.flighttimeapp.BuildConfig

/**
 * Rollback-safe gate for Aletheia Beta. When false, Crew Tools keeps the
 * coming-soon behaviour and Aletheia activities are not launched from home.
 */
object AletheiaFeature {
    val isEnabled: Boolean
        get() = BuildConfig.ALETHEIA_ENABLED

    val versionName: String
        get() = BuildConfig.ALETHEIA_VERSION

    const val BRAND = "Aletheia Beta"
}
