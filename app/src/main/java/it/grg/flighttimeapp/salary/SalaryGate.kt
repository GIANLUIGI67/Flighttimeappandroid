@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import android.content.SharedPreferences
import java.time.LocalDate
import java.time.Period

object SalaryGate {

    private const val KEY_FIRST_USE = "salary_first_use_iso"
    private const val KEY_CALC_COUNT = "salary_calc_count"
    private const val KEY_IS_PRO_USER = "isProUser"

    private const val SALARY_FREE_MONTHS = 12
    private const val SALARY_MAX_FREE_CALCULATIONS = 150
    private const val VIRAL_FREE_UNTIL_ISO = "2027-08-22T00:00:00Z"

    fun ensureFirstUseIfNeeded(prefs: SharedPreferences) {
        if (prefs.getString(KEY_FIRST_USE, null).isNullOrBlank()) {
            prefs.edit().putString(KEY_FIRST_USE, LocalDate.now().toString()).apply()
        }
    }

    fun monthsSinceFirstUse(prefs: SharedPreferences, now: LocalDate = LocalDate.now()): Int {
        val raw = prefs.getString(KEY_FIRST_USE, null) ?: return 0
        val start = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return 0
        val period = Period.between(start.withDayOfMonth(1), now.withDayOfMonth(1))
        return (period.years * 12 + period.months).coerceAtLeast(0)
    }

    fun getCalcCount(prefs: SharedPreferences): Int {
        return prefs.getInt(KEY_CALC_COUNT, 0)
    }

    fun incrementCalcCount(prefs: SharedPreferences) {
        val next = getCalcCount(prefs) + 1
        prefs.edit().putInt(KEY_CALC_COUNT, next).apply()
    }

    fun isProUser(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(KEY_IS_PRO_USER, false)
    }

    fun hasSalaryFreeAccess(prefs: SharedPreferences, isPro: Boolean): Boolean {
        if (isViralFreeWindowActive()) return true
        if (isPro) return true
        val months = monthsSinceFirstUse(prefs)
        val calcs = getCalcCount(prefs)
        return (months < SALARY_FREE_MONTHS) && (calcs < SALARY_MAX_FREE_CALCULATIONS)
    }

    private fun isViralFreeWindowActive(): Boolean {
        return runCatching {
            val until = java.time.Instant.parse(VIRAL_FREE_UNTIL_ISO)
            java.time.Instant.now().isBefore(until)
        }.getOrDefault(false)
    }
}
