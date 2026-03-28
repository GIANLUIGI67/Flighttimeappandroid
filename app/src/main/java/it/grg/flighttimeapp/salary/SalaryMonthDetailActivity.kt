@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R
import kotlin.math.max

class SalaryMonthDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_YEAR = "year"
        const val EXTRA_MONTH = "month"
    }

    private lateinit var storage: SalaryStorage
    private lateinit var config: SalaryConfiguration
    private var month: SalaryMonth = SalaryMonth.forCurrentMonth()

    private lateinit var titleText: TextView
    private lateinit var saveText: TextView
    private lateinit var basicValue: TextView
    private lateinit var housingRow: View
    private lateinit var housingValue: TextView
    private lateinit var allowancesCount: TextView
    private lateinit var deductionsCount: TextView

    private lateinit var resultBasic: TextView
    private lateinit var resultHousingRow: View
    private lateinit var resultHousing: TextView
    private lateinit var resultAllowances: TextView
    private lateinit var resultDeductionsRow: View
    private lateinit var resultDeductions: TextView
    private lateinit var resultTotal: TextView
    private lateinit var resultDutyTimeRow: View
    private lateinit var resultDutyTime: TextView
    private lateinit var resultSectorRow: View
    private lateinit var resultSector: TextView

    private lateinit var rowDuty: View
    private lateinit var rowOvertime: View
    private lateinit var rowLayoverDomestic: View
    private lateinit var rowLayoverInternational: View
    private lateinit var rowSectors: View

    private lateinit var rowScheduled: View
    private lateinit var rowActual: View
    private lateinit var rowDutyTime: View
    private lateinit var flightsRecycler: RecyclerView
    private lateinit var flightsAdapter: FlightLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_salary_month_detail)

        storage = SalaryStorage(this)
        if (!SalaryGate.hasSalaryFreeAccess(storage.getPrefs(), SalaryGate.isProUser(storage.getPrefs()))) {
            startActivity(Intent(this, SalaryLockedActivity::class.java))
            finish()
            return
        }
        config = storage.configuration ?: storage.ensureConfiguration()
        val y = intent.getIntExtra(EXTRA_YEAR, month.year)
        val m = intent.getIntExtra(EXTRA_MONTH, month.month)
        month = storage.selectOrCreateMonth(y, m)

        titleText = findViewById(R.id.monthDetailTitle)
        saveText = findViewById(R.id.monthDetailSave)
        basicValue = findViewById(R.id.monthDetailBasicValue)
        housingRow = findViewById(R.id.monthDetailHousingRow)
        housingValue = findViewById(R.id.monthDetailHousingValue)
        allowancesCount = findViewById(R.id.monthDetailAllowancesCount)
        deductionsCount = findViewById(R.id.monthDetailDeductionsCount)

        resultBasic = findViewById(R.id.monthDetailResultBasic)
        resultHousingRow = findViewById(R.id.monthDetailResultHousingRow)
        resultHousing = findViewById(R.id.monthDetailResultHousing)
        resultAllowances = findViewById(R.id.monthDetailResultAllowances)
        resultDeductionsRow = findViewById(R.id.monthDetailResultDeductionsRow)
        resultDeductions = findViewById(R.id.monthDetailResultDeductions)
        resultTotal = findViewById(R.id.monthDetailResultTotal)
        resultDutyTimeRow = findViewById(R.id.monthDetailResultDutyTimeRow)
        resultDutyTime = findViewById(R.id.monthDetailResultDutyTime)
        resultSectorRow = findViewById(R.id.monthDetailResultSectorRow)
        resultSector = findViewById(R.id.monthDetailResultSector)

        rowDuty = findViewById(R.id.rowDuty)
        rowOvertime = findViewById(R.id.rowOvertime)
        rowLayoverDomestic = findViewById(R.id.rowLayoverDomestic)
        rowLayoverInternational = findViewById(R.id.rowLayoverInternational)
        rowSectors = findViewById(R.id.rowSectors)

        rowScheduled = findViewById(R.id.rowScheduled)
        rowActual = findViewById(R.id.rowActual)
        rowDutyTime = findViewById(R.id.rowDutyTime)
        flightsRecycler = findViewById(R.id.monthDetailFlightsRecycler)

        titleText.text = month.title

        saveText.setOnClickListener {
            storage.upsert(month)
            finish()
        }

        setupCountRow(rowDuty, getString(R.string.salary_month_duty_count)) { delta ->
            month = month.copy(dutyCount = max(0, month.dutyCount + delta))
            updateCounts()
            persistMonth()
        }
        setupCountRow(rowOvertime, getString(R.string.salary_month_overtime_days)) { delta ->
            adjustOvertime(delta)
        }
        setupCountRow(rowLayoverDomestic, getString(R.string.salary_month_layover_domestic)) { delta ->
            adjustLayover(LayoverKind.DOMESTIC, delta)
        }
        setupCountRow(rowLayoverInternational, getString(R.string.salary_month_layover_international)) { delta ->
            adjustLayover(LayoverKind.INTERNATIONAL, delta)
        }
        setupCountRow(rowSectors, getString(R.string.salary_month_sectors)) { delta ->
            adjustSectors(delta)
        }

        rowOvertime.setOnClickListener {
            val i = Intent(this, OvertimeListActivity::class.java)
            i.putExtra(OvertimeListActivity.EXTRA_YEAR, month.year)
            i.putExtra(OvertimeListActivity.EXTRA_MONTH, month.month)
            startActivity(i)
        }
        rowLayoverDomestic.setOnClickListener {
            val i = Intent(this, LayoverListActivity::class.java)
            i.putExtra(LayoverListActivity.EXTRA_YEAR, month.year)
            i.putExtra(LayoverListActivity.EXTRA_MONTH, month.month)
            i.putExtra(LayoverListActivity.EXTRA_KIND, LayoverKind.DOMESTIC.name)
            startActivity(i)
        }
        rowLayoverInternational.setOnClickListener {
            val i = Intent(this, LayoverListActivity::class.java)
            i.putExtra(LayoverListActivity.EXTRA_YEAR, month.year)
            i.putExtra(LayoverListActivity.EXTRA_MONTH, month.month)
            i.putExtra(LayoverListActivity.EXTRA_KIND, LayoverKind.INTERNATIONAL.name)
            startActivity(i)
        }

        setupTimeRow(rowScheduled, getString(R.string.salary_month_scheduled_block), month.scheduledBlockMinutes) { minutes ->
            month = month.copy(scheduledBlockMinutes = minutes)
            updateTotals()
            persistMonth()
        }
        setupTimeRow(rowActual, getString(R.string.salary_month_actual_block), month.actualBlockMinutes) { minutes ->
            month = month.copy(actualBlockMinutes = minutes)
            updateTotals()
            persistMonth()
        }
        setupTimeRow(rowDutyTime, getString(R.string.salary_month_duty_time), month.dutyMinutes) { minutes ->
            month = month.copy(dutyMinutes = minutes)
            updateTotals()
            persistMonth()
        }

        findViewById<TextView>(R.id.monthDetailPayslipText).setOnClickListener { openPayslip() }
        findViewById<android.view.View>(R.id.monthDetailPayslipCard).setOnClickListener { openPayslip() }

        flightsAdapter = FlightLogAdapter(emptyList()) { log ->
            openFlightEditor(log)
        }
        flightsRecycler.layoutManager = LinearLayoutManager(this)
        flightsRecycler.adapter = flightsAdapter

        updateHeader()
        updateCounts()
        updateTotals()
        updateFlights()
    }

    private fun updateHeader() {
        basicValue.text = MoneyFormatter.format(config.basicSalary, config.currencyCode)
        if (config.housingAllowance > 0.0) {
            housingRow.visibility = View.VISIBLE
            housingValue.text = MoneyFormatter.format(config.housingAllowance, config.currencyCode)
        } else {
            housingRow.visibility = View.GONE
        }
        allowancesCount.text = config.monthlyAllowances.size.toString()
        deductionsCount.text = config.deductions.size.toString()
    }

    private fun updateCounts() {
        setCountLabel(rowDuty, getString(R.string.salary_month_duty_count), month.dutyCount)
        setCountLabel(rowOvertime, getString(R.string.salary_month_overtime_days), month.overtimeDays)
        setCountLabel(rowLayoverDomestic, getString(R.string.salary_month_layover_domestic), month.layoverDomesticDays)
        setCountLabel(rowLayoverInternational, getString(R.string.salary_month_layover_international), month.layoverInternationalDays)
        setCountLabel(rowSectors, getString(R.string.salary_month_sectors), month.sectorsCount)
        updateTotals()
    }

    private fun updateTotals() {
        val basic = config.basicSalary
        val housing = config.housingAllowance
        val allowancesBase = config.monthlyAllowances
            .filter { it.type != MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS }
            .sumOf { SalaryCalculatorEngine.computeAllowance(it, month, config) }
        val bandsPay = SalaryCalculatorEngine.progressiveBlockPay(month, config)
        val allowances = allowancesBase + bandsPay
        val deductions = config.deductions.sumOf { SalaryCalculatorEngine.computeDeduction(it, month, config) }
        val total = basic + housing + allowances - deductions

        resultBasic.text = MoneyFormatter.format(basic, config.currencyCode)
        if (housing > 0.0) {
            resultHousingRow.visibility = View.VISIBLE
            resultHousing.text = MoneyFormatter.format(housing, config.currencyCode)
        } else {
            resultHousingRow.visibility = View.GONE
        }
        resultAllowances.text = MoneyFormatter.format(allowances, config.currencyCode)
        if (deductions > 0.0) {
            resultDeductionsRow.visibility = View.VISIBLE
            resultDeductions.text = MoneyFormatter.format(-deductions, config.currencyCode)
        } else {
            resultDeductionsRow.visibility = View.GONE
        }
        resultTotal.text = MoneyFormatter.format(total, config.currencyCode)

        val dutyTimeTotal = config.monthlyAllowances
            .filter { it.type == MonthlyAllowanceType.PER_DUTY_HOUR }
            .sumOf { SalaryCalculatorEngine.computeAllowance(it, month, config) }
        if (dutyTimeTotal > 0.0) {
            resultDutyTimeRow.visibility = View.VISIBLE
            resultDutyTime.text = MoneyFormatter.format(dutyTimeTotal, config.currencyCode)
        } else {
            resultDutyTimeRow.visibility = View.GONE
        }

        val sectorTotal = config.monthlyAllowances
            .filter { it.type == MonthlyAllowanceType.PER_FLIGHT_SECTOR }
            .sumOf { SalaryCalculatorEngine.computeAllowance(it, month, config) }
        if (sectorTotal > 0.0) {
            resultSectorRow.visibility = View.VISIBLE
            resultSector.text = MoneyFormatter.format(sectorTotal, config.currencyCode)
        } else {
            resultSectorRow.visibility = View.GONE
        }
    }

    private fun persistMonth() {
        storage.upsert(month)
    }

    private fun openPayslip() {
        val basic = config.basicSalary
        val housing = config.housingAllowance
        val allowancesBase = config.monthlyAllowances
            .filter { it.type != MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS }
            .sumOf { SalaryCalculatorEngine.computeAllowance(it, month, config) }
        val bandsPay = SalaryCalculatorEngine.progressiveBlockPay(month, config)
        val allowances = allowancesBase + bandsPay
        val deductions = config.deductions.sumOf { SalaryCalculatorEngine.computeDeduction(it, month, config) }
        val total = basic + housing + allowances - deductions

        val intent = Intent(this, PayslipActivity::class.java)
        intent.putExtra("month_title", month.title)
        intent.putExtra(EXTRA_YEAR, month.year)
        intent.putExtra(EXTRA_MONTH, month.month)
        intent.putExtra("basic", basic)
        intent.putExtra("allowances", allowances)
        intent.putExtra("deductions", deductions)
        intent.putExtra("total", total)
        intent.putExtra("currency", config.currencyCode)
        startActivity(intent)
    }

    private fun adjustOvertime(delta: Int) {
        if (delta == 0) return
        val logs = month.overtimeLogs.toMutableList()
        if (delta > 0) {
            repeat(delta) {
                logs.add(0, OvertimeLog(date = java.time.LocalDate.now()))
            }
        } else {
            repeat(-delta) {
                if (logs.isNotEmpty()) logs.removeAt(0)
            }
        }
        month = month.copy(overtimeLogs = logs).syncCountsAlways()
        storage.upsert(month)
        updateCounts()
        updateTotals()
    }

    private fun adjustLayover(kind: LayoverKind, delta: Int) {
        if (delta == 0) return
        val logs = month.layoverLogs.toMutableList()
        if (delta > 0) {
            repeat(delta) {
                logs.add(
                    0,
                    LayoverLog(
                        date = java.time.LocalDate.now(),
                        location = "",
                        kind = kind
                    )
                )
            }
        } else {
            var remaining = -delta
            val iterator = logs.listIterator()
            while (iterator.hasNext() && remaining > 0) {
                val item = iterator.next()
                if (item.kind == kind) {
                    iterator.remove()
                    remaining--
                }
            }
        }
        month = month.copy(layoverLogs = logs).syncCountsAlways()
        storage.upsert(month)
        updateCounts()
        updateTotals()
    }

    private fun adjustSectors(delta: Int) {
        if (delta == 0) return
        val logs = month.flightLogs.toMutableList()
        if (delta > 0) {
            repeat(delta) {
                logs.add(
                    0,
                    FlightLog(
                        date = java.time.LocalDate.now(),
                        route = "AAA-BBB",
                        minutes = 0,
                        isScheduled = true
                    )
                )
            }
        } else {
            repeat(-delta) {
                if (logs.isNotEmpty()) logs.removeAt(0)
            }
        }
        month = month.copy(flightLogs = logs).syncCountsAlways()
        storage.upsert(month)
        updateCounts()
        updateFlights()
        updateTotals()
    }

    private val flightEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        val deleted = data.getBooleanExtra(FlightEditorActivity.EXTRA_DELETED, false)
        val id = data.getStringExtra(FlightEditorActivity.EXTRA_ID)

        if (deleted && id != null) {
            val updatedLogs = month.flightLogs.filterNot { it.id == id }
            month = month.copy(flightLogs = updatedLogs).syncCountsAlways()
            storage.upsert(month)
            updateFlights()
            updateTotals()
            return@registerForActivityResult
        }

        val dateRaw = data.getStringExtra(FlightEditorActivity.EXTRA_DATE) ?: return@registerForActivityResult
        val route = data.getStringExtra(FlightEditorActivity.EXTRA_ROUTE) ?: ""
        val minutes = data.getIntExtra(FlightEditorActivity.EXTRA_MINUTES, 0)
        val scheduled = data.getBooleanExtra(FlightEditorActivity.EXTRA_SCHEDULED, true)

        val updated = FlightLog(
            id = id ?: java.util.UUID.randomUUID().toString(),
            date = java.time.LocalDate.parse(dateRaw),
            route = route,
            minutes = minutes,
            isScheduled = scheduled
        )

        val list = month.flightLogs.toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)

        month = month.copy(flightLogs = list).syncCountsAlways()
        storage.upsert(month)
        updateFlights()
        updateTotals()
    }

    private fun openFlightEditor(existing: FlightLog?) {
        val intent = Intent(this, FlightEditorActivity::class.java)
        if (existing != null) {
            intent.putExtra(FlightEditorActivity.EXTRA_ID, existing.id)
            intent.putExtra(FlightEditorActivity.EXTRA_DATE, existing.date.toString())
            intent.putExtra(FlightEditorActivity.EXTRA_ROUTE, existing.route)
            intent.putExtra(FlightEditorActivity.EXTRA_MINUTES, existing.minutes)
            intent.putExtra(FlightEditorActivity.EXTRA_SCHEDULED, existing.isScheduled)
        }
        flightEditorLauncher.launch(intent)
    }

    override fun onResume() {
        super.onResume()
        month = storage.selectOrCreateMonth(month.year, month.month)
        updateHeader()
        updateCounts()
        updateTotals()
        updateFlights()
    }

    private fun updateFlights() {
        val sorted = month.flightLogs.sortedByDescending { it.date }
        flightsAdapter.update(sorted)
    }

    private fun setupCountRow(row: View, label: String, onDelta: (Int) -> Unit) {
        val labelView = row.findViewById<TextView>(R.id.countLabel)
        val minus = row.findViewById<TextView>(R.id.countMinus)
        val plus = row.findViewById<TextView>(R.id.countPlus)

        labelView.text = "$label: 0"
        minus.setOnClickListener { onDelta(-1) }
        plus.setOnClickListener { onDelta(1) }
    }

    private fun setCountLabel(row: View, label: String, value: Int) {
        row.findViewById<TextView>(R.id.countLabel).text = "$label: $value"
    }

    private fun setupTimeRow(row: View, label: String, minutes: Int, onChange: (Int) -> Unit) {
        row.findViewById<TextView>(R.id.timeLabel).text = label
        val hInput = row.findViewById<EditText>(R.id.timeH)
        val mInput = row.findViewById<EditText>(R.id.timeM)

        val h = minutes / 60
        val m = minutes % 60
        hInput.setText(h.toString())
        mInput.setText(String.format("%02d", m))

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val hh = hInput.text.toString().toIntOrNull() ?: 0
                val mm = mInput.text.toString().toIntOrNull() ?: 0
                val cappedM = max(0, minOf(59, mm))
                onChange(hh * 60 + cappedM)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }

        hInput.addTextChangedListener(watcher)
        mInput.addTextChangedListener(watcher)
    }
}
