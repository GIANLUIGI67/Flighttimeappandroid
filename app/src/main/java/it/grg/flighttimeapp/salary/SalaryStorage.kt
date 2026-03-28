@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.time.LocalDate
import java.time.YearMonth

class SalaryStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("salary_storage", Context.MODE_PRIVATE)

    private val configKey = "salary_config_v1"
    private val monthsKey = "salary_months_v1"

    var configuration: SalaryConfiguration? = null
        private set

    var months: List<SalaryMonth> = emptyList()
        private set

    init {
        loadAll()
    }

    fun ensureConfiguration(): SalaryConfiguration {
        val existing = configuration
        if (existing != null) return existing

        val created = SalaryConfiguration.default()
        configuration = created
        saveConfig(created)
        return created
    }

    fun updateConfiguration(newConfig: SalaryConfiguration) {
        configuration = newConfig
        saveConfig(newConfig)
    }

    fun currentMonth(now: LocalDate = LocalDate.now()): SalaryMonth {
        val y = now.year
        val m = now.monthValue
        val found = months.firstOrNull { it.year == y && it.month == m }
        if (found != null) {
            val synced = found.syncCountsAlways()
            if (synced != found) {
                upsert(synced)
            }
            return synced
        }

        val created = SalaryMonth(year = y, month = m).syncCountsAlways()
        upsert(created)
        return created
    }

    fun upsert(month: SalaryMonth) {
        val updated = month.syncCountsAlways()
        val replaced = months.toMutableList()

        val idx = replaced.indexOfFirst { it.id == updated.id }
        when {
            idx >= 0 -> replaced[idx] = updated
            else -> {
                val idx2 = replaced.indexOfFirst { it.year == updated.year && it.month == updated.month }
                if (idx2 >= 0) replaced[idx2] = updated else replaced.add(updated)
            }
        }

        months = replaced.sortedWith(compareBy({ it.year }, { it.month }))
        saveMonths()
    }

    fun selectOrCreateMonth(year: Int, month: Int): SalaryMonth {
        val found = months.firstOrNull { it.year == year && it.month == month }
        if (found != null) {
            val synced = found.syncCountsAlways()
            if (synced != found) {
                upsert(synced)
            }
            return synced
        }

        val created = SalaryMonth(year = year, month = month).syncCountsAlways()
        upsert(created)
        return created
    }

    private fun loadAll() {
        configuration = loadConfig()
        configuration = ensureDefaultsIfNeeded(configuration)
        val loaded = loadMonths()
        val normalized = loaded.map { it.syncCountsAlways() }
        months = normalized.sortedWith(compareBy({ it.year }, { it.month }))
        if (normalized != loaded) {
            saveMonths()
        }
        configuration?.let { saveConfig(it) }
    }

    private fun ensureDefaultsIfNeeded(current: SalaryConfiguration?): SalaryConfiguration? {
        if (current == null) return null
        val hasAllowances = current.monthlyAllowances.isNotEmpty()
        val hasDeductions = current.deductions.isNotEmpty()
        val hasBands = current.blockPayBands.isNotEmpty()
        if (hasAllowances || hasDeductions || hasBands) return current
        return SalaryConfiguration.default().copy(
            currencyCode = current.currencyCode,
            blockTimeBase = current.blockTimeBase,
            basicSalary = current.basicSalary,
            housingAllowance = current.housingAllowance,
            deductions = current.deductions,
            blockPayBandsMaxHours = normalizeMaxHours(current.blockPayBandsMaxHours)
        )
    }

    fun reload() {
        loadAll()
    }

    private fun saveConfig(config: SalaryConfiguration) {
        val json = SalaryJson.encode(config)
        prefs.edit().putString(configKey, json).apply()
    }

    private fun loadConfig(): SalaryConfiguration? {
        val raw = prefs.getString(configKey, null) ?: return null
        val decoded = runCatching { SalaryJson.decode(raw, SalaryConfiguration::class.java) }
            .getOrNull() ?: return null
        val safeDeductions = (decoded.deductions as? List<SalaryDeduction>) ?: emptyList()
        return decoded.copy(
            deductions = safeDeductions,
            blockPayBandsMaxHours = normalizeMaxHours(decoded.blockPayBandsMaxHours)
        )
    }

    private fun normalizeMaxHours(raw: Int): Int {
        val minHours = SalaryCalculatorEngine.DEFAULT_BAND_HOURS
        val maxHours = SalaryCalculatorEngine.MAX_BAND_HOURS_LIMIT
        return raw.coerceIn(minHours, maxHours)
    }

    private fun saveMonths() {
        val json = SalaryJson.encode(months)
        prefs.edit().putString(monthsKey, json).apply()
    }

    private fun loadMonths(): List<SalaryMonth> {
        val raw = prefs.getString(monthsKey, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<SalaryMonth>>() {}.type
            SalaryJson.decode<List<SalaryMonth>>(raw, type)
        }.getOrNull() ?: emptyList()
    }

    fun getPrefs(): SharedPreferences = prefs

}
