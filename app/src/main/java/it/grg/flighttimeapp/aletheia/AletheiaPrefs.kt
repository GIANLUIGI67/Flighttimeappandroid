package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.content.SharedPreferences

class AletheiaPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tokenLock = Any()

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

    var lastDailyUsed: Int
        get() = prefs.getInt(KEY_LAST_DAILY_USED, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_DAILY_USED, value).apply()

    var activePlanId: String?
        get() = prefs.getString(KEY_ACTIVE_PLAN, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PLAN, value).apply()

    var pendingOnlinePrompt: String?
        get() = prefs.getString(KEY_PENDING_PROMPT, null)
        set(value) = prefs.edit().putString(KEY_PENDING_PROMPT, value).apply()

    var pendingOnlineMessageId: String?
        get() = prefs.getString(KEY_PENDING_MESSAGE_ID, null)
        set(value) = prefs.edit().putString(KEY_PENDING_MESSAGE_ID, value).apply()

    fun clearPendingOnline() {
        prefs.edit()
            .remove(KEY_PENDING_PROMPT)
            .remove(KEY_PENDING_MESSAGE_ID)
            .apply()
    }

    fun isPurchaseTokenGranted(token: String): Boolean {
        synchronized(tokenLock) {
            return grantedTokens().contains(token)
        }
    }

    /**
     * Atomically records a verified purchase token.
     * @return true if this call newly recorded the token (caller should grant once).
     */
    fun markPurchaseTokenGranted(token: String): Boolean {
        synchronized(tokenLock) {
            val set = grantedTokens().toMutableSet()
            if (!set.add(token)) return false
            // commit() so overlapping verify handlers see the token immediately.
            prefs.edit().putStringSet(KEY_GRANTED_TOKENS, HashSet(set)).commit()
            return true
        }
    }

    private fun grantedTokens(): Set<String> {
        // Copy — SharedPreferences may return a live mutable StringSet.
        return prefs.getStringSet(KEY_GRANTED_TOKENS, emptySet()).orEmpty().toSet()
    }

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
        private const val KEY_LAST_DAILY_USED = "last_daily_used"
        private const val KEY_ACTIVE_PLAN = "active_plan"
        private const val KEY_PENDING_PROMPT = "pending_online_prompt"
        private const val KEY_PENDING_MESSAGE_ID = "pending_online_message_id"
        private const val KEY_GRANTED_TOKENS = "granted_purchase_tokens"
    }
}
