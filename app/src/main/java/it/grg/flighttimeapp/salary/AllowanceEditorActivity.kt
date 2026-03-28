package it.grg.flighttimeapp.salary

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R

class AllowanceEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ALLOWANCE_JSON = "allowance_json"
        const val EXTRA_IS_NEW = "is_new"
        const val EXTRA_RESULT_JSON = "result_json"
        const val EXTRA_DELETED = "deleted"
    }

    private lateinit var nameInput: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var typeHelp: TextView
    private lateinit var amountRow: View
    private lateinit var amountInput: EditText
    private lateinit var amountCurrency: TextView
    private lateinit var bandsButton: TextView
    private lateinit var bandsSummary: TextView
    private lateinit var deleteButton: TextView

    private var isNew: Boolean = true
    private var currencyCode: String = "SAR"
    private var allowance: MonthlyAllowance = MonthlyAllowance(name = "", type = MonthlyAllowanceType.FIXED_MONTHLY, amount = 0.0)

    private val types = MonthlyAllowanceType.values().toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_allowance_editor)

        nameInput = findViewById(R.id.allowanceNameInput)
        typeSpinner = findViewById(R.id.allowanceTypeSpinner)
        typeHelp = findViewById(R.id.allowanceTypeHelp)
        amountRow = findViewById(R.id.allowanceAmountRow)
        amountInput = findViewById(R.id.allowanceAmountInput)
        amountCurrency = findViewById(R.id.allowanceAmountCurrency)
        bandsButton = findViewById(R.id.allowanceBandsButton)
        bandsSummary = findViewById(R.id.allowanceBandsSummary)
        deleteButton = findViewById(R.id.allowanceDelete)

        findViewById<TextView>(R.id.allowanceCancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.allowanceSave).setOnClickListener { saveAndReturn() }

        val raw = intent.getStringExtra(EXTRA_ALLOWANCE_JSON)
        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, true)
        currencyCode = intent.getStringExtra("currency_code") ?: "SAR"

        if (!raw.isNullOrBlank()) {
            allowance = SalaryJson.decode(raw, MonthlyAllowance::class.java)
        }

        setupTypeSpinner()
        bindAllowance()

        deleteButton.visibility = if (isNew) View.GONE else View.VISIBLE
        deleteButton.setOnClickListener { deleteAndReturn() }

        bandsButton.setOnClickListener {
            startActivity(Intent(this, BlockBandsEditorActivity::class.java))
        }
    }

    private fun setupTypeSpinner() {
        val labels = types.map { typeLabel(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        typeSpinner.adapter = adapter

        val idx = types.indexOfFirst { it == allowance.type }.let { if (it >= 0) it else 0 }
        typeSpinner.setSelection(idx)
        typeSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { position ->
            allowance = allowance.copy(type = types[position])
            updateTypeUI()
        })
    }

    private fun bindAllowance() {
        nameInput.setText(allowance.name)
        amountInput.setText(formatAmount(allowance.amount))
        amountCurrency.text = currencyCode
        updateTypeUI()
    }

    private fun updateTypeUI() {
        val t = allowance.type
        typeHelp.text = typeHelpText(t)

        if (t == MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS) {
            amountRow.visibility = View.GONE
            bandsButton.visibility = View.VISIBLE
            bandsSummary.visibility = View.VISIBLE
            bandsSummary.text = getString(R.string.salary_settings_no_bands)
        } else {
            amountRow.visibility = View.VISIBLE
            bandsButton.visibility = View.GONE
            bandsSummary.visibility = View.GONE
        }
    }

    private fun saveAndReturn() {
        val name = nameInput.text.toString().trim()
        val amount = parseAmount(amountInput.text.toString())
        val storedAmount = if (allowance.type == MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS) 0.0 else amount
        val updated = allowance.copy(name = name, amount = storedAmount)

        val data = Intent()
        data.putExtra(EXTRA_RESULT_JSON, SalaryJson.encode(updated))
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun deleteAndReturn() {
        val data = Intent()
        data.putExtra(EXTRA_RESULT_JSON, SalaryJson.encode(allowance))
        data.putExtra(EXTRA_DELETED, true)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun parseAmount(raw: String): Double {
        val cleaned = raw.replace(",", ".").trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun formatAmount(value: Double): String {
        return if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()
    }

    private fun typeLabel(type: MonthlyAllowanceType): String {
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

    private fun typeHelpText(type: MonthlyAllowanceType): String {
        return when (type) {
            MonthlyAllowanceType.FIXED_MONTHLY -> getString(R.string.allowance_help_fixed_monthly)
            MonthlyAllowanceType.PER_DUTY -> getString(R.string.allowance_help_per_duty)
            MonthlyAllowanceType.PER_DUTY_HOUR -> getString(R.string.allowance_help_per_duty_hour)
            MonthlyAllowanceType.PER_OVERTIME_DAY -> getString(R.string.allowance_help_per_overtime_day)
            MonthlyAllowanceType.PER_DOMESTIC_LAYOVER_DAY -> getString(R.string.allowance_help_per_domestic_layover)
            MonthlyAllowanceType.PER_INTERNATIONAL_LAYOVER_DAY -> getString(R.string.allowance_help_per_international_layover)
            MonthlyAllowanceType.PER_FLIGHT_SECTOR -> getString(R.string.allowance_help_per_flight)
            MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS -> getString(R.string.allowance_help_progressive_block_bands)
            MonthlyAllowanceType.OTHER -> getString(R.string.allowance_help_other)
        }
    }
}
