package it.grg.flighttimeapp.salary

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.NumberPicker
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import it.grg.flighttimeapp.R

class SalaryHomeActivity : AppCompatActivity() {

    private lateinit var storage: SalaryStorage
    private var selectedYear: Int = 0
    private var selectedMonth: Int = 0

    private lateinit var monthText: TextView
    private lateinit var summaryFlightHours: TextView
    private lateinit var summaryOvertimeValue: TextView
    private lateinit var summaryLayoverDomesticValue: TextView
    private lateinit var summaryLayoverInternationalValue: TextView
    private lateinit var flightsRecycler: RecyclerView
    private lateinit var flightsAdapter: FlightLogAdapter

    private val flightEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        val deleted = data.getBooleanExtra(FlightEditorActivity.EXTRA_DELETED, false)
        val id = data.getStringExtra(FlightEditorActivity.EXTRA_ID)
        val month = storage.selectOrCreateMonth(selectedYear, selectedMonth)

        if (deleted && id != null) {
            val updatedLogs = month.flightLogs.filterNot { it.id == id }
            val updated = month.copy(flightLogs = updatedLogs).syncCountsAlways()
            storage.upsert(updated)
            bindMonthSummary()
            return@registerForActivityResult
        }

        val dateRaw = data.getStringExtra(FlightEditorActivity.EXTRA_DATE) ?: return@registerForActivityResult
        val route = data.getStringExtra(FlightEditorActivity.EXTRA_ROUTE) ?: ""
        val minutes = data.getIntExtra(FlightEditorActivity.EXTRA_MINUTES, 0)
        val scheduled = data.getBooleanExtra(FlightEditorActivity.EXTRA_SCHEDULED, true)

        val updatedLog = FlightLog(
            id = id ?: java.util.UUID.randomUUID().toString(),
            date = java.time.LocalDate.parse(dateRaw),
            route = route,
            minutes = minutes,
            isScheduled = scheduled
        )

        val list = month.flightLogs.toMutableList()
        val idx = list.indexOfFirst { it.id == updatedLog.id }
        if (idx >= 0) list[idx] = updatedLog else list.add(updatedLog)

        val updatedMonth = month.copy(flightLogs = list).syncCountsAlways()
        storage.upsert(updatedMonth)
        bindMonthSummary()
    }

    private val overtimeEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        val deleted = data.getBooleanExtra(OvertimeEditorActivity.EXTRA_DELETED, false)
        val id = data.getStringExtra(OvertimeEditorActivity.EXTRA_ID)
        val month = storage.selectOrCreateMonth(selectedYear, selectedMonth)

        if (deleted && id != null) {
            val updatedLogs = month.overtimeLogs.filterNot { it.id == id }
            val updated = month.copy(overtimeLogs = updatedLogs, overtimeDays = updatedLogs.size).syncCountsAlways()
            storage.upsert(updated)
            bindMonthSummary()
            return@registerForActivityResult
        }

        val dateRaw = data.getStringExtra(OvertimeEditorActivity.EXTRA_DATE) ?: return@registerForActivityResult
        val updatedLog = OvertimeLog(
            id = id ?: java.util.UUID.randomUUID().toString(),
            date = java.time.LocalDate.parse(dateRaw)
        )

        val list = month.overtimeLogs.toMutableList()
        val idx = list.indexOfFirst { it.id == updatedLog.id }
        if (idx >= 0) list[idx] = updatedLog else list.add(updatedLog)

        val updatedMonth = month.copy(overtimeLogs = list, overtimeDays = list.size).syncCountsAlways()
        storage.upsert(updatedMonth)
        bindMonthSummary()
    }

    private val layoverEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        val deleted = data.getBooleanExtra(LayoverEditorActivity.EXTRA_DELETED, false)
        val id = data.getStringExtra(LayoverEditorActivity.EXTRA_ID)
        val kind = LayoverKind.valueOf(data.getStringExtra(LayoverEditorActivity.EXTRA_KIND) ?: LayoverKind.DOMESTIC.name)
        val month = storage.selectOrCreateMonth(selectedYear, selectedMonth)

        if (deleted && id != null) {
            val updatedLogs = month.layoverLogs.filterNot { it.id == id }
            val domestic = updatedLogs.count { it.kind == LayoverKind.DOMESTIC }
            val international = updatedLogs.count { it.kind == LayoverKind.INTERNATIONAL }
            val updated = month.copy(
                layoverLogs = updatedLogs,
                layoverDomesticDays = domestic,
                layoverInternationalDays = international
            ).syncCountsAlways()
            storage.upsert(updated)
            bindMonthSummary()
            return@registerForActivityResult
        }

        val dateRaw = data.getStringExtra(LayoverEditorActivity.EXTRA_DATE) ?: return@registerForActivityResult
        val location = data.getStringExtra(LayoverEditorActivity.EXTRA_LOCATION) ?: ""

        val updatedLog = LayoverLog(
            id = id ?: java.util.UUID.randomUUID().toString(),
            date = java.time.LocalDate.parse(dateRaw),
            location = location,
            kind = kind
        )

        val list = month.layoverLogs.toMutableList()
        val idx = list.indexOfFirst { it.id == updatedLog.id }
        if (idx >= 0) list[idx] = updatedLog else list.add(updatedLog)

        val domestic = list.count { it.kind == LayoverKind.DOMESTIC }
        val international = list.count { it.kind == LayoverKind.INTERNATIONAL }
        val updatedMonth = month.copy(
            layoverLogs = list,
            layoverDomesticDays = domestic,
            layoverInternationalDays = international
        ).syncCountsAlways()
        storage.upsert(updatedMonth)
        bindMonthSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_salary_home)

        storage = SalaryStorage(this)
        SalaryGate.ensureFirstUseIfNeeded(storage.getPrefs())
        if (!SalaryGate.hasSalaryFreeAccess(storage.getPrefs(), SalaryGate.isProUser(storage.getPrefs()))) {
            startActivity(Intent(this, SalaryLockedActivity::class.java))
            finish()
            return
        }

        val current = storage.currentMonth()
        selectedYear = current.year
        selectedMonth = current.month

        monthText = findViewById(R.id.salaryMonthText)
        summaryFlightHours = findViewById(R.id.salarySummaryFlightHours)
        summaryOvertimeValue = findViewById(R.id.salarySummaryOvertimeValue)
        summaryLayoverDomesticValue = findViewById(R.id.salarySummaryLayoverDomesticValue)
        summaryLayoverInternationalValue = findViewById(R.id.salarySummaryLayoverInternationalValue)
        flightsRecycler = findViewById(R.id.salaryFlightsRecycler)
        flightsAdapter = FlightLogAdapter(emptyList()) { log -> openFlightEditor(log) }
        flightsRecycler.layoutManager = LinearLayoutManager(this)
        flightsRecycler.adapter = flightsAdapter

        findViewById<ImageButton>(R.id.salaryBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.salarySettings).setOnClickListener {
            startActivity(Intent(this, SalarySettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.salaryChangeMonth).setOnClickListener { showMonthPicker() }
        findViewById<android.view.View>(R.id.salaryChangeMonthRow).setOnClickListener { showMonthPicker() }
        findViewById<android.view.View>(R.id.salaryAddCard).setOnClickListener { showAddMenu() }
        findViewById<TextView>(R.id.salaryAdd).setOnClickListener { showAddMenu() }
        findViewById<android.view.View>(R.id.salaryPayslipCard).setOnClickListener { openPayslip() }
        findViewById<TextView>(R.id.salaryPayslipText).setOnClickListener { openPayslip() }

        bindMonthSummary()
    }

    override fun onResume() {
        super.onResume()
        storage.reload()
        bindMonthSummary()
    }

    private fun bindMonthSummary() {
        val config = storage.configuration ?: storage.ensureConfiguration()
        val month = storage.selectOrCreateMonth(selectedYear, selectedMonth)

        monthText.text = month.title
        val minutes = SalaryCalculatorEngine.baseBlockMinutes(month, config)
        summaryFlightHours.text = SalaryCalculatorEngine.hhmm(minutes)

        summaryOvertimeValue.text = month.overtimeDays.toString()
        summaryLayoverDomesticValue.text = month.layoverDomesticDays.toString()
        summaryLayoverInternationalValue.text = month.layoverInternationalDays.toString()

        val sortedFlights = month.flightLogs.sortedByDescending { it.date }
        flightsAdapter.update(sortedFlights)
    }

    private fun openPayslip() {
        val prefs = storage.getPrefs()
        if (!SalaryGate.hasSalaryFreeAccess(prefs, SalaryGate.isProUser(prefs))) {
            startActivity(Intent(this, SalaryLockedActivity::class.java))
            return
        }
        if (!SalaryGate.isProUser(prefs)) {
            SalaryGate.incrementCalcCount(prefs)
        }
        val intent = Intent(this, SalaryMonthDetailActivity::class.java)
        intent.putExtra(SalaryMonthDetailActivity.EXTRA_YEAR, selectedYear)
        intent.putExtra(SalaryMonthDetailActivity.EXTRA_MONTH, selectedMonth)
        startActivity(intent)
    }

    private fun showMonthPicker() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_month_picker, null, false)
        val yearPicker = view.findViewById<NumberPicker>(R.id.pickerYear)
        val monthPicker = view.findViewById<NumberPicker>(R.id.pickerMonth)

        yearPicker.minValue = 2000
        yearPicker.maxValue = 2100
        yearPicker.value = selectedYear

        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        monthPicker.value = selectedMonth

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.salary_change_month))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel_button)) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
            .setPositiveButton(getString(R.string.salary_select)) { _: DialogInterface, _: Int ->
                selectedYear = yearPicker.value
                selectedMonth = monthPicker.value
                bindMonthSummary()
            }
            .show()
    }

    private fun showAddMenu() {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.bottom_sheet_salary_add)

        dialog.findViewById<TextView>(R.id.addFlight)?.setOnClickListener {
            dialog.dismiss()
            flightEditorLauncher.launch(Intent(this, FlightEditorActivity::class.java))
        }
        dialog.findViewById<TextView>(R.id.addOvertime)?.setOnClickListener {
            dialog.dismiss()
            overtimeEditorLauncher.launch(Intent(this, OvertimeEditorActivity::class.java))
        }
        dialog.findViewById<TextView>(R.id.addLayoverDomestic)?.setOnClickListener {
            dialog.dismiss()
            val i = Intent(this, LayoverEditorActivity::class.java)
            i.putExtra(LayoverEditorActivity.EXTRA_KIND, LayoverKind.DOMESTIC.name)
            layoverEditorLauncher.launch(i)
        }
        dialog.findViewById<TextView>(R.id.addLayoverInternational)?.setOnClickListener {
            dialog.dismiss()
            val i = Intent(this, LayoverEditorActivity::class.java)
            i.putExtra(LayoverEditorActivity.EXTRA_KIND, LayoverKind.INTERNATIONAL.name)
            layoverEditorLauncher.launch(i)
        }
        dialog.show()
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
}
