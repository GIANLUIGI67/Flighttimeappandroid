package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.content.SharedPreferences

class AletheiaPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var consentAnswered: Boolean
        get() = prefs.getBoolean(KEY_CONSENT_ANSWERED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONSENT_ANSWERED, value).apply()

    /** True = Yes to RL training; false = No (full AI still allowed). */
    var trainingConsentGranted: Boolean
        get() = prefs.getBoolean(KEY_TRAINING_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_TRAINING_CONSENT, value).apply()

    var lowPowerWarningDismissed: Boolean
        get() = prefs.getBoolean(KEY_LOW_POWER_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_POWER_DISMISSED, value).apply()

    var preferredModeOnline: Boolean
        get() = prefs.getBoolean(KEY_MODE_ONLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_MODE_ONLINE, value).apply()

    var cachedDailyRemaining: Int
        get() = prefs.getInt(KEY_DAILY_REMAINING, FREE_DAILY_LIMIT)
        set(value) = prefs.edit().putInt(KEY_DAILY_REMAINING, value).apply()

    var cachedCreditsRemaining: Int
        get() = prefs.getInt(KEY_CREDITS_REMAINING, 0)
        set(value) = prefs.edit().putInt(KEY_CREDITS_REMAINING, value).apply()

    var cachedHaikuRemaining: Int
        get() = prefs.getInt(KEY_HAIKU_REMAINING, 0)
        set(value) = prefs.edit().putInt(KEY_HAIKU_REMAINING, value).apply()

    var activePlanId: String?
        get() = prefs.getString(KEY_ACTIVE_PLAN, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PLAN, value).apply()

    fun clearConsent() {
        prefs.edit()
            .remove(KEY_CONSENT_ANSWERED)
            .remove(KEY_TRAINING_CONSENT)
            .apply()
    }

    companion object {
        const val FREE_DAILY_LIMIT = 20
        private const val PREFS = "aletheia_beta_prefs"
        private const val KEY_CONSENT_ANSWERED = "consent_answered"
        private const val KEY_TRAINING_CONSENT = "training_consent"
        private const val KEY_LOW_POWER_DISMISSED = "low_power_dismissed"
        private const val KEY_MODE_ONLINE = "mode_online"
        private const val KEY_DAILY_REMAINING = "daily_remaining"
        private const val KEY_CREDITS_REMAINING = "credits_remaining"
        private const val KEY_HAIKU_REMAINING = "haiku_remaining"
        private const val KEY_ACTIVE_PLAN = "active_plan"
    }
}
