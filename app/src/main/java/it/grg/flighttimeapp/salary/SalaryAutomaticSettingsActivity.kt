package it.grg.flighttimeapp.salary

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import it.grg.flighttimeapp.R

class SalaryAutomaticSettingsActivity : AppCompatActivity() {

    private lateinit var storage: SalaryStorage
    private lateinit var currentConfig: SalaryConfiguration
    private lateinit var saudiSwitch: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        storage = SalaryStorage(this)
        currentConfig = storage.configuration ?: storage.ensureConfiguration()

        val scroll = NestedScrollView(this).apply {
            setBackgroundColor(getColor(R.color.iosBackground))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = TextView(this).apply {
            text = getString(R.string.salary_settings_cancel)
            setTextColor(getColor(R.color.iosBlue))
            textSize = 15f
            setPadding(0, dp(8), dp(12), dp(8))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = getString(R.string.salary_auto_settings_title)
            setTextColor(getColor(R.color.iosText))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        header.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(View(this), LinearLayout.LayoutParams(close.measuredWidth.coerceAtLeast(dp(64)), 1))
        content.addView(header)

        saudiSwitch = SwitchCompat(this).apply {
            text = getString(R.string.salary_auto_settings_saudi_national)
            setTextColor(getColor(R.color.iosText))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        content.addView(card(saudiSwitch), topMargin = dp(14))

        val warning = TextView(this).apply {
            text = getString(R.string.salary_auto_settings_review_warning)
            setTextColor(getColor(R.color.iosHint))
            textSize = 13f
            setPadding(dp(4), dp(12), dp(4), dp(8))
        }
        content.addView(warning)

        SalaryAutomaticPresetRole.values().forEach { role ->
            content.addView(roleRow(role), topMargin = dp(10))
        }

        setContentView(scroll)
    }

    private fun roleRow(role: SalaryAutomaticPresetRole): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val title = TextView(this).apply {
            text = getString(role.titleRes)
            setTextColor(getColor(if (role.available) R.color.iosText else R.color.iosHint))
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = if (role.available) getString(role.subtitleRes) else getString(R.string.salary_auto_settings_coming_soon)
            setTextColor(getColor(R.color.iosHint))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(title)
        row.addView(subtitle)

        return card(row).apply {
            alpha = if (role.available) 1f else 0.65f
            isClickable = role.available
            isFocusable = role.available
            if (role.available) {
                setOnClickListener { applyPreset(role) }
            }
        }
    }

    private fun applyPreset(role: SalaryAutomaticPresetRole) {
        val preset = SalaryAutomaticPresetBuilder.configuration(
            role = role,
            current = currentConfig,
            includeGosi = saudiSwitch.isChecked,
            labels = this
        )
        storage.updateConfiguration(preset)
        Toast.makeText(this, getString(R.string.salary_auto_settings_applied), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun card(child: View): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.iosCardBg))
            strokeColor = getColor(R.color.iosCardStroke)
            strokeWidth = dp(1)
            addView(child)
        }
    }

    private fun LinearLayout.addView(view: View, topMargin: Int) {
        addView(
            view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { this.topMargin = topMargin }
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private enum class SalaryAutomaticPresetRole(
    val titleRes: Int,
    val subtitleRes: Int,
    val available: Boolean
) {
    CAPTAIN(R.string.salary_auto_settings_captain, R.string.salary_auto_preset_caption_captain, true),
    FIRST_OFFICER(R.string.salary_auto_settings_first_officer, R.string.salary_auto_preset_caption_first_officer, true),
    PURSER(R.string.salary_auto_settings_purser, R.string.salary_auto_preset_caption_soon, false),
    CABIN_CREW(R.string.salary_auto_settings_cabin_crew, R.string.salary_auto_preset_caption_cabin_crew, true),
    INSTRUCTOR(R.string.salary_auto_settings_instructor, R.string.salary_auto_preset_caption_soon, false)
}

private object SalaryAutomaticPresetBuilder {
    fun configuration(
        role: SalaryAutomaticPresetRole,
        current: SalaryConfiguration,
        includeGosi: Boolean,
        labels: android.content.Context
    ): SalaryConfiguration {
        return when (role) {
            SalaryAutomaticPresetRole.CAPTAIN -> captain(current, includeGosi, labels)
            SalaryAutomaticPresetRole.FIRST_OFFICER -> firstOfficer(current, includeGosi, labels)
            SalaryAutomaticPresetRole.CABIN_CREW -> cabinCrew(current, includeGosi, labels)
            SalaryAutomaticPresetRole.PURSER,
            SalaryAutomaticPresetRole.INSTRUCTOR -> current
        }
    }

    private fun captain(
        current: SalaryConfiguration,
        includeGosi: Boolean,
        labels: android.content.Context
    ): SalaryConfiguration {
        return SalaryConfiguration(
            currencyCode = "SAR",
            blockTimeBase = BlockTimeBase.SCHEDULED,
            monthlyAllowances = captainAllowances(includeGosi, labels),
            deductions = deductions(includeGosi, labels),
            blockPayBands = listOf(
                BlockPayBand(fromHours = 0, ratePerHour = 150.0),
                BlockPayBand(fromHours = 50, ratePerHour = 300.0),
                BlockPayBand(fromHours = 75, ratePerHour = 500.0)
            ),
            blockPayBandsMaxHours = 100,
            basicSalary = current.basicSalary,
            housingAllowance = 8333.0
        )
    }

    private fun firstOfficer(
        current: SalaryConfiguration,
        includeGosi: Boolean,
        labels: android.content.Context
    ): SalaryConfiguration {
        return SalaryConfiguration(
            currencyCode = "SAR",
            blockTimeBase = BlockTimeBase.SCHEDULED,
            monthlyAllowances = firstOfficerAllowances(includeGosi, labels),
            deductions = deductions(includeGosi, labels),
            blockPayBands = listOf(
                BlockPayBand(fromHours = 0, ratePerHour = 100.0),
                BlockPayBand(fromHours = 50, ratePerHour = 175.0),
                BlockPayBand(fromHours = 75, ratePerHour = 400.0)
            ),
            blockPayBandsMaxHours = 100,
            basicSalary = current.basicSalary,
            housingAllowance = current.housingAllowance
        )
    }

    private fun cabinCrew(
        current: SalaryConfiguration,
        includeGosi: Boolean,
        labels: android.content.Context
    ): SalaryConfiguration {
        return SalaryConfiguration(
            currencyCode = "SAR",
            blockTimeBase = current.blockTimeBase,
            monthlyAllowances = commonAllowances(labels),
            deductions = deductions(includeGosi, labels),
            blockPayBands = listOf(
                BlockPayBand(fromHours = 0, ratePerHour = 0.0),
                BlockPayBand(fromHours = 25, ratePerHour = 40.0),
                BlockPayBand(fromHours = 50, ratePerHour = 70.0),
                BlockPayBand(fromHours = 75, ratePerHour = 90.0)
            ),
            blockPayBandsMaxHours = 120,
            basicSalary = current.basicSalary,
            housingAllowance = current.housingAllowance
        )
    }

    private fun captainAllowances(includeSaudization: Boolean, labels: android.content.Context): List<MonthlyAllowance> {
        val allowances = commonAllowances(labels).toMutableList()
        allowances.add(
            MonthlyAllowance(
                name = labels.getString(R.string.salary_transport_allowance),
                type = MonthlyAllowanceType.FIXED_MONTHLY,
                amount = 1200.0
            )
        )
        allowances.add(
            MonthlyAllowance(
                name = labels.getString(R.string.salary_auto_settings_captain),
                type = MonthlyAllowanceType.FIXED_MONTHLY,
                amount = 5000.0
            )
        )
        allowances.add(
            MonthlyAllowance(
                name = labels.getString(R.string.allowance_type_per_overtime_day),
                type = MonthlyAllowanceType.PER_OVERTIME_DAY,
                amount = 1500.0
            )
        )
        if (includeSaudization) {
            allowances.add(saudizationAllowance(3000.0, labels))
        }
        return allowances
    }

    private fun firstOfficerAllowances(includeSaudization: Boolean, labels: android.content.Context): List<MonthlyAllowance> {
        val allowances = commonAllowances(labels).toMutableList()
        allowances.add(
            MonthlyAllowance(
                name = labels.getString(R.string.salary_transport_allowance),
                type = MonthlyAllowanceType.FIXED_MONTHLY,
                amount = 1000.0
            )
        )
        allowances.add(
            MonthlyAllowance(
                name = labels.getString(R.string.salary_auto_settings_first_officer),
                type = MonthlyAllowanceType.FIXED_MONTHLY,
                amount = 3500.0
            )
        )
        allowances.add(
            MonthlyAllowance(
                name = labels.getString(R.string.allowance_type_per_overtime_day),
                type = MonthlyAllowanceType.PER_OVERTIME_DAY,
                amount = 1200.0
            )
        )
        if (includeSaudization) {
            allowances.add(saudizationAllowance(1000.0, labels))
        }
        return allowances
    }

    private fun commonAllowances(labels: android.content.Context): List<MonthlyAllowance> {
        return listOf(
            MonthlyAllowance(
                name = labels.getString(R.string.salary_flying_allowance),
                type = MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS,
                amount = 0.0
            ),
            MonthlyAllowance(
                name = labels.getString(R.string.allowance_type_per_domestic_layover),
                type = MonthlyAllowanceType.PER_DOMESTIC_LAYOVER_DAY,
                amount = 150.0
            ),
            MonthlyAllowance(
                name = labels.getString(R.string.allowance_type_per_international_layover),
                type = MonthlyAllowanceType.PER_INTERNATIONAL_LAYOVER_DAY,
                amount = 300.0
            )
        )
    }

    private fun saudizationAllowance(amount: Double, labels: android.content.Context): MonthlyAllowance {
        return MonthlyAllowance(
            name = labels.getString(R.string.allowance_type_saudization),
            type = MonthlyAllowanceType.SAUDIZATION_ALLOWANCE,
            amount = amount
        )
    }

    private fun deductions(includeGosi: Boolean, labels: android.content.Context): List<SalaryDeduction> {
        if (!includeGosi) return emptyList()
        return listOf(
            SalaryDeduction(
                name = labels.getString(R.string.salary_deduction_gosi_name),
                type = DeductionType.GOSI_PERSONAL_SAUDI,
                amount = 0.0
            )
        )
    }
}
