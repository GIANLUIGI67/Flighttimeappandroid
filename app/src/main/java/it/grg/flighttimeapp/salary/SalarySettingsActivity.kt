package it.grg.flighttimeapp.salary

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import it.grg.flighttimeapp.R
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts

class SalarySettingsActivity : AppCompatActivity() {

    private lateinit var storage: SalaryStorage
    private var config: SalaryConfiguration = SalaryConfiguration.default()

    private lateinit var currencySpinner: Spinner
    private lateinit var basicInput: EditText
    private lateinit var basicCurrency: TextView
    private lateinit var housingInput: EditText
    private lateinit var housingCurrency: TextView
    private lateinit var blockToggle: MaterialButtonToggleGroup
    private lateinit var allowancesContainer: LinearLayout
    private lateinit var allowancesEmpty: TextView
    private lateinit var deductionsContainer: LinearLayout
    private lateinit var deductionsEmpty: TextView

    private val currencies = listOf("SAR","EUR","USD","GBP","AED","QAR","KWD","BHD","OMR","CHF","JPY")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_salary_settings)

        storage = SalaryStorage(this)
        config = storage.configuration ?: storage.ensureConfiguration()

        currencySpinner = findViewById(R.id.settingsCurrencySpinner)
        basicInput = findViewById(R.id.settingsBasicInput)
        basicCurrency = findViewById(R.id.settingsBasicCurrency)
        housingInput = findViewById(R.id.settingsHousingInput)
        housingCurrency = findViewById(R.id.settingsHousingCurrency)
        blockToggle = findViewById(R.id.settingsBlockToggle)
        allowancesContainer = findViewById(R.id.settingsAllowancesContainer)
        allowancesEmpty = findViewById(R.id.settingsAllowancesEmpty)
        deductionsContainer = findViewById(R.id.settingsDeductionsContainer)
        deductionsEmpty = findViewById(R.id.settingsDeductionsEmpty)

        findViewById<View>(R.id.settingsSave).setOnClickListener {
            persistConfig()
            finish()
        }

        setupCurrency()
        setupBasicSalary()
        setupHousingAllowance()
        setupBlockBase()
        setupAllowances()
        setupDeductions()
    }

    override fun onResume() {
        super.onResume()
        storage.reload()
        val latest = storage.configuration
        if (latest != null && latest != config) {
            config = latest
            refreshUiFromConfig()
        }
    }

    override fun onPause() {
        super.onPause()
        persistConfig()
    }

    private fun persistConfig() {
        val latest = storage.configuration ?: config
        val merged = latest.copy(
            currencyCode = config.currencyCode,
            blockTimeBase = config.blockTimeBase,
            monthlyAllowances = config.monthlyAllowances,
            deductions = config.deductions,
            basicSalary = config.basicSalary,
            housingAllowance = config.housingAllowance
        )
        storage.updateConfiguration(merged)
    }

    private val allowanceEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        val data = result.data ?: return@registerForActivityResult
        val deleted = data.getBooleanExtra(AllowanceEditorActivity.EXTRA_DELETED, false)
        val raw = data.getStringExtra(AllowanceEditorActivity.EXTRA_RESULT_JSON)
        val baseConfig = storage.configuration ?: config

        if (deleted && raw != null) {
            val deletedAllowance = SalaryJson.decode(raw, MonthlyAllowance::class.java)
            val updated = baseConfig.monthlyAllowances.filterNot { it.id == deletedAllowance.id }
            config = baseConfig.copy(monthlyAllowances = updated)
            storage.updateConfiguration(config)
        } else if (raw != null) {
            val updatedAllowance = SalaryJson.decode(raw, MonthlyAllowance::class.java)
            val list = baseConfig.monthlyAllowances.toMutableList()
            val idx = list.indexOfFirst { it.id == updatedAllowance.id }
            if (idx >= 0) list[idx] = updatedAllowance else list.add(updatedAllowance)
            config = baseConfig.copy(monthlyAllowances = list)
            storage.updateConfiguration(config)
        }

        renderAllowances()
    }

    private val deductionEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        val data = result.data ?: return@registerForActivityResult
        val deleted = data.getBooleanExtra(DeductionEditorActivity.EXTRA_DELETED, false)
        val raw = data.getStringExtra(DeductionEditorActivity.EXTRA_RESULT_JSON)
        val baseConfig = storage.configuration ?: config

        if (deleted && raw != null) {
            val deletedDeduction = SalaryJson.decode(raw, SalaryDeduction::class.java)
            val updated = baseConfig.deductions.filterNot { it.id == deletedDeduction.id }
            config = baseConfig.copy(deductions = updated)
            storage.updateConfiguration(config)
        } else if (raw != null) {
            val updatedDeduction = SalaryJson.decode(raw, SalaryDeduction::class.java)
            val list = baseConfig.deductions.toMutableList()
            val idx = list.indexOfFirst { it.id == updatedDeduction.id }
            if (idx >= 0) list[idx] = updatedDeduction else list.add(updatedDeduction)
            config = baseConfig.copy(deductions = list)
            storage.updateConfiguration(config)
        }

        renderDeductions()
    }

    private fun setupCurrency() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, currencies)
        currencySpinner.adapter = adapter

        val idx = currencies.indexOf(config.currencyCode).let { if (it >= 0) it else 0 }
        currencySpinner.setSelection(idx)
        basicCurrency.text = currencies[idx]
        housingCurrency.text = currencies[idx]

        currencySpinner.setOnItemSelectedListener(SimpleItemSelectedListener { position ->
            val code = currencies[position]
            config = config.copy(currencyCode = code)
            basicCurrency.text = code
            housingCurrency.text = code
        })
    }

    private fun setupBasicSalary() {
        basicInput.setText(formatAmount(config.basicSalary))
        basicInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val value = parseAmount(s?.toString().orEmpty())
                config = config.copy(basicSalary = value)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    private fun setupHousingAllowance() {
        housingInput.setText(formatAmount(config.housingAllowance))
        housingInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val value = parseAmount(s?.toString().orEmpty())
                config = config.copy(housingAllowance = value)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        housingCurrency.text = config.currencyCode
    }

    private fun setupBlockBase() {
        if (config.blockTimeBase == BlockTimeBase.SCHEDULED) {
            blockToggle.check(R.id.settingsScheduledBtn)
        } else {
            blockToggle.check(R.id.settingsActualBtn)
        }

        blockToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            config = when (checkedId) {
                R.id.settingsScheduledBtn -> config.copy(blockTimeBase = BlockTimeBase.SCHEDULED)
                R.id.settingsActualBtn -> config.copy(blockTimeBase = BlockTimeBase.ACTUAL)
                else -> config
            }
        }
    }

    private fun setupAllowances() {
        findViewById<TextView>(R.id.settingsAddAllowance).setOnClickListener { openAllowanceEditor(null) }
        renderAllowances()
    }

    private fun setupDeductions() {
        findViewById<TextView>(R.id.settingsAddDeduction).setOnClickListener { openDeductionEditor(null) }
        renderDeductions()
    }

    private fun refreshUiFromConfig() {
        val idx = currencies.indexOf(config.currencyCode).let { if (it >= 0) it else 0 }
        currencySpinner.setSelection(idx)
        basicCurrency.text = currencies[idx]
        housingCurrency.text = currencies[idx]
        basicInput.setText(formatAmount(config.basicSalary))
        housingInput.setText(formatAmount(config.housingAllowance))

        if (config.blockTimeBase == BlockTimeBase.SCHEDULED) {
            blockToggle.check(R.id.settingsScheduledBtn)
        } else {
            blockToggle.check(R.id.settingsActualBtn)
        }

        renderAllowances()
        renderDeductions()
    }

    private fun openAllowanceEditor(existing: MonthlyAllowance?) {
        val intent = Intent(this, AllowanceEditorActivity::class.java)
        intent.putExtra(AllowanceEditorActivity.EXTRA_IS_NEW, existing == null)
        intent.putExtra("currency_code", config.currencyCode)
        if (existing != null) {
            intent.putExtra(AllowanceEditorActivity.EXTRA_ALLOWANCE_JSON, SalaryJson.encode(existing))
        }
        allowanceEditorLauncher.launch(intent)
    }

    private fun openDeductionEditor(existing: SalaryDeduction?) {
        val intent = Intent(this, DeductionEditorActivity::class.java)
        intent.putExtra(DeductionEditorActivity.EXTRA_IS_NEW, existing == null)
        intent.putExtra("currency_code", config.currencyCode)
        if (existing != null) {
            intent.putExtra(DeductionEditorActivity.EXTRA_DEDUCTION_JSON, SalaryJson.encode(existing))
        }
        deductionEditorLauncher.launch(intent)
    }

    private fun renderAllowances() {
        allowancesContainer.removeAllViews()

        if (config.monthlyAllowances.isEmpty()) {
            allowancesEmpty.visibility = View.VISIBLE
        } else {
            allowancesEmpty.visibility = View.GONE
            val inflater = LayoutInflater.from(this)
            config.monthlyAllowances.forEach { a ->
                val row = inflater.inflate(R.layout.item_allowance_row, allowancesContainer, false)
                row.findViewById<TextView>(R.id.allowanceName).text = if (a.name.isBlank()) getString(R.string.salary_settings_allowance_fallback) else a.name
                row.findViewById<TextView>(R.id.allowanceType).text = allowanceTypeLabel(a.type)

                val amountText = if (a.type == MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS) {
                    getString(R.string.salary_settings_bands)
                } else {
                    MoneyFormatter.format(a.amount, config.currencyCode)
                }
                row.findViewById<TextView>(R.id.allowanceAmount).text = amountText

                row.setOnClickListener { openAllowanceEditor(a) }
                allowancesContainer.addView(row)
            }
        }

        // add action row
        val addRow = LayoutInflater.from(this).inflate(R.layout.item_allowance_row, allowancesContainer, false)
        addRow.findViewById<TextView>(R.id.allowanceName).apply {
            text = "+ " + getString(R.string.salary_settings_add_allowance)
            setTextColor(getColor(R.color.iosBlue))
        }
        addRow.findViewById<TextView>(R.id.allowanceType).text = ""
        addRow.findViewById<TextView>(R.id.allowanceAmount).text = ""
        addRow.setOnClickListener { openAllowanceEditor(null) }
        allowancesContainer.addView(addRow)
    }

    private fun renderDeductions() {
        deductionsContainer.removeAllViews()

        if (config.deductions.isEmpty()) {
            deductionsEmpty.visibility = View.VISIBLE
        } else {
            deductionsEmpty.visibility = View.GONE
            val inflater = LayoutInflater.from(this)
            config.deductions.forEach { d ->
                val row = inflater.inflate(R.layout.item_allowance_row, deductionsContainer, false)
                row.findViewById<TextView>(R.id.allowanceName).text = when {
                    d.name.isNotBlank() -> d.name
                    d.type == DeductionType.GOSI_PERSONAL_SAUDI -> getString(R.string.salary_deduction_gosi_name)
                    else -> getString(R.string.salary_settings_deduction_fallback)
                }
                row.findViewById<TextView>(R.id.allowanceType).text = deductionTypeLabel(d.type)
                row.findViewById<TextView>(R.id.allowanceAmount).text = if (d.type == DeductionType.GOSI_PERSONAL_SAUDI) {
                    getString(R.string.salary_settings_amount_auto)
                } else {
                    MoneyFormatter.format(d.amount, config.currencyCode)
                }
                row.setOnClickListener { openDeductionEditor(d) }
                deductionsContainer.addView(row)
            }
        }

        val addRow = LayoutInflater.from(this).inflate(R.layout.item_allowance_row, deductionsContainer, false)
        addRow.findViewById<TextView>(R.id.allowanceName).apply {
            text = "+ " + getString(R.string.salary_settings_add_deduction)
            setTextColor(getColor(R.color.iosBlue))
        }
        addRow.findViewById<TextView>(R.id.allowanceType).text = ""
        addRow.findViewById<TextView>(R.id.allowanceAmount).text = ""
        addRow.setOnClickListener { openDeductionEditor(null) }
        deductionsContainer.addView(addRow)
    }

    private fun allowanceTypeLabel(type: MonthlyAllowanceType): String {
        return when (type) {
            MonthlyAllowanceType.FIXED_MONTHLY -> getString(R.string.allowance_type_fixed_monthly)
            MonthlyAllowanceType.PER_DUTY -> getString(R.string.allowance_type_per_duty)
            MonthlyAllowanceType.PER_DUTY_HOUR -> getString(R.string.allowance_type_per_duty_hour)
            MonthlyAllowanceType.PER_OVERTIME_DAY -> getString(R.string.allowance_type_per_overtime_day)
            MonthlyAllowanceType.PER_DOMESTIC_LAYOVER_DAY -> getString(R.string.allowance_type_per_domestic_layover)
            MonthlyAllowanceType.PER_INTERNATIONAL_LAYOVER_DAY -> getString(R.string.allowance_type_per_international_layover)
            MonthlyAllowanceType.PER_FLIGHT_SECTOR -> getString(R.string.allowance_type_per_flight)
            MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS -> getString(R.string.allowance_type_progressive_block_bands)
            MonthlyAllowanceType.OTHER -> getString(R.string.allowance_type_other)
        }
    }

    private fun deductionTypeLabel(type: DeductionType): String {
        return when (type) {
            DeductionType.MONTHLY -> getString(R.string.deduction_type_monthly)
            DeductionType.DAILY -> getString(R.string.deduction_type_daily)
            DeductionType.GOSI_PERSONAL_SAUDI -> getString(R.string.deduction_type_gosi)
        }
    }

    private fun parseAmount(raw: String): Double {
        val cleaned = raw.replace(",", ".").trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun formatAmount(value: Double): String {
        return if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()
    }
}
