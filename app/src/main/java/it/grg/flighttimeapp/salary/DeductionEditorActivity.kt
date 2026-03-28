package it.grg.flighttimeapp.salary

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R

class DeductionEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEDUCTION_JSON = "deduction_json"
        const val EXTRA_IS_NEW = "is_new"
        const val EXTRA_RESULT_JSON = "result_json"
        const val EXTRA_DELETED = "deleted"
    }

    private lateinit var nameInput: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var typeHelp: TextView
    private lateinit var amountInput: EditText
    private lateinit var amountCurrency: TextView
    private lateinit var deleteButton: TextView

    private var isNew: Boolean = true
    private var currencyCode: String = "SAR"
    private var deduction: SalaryDeduction = SalaryDeduction(
        name = "",
        type = DeductionType.MONTHLY,
        amount = 0.0
    )

    private val types = DeductionType.values().toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deduction_editor)

        nameInput = findViewById(R.id.deductionNameInput)
        typeSpinner = findViewById(R.id.deductionTypeSpinner)
        typeHelp = findViewById(R.id.deductionTypeHelp)
        amountInput = findViewById(R.id.deductionAmountInput)
        amountCurrency = findViewById(R.id.deductionAmountCurrency)
        deleteButton = findViewById(R.id.deductionDelete)

        findViewById<TextView>(R.id.deductionCancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.deductionSave).setOnClickListener { saveAndReturn() }

        val raw = intent.getStringExtra(EXTRA_DEDUCTION_JSON)
        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, true)
        currencyCode = intent.getStringExtra("currency_code") ?: "SAR"

        if (!raw.isNullOrBlank()) {
            deduction = SalaryJson.decode(raw, SalaryDeduction::class.java)
        }

        setupTypeSpinner()
        bindDeduction()

        deleteButton.visibility = if (isNew) View.GONE else View.VISIBLE
        deleteButton.setOnClickListener { deleteAndReturn() }
    }

    private fun setupTypeSpinner() {
        val labels = types.map { typeLabel(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        typeSpinner.adapter = adapter

        val idx = types.indexOfFirst { it == deduction.type }.let { if (it >= 0) it else 0 }
        typeSpinner.setSelection(idx)
        typeSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { position ->
            deduction = deduction.copy(type = types[position])
            updateTypeHelp()
            updateAmountState()
        })
    }

    private fun bindDeduction() {
        nameInput.setText(deduction.name)
        amountInput.setText(formatAmount(deduction.amount))
        amountCurrency.text = currencyCode
        updateTypeHelp()
        updateAmountState()
    }

    private fun updateTypeHelp() {
        typeHelp.text = typeHelpText(deduction.type)
    }

    private fun updateAmountState() {
        val isAuto = deduction.type == DeductionType.GOSI_PERSONAL_SAUDI
        amountInput.isEnabled = !isAuto
        amountInput.alpha = if (isAuto) 0.6f else 1f
        if (isAuto) {
            amountInput.setText("0")
        }
    }

    private fun saveAndReturn() {
        val name = nameInput.text.toString().trim()
        val amount = if (deduction.type == DeductionType.GOSI_PERSONAL_SAUDI) {
            0.0
        } else {
            parseAmount(amountInput.text.toString())
        }
        val updated = deduction.copy(name = name, amount = amount)

        val data = Intent()
        data.putExtra(EXTRA_RESULT_JSON, SalaryJson.encode(updated))
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun deleteAndReturn() {
        val data = Intent()
        data.putExtra(EXTRA_RESULT_JSON, SalaryJson.encode(deduction))
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

    private fun typeLabel(type: DeductionType): String {
        return when (type) {
            DeductionType.MONTHLY -> getString(R.string.deduction_type_monthly)
            DeductionType.DAILY -> getString(R.string.deduction_type_daily)
            DeductionType.GOSI_PERSONAL_SAUDI -> getString(R.string.deduction_type_gosi)
        }
    }

    private fun typeHelpText(type: DeductionType): String {
        return when (type) {
            DeductionType.MONTHLY -> getString(R.string.deduction_help_monthly)
            DeductionType.DAILY -> getString(R.string.deduction_help_daily)
            DeductionType.GOSI_PERSONAL_SAUDI -> getString(R.string.deduction_help_gosi)
        }
    }
}
