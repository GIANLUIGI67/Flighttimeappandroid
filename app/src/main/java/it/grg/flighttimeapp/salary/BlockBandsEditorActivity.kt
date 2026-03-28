package it.grg.flighttimeapp.salary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R

class BlockBandsEditorActivity : AppCompatActivity() {

    private lateinit var storage: SalaryStorage
    private lateinit var container: LinearLayout
    private lateinit var addBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var cancelBtn: TextView

    private var currencyCode: String = "SAR"
    private var currentMaxHours: Int = SalaryCalculatorEngine.DEFAULT_BAND_HOURS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block_bands)

        storage = SalaryStorage(this)
        val config = storage.configuration ?: storage.ensureConfiguration()
        currencyCode = config.currencyCode
        currentMaxHours = config.blockPayBandsMaxHours
            .coerceIn(SalaryCalculatorEngine.DEFAULT_BAND_HOURS, SalaryCalculatorEngine.MAX_BAND_HOURS_LIMIT)

        container = findViewById(R.id.bandsContainer)
        addBtn = findViewById(R.id.bandsAdd)
        saveBtn = findViewById(R.id.bandsSave)
        cancelBtn = findViewById(R.id.bandsCancel)

        val existing = config.blockPayBands.sortedBy { it.fromHours }
        if (existing.isEmpty()) {
            addRow("0", "50", "150")
            addRow("50", "75", "300")
            addRow("75", "100", "500")
            addRow("100", "120", "600")
        } else {
            existing.forEachIndexed { index, b ->
                val nextStart = existing.getOrNull(index + 1)?.fromHours ?: currentMaxHours
                addRow(b.fromHours.toString(), nextStart.toString(), formatAmount(b.ratePerHour))
            }
        }

        addBtn.setOnClickListener { addRow("0", "", "0") }
        cancelBtn.setOnClickListener { finish() }
        saveBtn.setOnClickListener { saveBands() }
    }

    private fun addRow(start: String, end: String, rate: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_band_row, container, false)
        val startInput = row.findViewById<EditText>(R.id.bandStart)
        val endInput = row.findViewById<EditText>(R.id.bandEnd)
        val rateInput = row.findViewById<EditText>(R.id.bandRate)
        val currency = row.findViewById<TextView>(R.id.bandCurrency)
        val delete = row.findViewById<TextView>(R.id.bandDelete)

        startInput.setText(start)
        endInput.setText(end)
        rateInput.setText(rate)
        currency.text = "$currencyCode/hr"

        delete.setOnClickListener {
            container.removeView(row)
        }

        container.addView(row)
    }

    private fun saveBands() {
        val bands = mutableListOf<BlockPayBand>()
        var previousEnd: Int? = null
        var lastExplicitEnd: Int? = null
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            val startInput = row.findViewById<EditText>(R.id.bandStart)
            val endInput = row.findViewById<EditText>(R.id.bandEnd)
            val rateInput = row.findViewById<EditText>(R.id.bandRate)

            val rawStart = startInput.text.toString().toIntOrNull()
            val rawEnd = endInput.text.toString().toIntOrNull()
            val rate = rateInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0

            if (rawStart == null && rawEnd == null && rate == 0.0) {
                continue
            }

            val resolvedStart = when {
                rawStart != null -> rawStart
                previousEnd != null -> previousEnd
                rawEnd != null -> maxOf(0, rawEnd - 1)
                else -> 0
            }

            bands.add(BlockPayBand(fromHours = resolvedStart, ratePerHour = rate))
            previousEnd = rawEnd ?: resolvedStart
            if (rawEnd != null) {
                lastExplicitEnd = rawEnd
            }
        }

        val config = storage.configuration ?: storage.ensureConfiguration()
        val normalized = bands
            .groupBy { it.fromHours }
            .map { (_, list) -> list.last() }
            .sortedBy { it.fromHours }
        val maxHours = (lastExplicitEnd ?: currentMaxHours)
            .coerceIn(SalaryCalculatorEngine.DEFAULT_BAND_HOURS, SalaryCalculatorEngine.MAX_BAND_HOURS_LIMIT)
        storage.updateConfiguration(
            config.copy(
                blockPayBands = normalized,
                blockPayBandsMaxHours = maxHours
            )
        )
        finish()
    }

    private fun formatAmount(value: Double): String {
        return if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()
    }
}
