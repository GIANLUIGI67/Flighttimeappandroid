@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import it.grg.flighttimeapp.R
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class PayslipActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payslip)

        val storage = SalaryStorage(this)
        if (!SalaryGate.hasSalaryFreeAccess(storage.getPrefs(), SalaryGate.isProUser(storage.getPrefs()))) {
            startActivity(Intent(this, SalaryLockedActivity::class.java))
            finish()
            return
        }

        val config = storage.configuration ?: storage.ensureConfiguration()
        val year = intent.getIntExtra(SalaryMonthDetailActivity.EXTRA_YEAR, 0)
        val monthNum = intent.getIntExtra(SalaryMonthDetailActivity.EXTRA_MONTH, 0)
        val month = if (year > 0 && monthNum > 0) {
            storage.selectOrCreateMonth(year, monthNum)
        } else {
            storage.currentMonth()
        }

        val allowances = SalaryCalculatorEngine.allowancesTotal(month, config)
        val deductions = config.deductions.sumOf { SalaryCalculatorEngine.computeDeduction(it, month, config) }
        val basic = config.basicSalary
        val total = basic + allowances - deductions

        findViewById<TextView>(R.id.payslipMonth).text = month.title
        findViewById<TextView>(R.id.payslipBasic).text = MoneyFormatter.format(basic, config.currencyCode)
        val housingRow = findViewById<View>(R.id.payslipHousingRow)
        val housingText = findViewById<TextView>(R.id.payslipHousing)
        housingRow.visibility = View.GONE
        housingText.text = ""
        findViewById<TextView>(R.id.payslipAllowances).text = MoneyFormatter.format(allowances, config.currencyCode)
        val deductionsRow = findViewById<View>(R.id.payslipDeductionsRow)
        val deductionsText = findViewById<TextView>(R.id.payslipDeductions)
        if (deductions > 0.0) {
            deductionsRow.visibility = View.VISIBLE
            deductionsText.text = MoneyFormatter.format(-deductions, config.currencyCode)
        } else {
            deductionsRow.visibility = View.GONE
        }
        findViewById<TextView>(R.id.payslipTotal).text = MoneyFormatter.format(total, config.currencyCode)

        renderAllowancesDetail(month, config)
        renderDeductionsDetail(month, config)

        findViewById<TextView>(R.id.payslipClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.payslipShare).setOnClickListener { sharePdf() }
    }

    private fun renderAllowancesDetail(month: SalaryMonth, config: SalaryConfiguration) {
        val container = findViewById<LinearLayout>(R.id.payslipAllowancesContainer)
        container.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val rows = SalaryCalculatorEngine.allowanceBreakdownLines(month, config)
        if (rows.isEmpty()) {
            container.visibility = View.GONE
            findViewById<View>(R.id.payslipAllowancesRow).visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        findViewById<View>(R.id.payslipAllowancesRow).visibility = View.GONE
        rows.forEachIndexed { index, row ->
            val view = inflater.inflate(R.layout.item_allowance_row, container, false)
            val nameText = view.findViewById<TextView>(R.id.allowanceName)
            val typeText = view.findViewById<TextView>(R.id.allowanceType)
            val amountText = view.findViewById<TextView>(R.id.allowanceAmount)

            nameText.text = displayAllowanceName(row)
            val detail = displayAllowanceDetail(row)
            if (detail.isBlank()) {
                typeText.visibility = View.GONE
            } else {
                typeText.visibility = View.VISIBLE
                typeText.text = detail
            }
            amountText.text = MoneyFormatter.format(row.amount, config.currencyCode)
            container.addView(view)

            if (index < rows.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.displayMetrics.density.toInt().coerceAtLeast(1)
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                        bottomMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    setBackgroundColor(getColor(R.color.iosCardStroke))
                }
                container.addView(divider)
            }
        }
    }

    private fun renderDeductionsDetail(month: SalaryMonth, config: SalaryConfiguration) {
        val container = findViewById<LinearLayout>(R.id.payslipDeductionsContainer)
        container.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val rows = config.deductions
            .map { deduction ->
                val amount = SalaryCalculatorEngine.computeDeduction(deduction, month, config)
                DeductionRowData(
                    name = when {
                        deduction.name.isNotBlank() -> deduction.name
                        deduction.type == DeductionType.GOSI_PERSONAL_SAUDI -> getString(R.string.salary_deduction_gosi_name)
                        else -> getString(R.string.salary_settings_deduction_fallback)
                    },
                    detail = deductionTypeLabel(deduction.type),
                    amount = amount
                )
            }
            .filter { it.amount > 0.0 }

        if (rows.isEmpty()) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        rows.forEachIndexed { index, row ->
            val view = inflater.inflate(R.layout.item_allowance_row, container, false)
            val nameText = view.findViewById<TextView>(R.id.allowanceName)
            val typeText = view.findViewById<TextView>(R.id.allowanceType)
            val amountText = view.findViewById<TextView>(R.id.allowanceAmount)

            nameText.text = row.name
            if (row.detail.isBlank()) {
                typeText.visibility = View.GONE
            } else {
                typeText.visibility = View.VISIBLE
                typeText.text = row.detail
            }
            amountText.text = MoneyFormatter.format(-row.amount, config.currencyCode)
            container.addView(view)

            if (index < rows.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.displayMetrics.density.toInt().coerceAtLeast(1)
                    ).apply {
                        topMargin = (6 * resources.displayMetrics.density).toInt()
                        bottomMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                    setBackgroundColor(getColor(R.color.iosCardStroke))
                }
                container.addView(divider)
            }
        }
    }

    private fun allowanceTypeLabel(type: MonthlyAllowanceType): String {
        return when (type) {
            MonthlyAllowanceType.FIXED_MONTHLY -> getString(R.string.allowance_type_fixed_monthly)
            MonthlyAllowanceType.SAUDIZATION_ALLOWANCE -> getString(R.string.allowance_type_saudization)
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

    private fun displayAllowanceName(line: SalaryAllowanceLine): String {
        return when {
            line.id == "housing" -> getString(R.string.salary_housing_allowance)
            line.name.isBlank() -> getString(R.string.salary_settings_allowance_fallback)
            else -> line.name
        }
    }

    private fun displayAllowanceDetail(line: SalaryAllowanceLine): String {
        return runCatching { MonthlyAllowanceType.valueOf(line.type) }
            .getOrNull()
            ?.let { allowanceTypeLabel(it) }
            ?: ""
    }

    private data class DeductionRowData(
        val name: String,
        val detail: String,
        val amount: Double
    )

    private fun sharePdf() {
        val storage = SalaryStorage(this)
        val config = storage.configuration ?: storage.ensureConfiguration()
        val year = intent.getIntExtra(SalaryMonthDetailActivity.EXTRA_YEAR, 0)
        val monthNum = intent.getIntExtra(SalaryMonthDetailActivity.EXTRA_MONTH, 0)
        val month = if (year > 0 && monthNum > 0) {
            storage.selectOrCreateMonth(year, monthNum)
        } else {
            storage.currentMonth()
        }

        val pdfFile = generatePdf(month, config)
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            pdfFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_button)))
    }

    private fun generatePdf(month: SalaryMonth, config: SalaryConfiguration): File {
        val fileName = "Payslip_${month.title}_${config.currencyCode}.pdf"
        val file = File(cacheDir, fileName)

        val document = PdfDocument()
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas: Canvas = page.canvas

        val margin = 36f
        var y = margin

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 20f
            isFakeBoldText = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 12f
        }
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
        }
        val boldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
            isFakeBoldText = true
        }
        val linePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }

        fun newPage() {
            document.finishPage(page)
            pageNumber += 1
            pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            y = margin
        }

        fun ensureSpace(points: Float = 22f) {
            if (y + points > pageInfo.pageHeight - margin) {
                newPage()
            }
        }

        fun drawLine() {
            ensureSpace(14f)
            canvas.drawLine(margin, y, pageInfo.pageWidth - margin, y, linePaint)
            y += 12f
        }

        fun drawTextLine(text: String, paint: Paint = textPaint, indent: Float = 0f) {
            ensureSpace(18f)
            canvas.drawText(text, margin + indent, y, paint)
            y += 16f
        }

        fun drawAmountLine(label: String, amount: String, paint: Paint = textPaint) {
            ensureSpace(18f)
            canvas.drawText(label, margin, y, paint)
            canvas.drawText(amount, pageInfo.pageWidth - margin - 130f, y, paint)
            y += 18f
        }

        canvas.drawText(getString(R.string.payslip_title), margin, y, titlePaint)
        y += 24f
        canvas.drawText(month.title, margin, y, subtitlePaint)
        y += 16f
        drawLine()

        val basic = config.basicSalary
        val allowanceLines = SalaryCalculatorEngine.allowanceBreakdownLines(month, config)
        val allowances = allowanceLines.sumOf { it.amount }
        val deductions = config.deductions.sumOf { SalaryCalculatorEngine.computeDeduction(it, month, config) }
        val total = basic + allowances - deductions

        drawAmountLine(getString(R.string.payslip_basic), MoneyFormatter.format(basic, config.currencyCode))
        if (allowanceLines.isNotEmpty()) {
            drawTextLine(getString(R.string.payslip_allowances), boldPaint)
            allowanceLines.forEach { line ->
                drawAmountLine(displayAllowanceName(line), MoneyFormatter.format(line.amount, config.currencyCode))
            }
        }

        if (deductions > 0.0) {
            drawTextLine(getString(R.string.payslip_deductions), boldPaint)
            config.deductions.forEach { deduction ->
                val value = SalaryCalculatorEngine.computeDeduction(deduction, month, config)
                if (value == 0.0) return@forEach
                val label = when {
                    deduction.name.isNotBlank() -> deduction.name
                    deduction.type == DeductionType.GOSI_PERSONAL_SAUDI -> getString(R.string.salary_deduction_gosi_name)
                    else -> getString(R.string.salary_settings_deduction_fallback)
                }
                drawAmountLine(label, MoneyFormatter.format(-value, config.currencyCode))
            }
        }

        drawLine()
        drawAmountLine(getString(R.string.payslip_total), MoneyFormatter.format(total, config.currencyCode), boldPaint)
        y += 24f

        drawLine()
        drawTextLine(getString(R.string.payslip_audit_details), titlePaint)
        y += 4f

        val dateCol = margin
        val routeCol = margin + 120f
        val timeCol = pageInfo.pageWidth - margin - 80f

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val flights = month.flightLogs.sortedBy { it.date }
        var totalMinutes = 0

        if (flights.isNotEmpty()) {
            drawTextLine(getString(R.string.salary_flights_section_title), boldPaint)
            ensureSpace(18f)
            canvas.drawText(getString(R.string.payslip_header_date), dateCol, y, boldPaint)
            canvas.drawText(getString(R.string.payslip_header_route), routeCol, y, boldPaint)
            canvas.drawText(getString(R.string.payslip_header_time), timeCol, y, boldPaint)
            y += 16f
            drawLine()

            flights.forEach { f ->
                totalMinutes += maxOf(0, f.minutes)
                ensureSpace(16f)
                canvas.drawText(f.date.format(formatter), dateCol, y, textPaint)
                canvas.drawText(if (f.route.isBlank()) "-" else f.route, routeCol, y, textPaint)
                canvas.drawText(SalaryCalculatorEngine.hhmm(f.minutes), timeCol, y, textPaint)
                y += 16f
            }

            drawLine()
            canvas.drawText("", dateCol, y, textPaint)
            canvas.drawText(getString(R.string.payslip_flights_total), routeCol, y, boldPaint)
            canvas.drawText(SalaryCalculatorEngine.hhmm(totalMinutes), timeCol, y, boldPaint)
            y += 16f
            y += 8f
        }

        fun drawDateDetailSection(title: String, rows: List<Pair<LocalDate, String>>) {
            if (rows.isEmpty()) return
            drawTextLine(title, boldPaint)
            ensureSpace(18f)
            canvas.drawText(getString(R.string.payslip_header_date), dateCol, y, boldPaint)
            canvas.drawText(getString(R.string.payslip_header_place), routeCol, y, boldPaint)
            canvas.drawText(getString(R.string.payslip_flights_total), timeCol, y, boldPaint)
            y += 16f
            drawLine()
            rows.sortedBy { it.first }.forEach { row ->
                ensureSpace(16f)
                canvas.drawText(row.first.format(formatter), dateCol, y, textPaint)
                canvas.drawText(if (row.second.isBlank()) "-" else row.second, routeCol, y, textPaint)
                canvas.drawText("1", timeCol, y, textPaint)
                y += 16f
            }
            drawLine()
            canvas.drawText("", dateCol, y, textPaint)
            canvas.drawText(getString(R.string.payslip_flights_total), routeCol, y, boldPaint)
            canvas.drawText(rows.size.toString(), timeCol, y, boldPaint)
            y += 16f
            y += 8f
        }

        drawDateDetailSection(
            getString(R.string.salary_summary_overtime_total_days),
            month.overtimeLogs.map { it.date to getString(R.string.salary_add_menu_overtime) }
        )
        drawDateDetailSection(
            getString(R.string.salary_summary_layover_domestic_total),
            month.layoverLogs.filter { it.kind == LayoverKind.DOMESTIC }.map { it.date to it.location }
        )
        drawDateDetailSection(
            getString(R.string.salary_summary_layover_international_total),
            month.layoverLogs.filter { it.kind == LayoverKind.INTERNATIONAL }.map { it.date to it.location }
        )

        document.finishPage(page)
        FileOutputStream(file).use { output ->
            document.writeTo(output)
        }
        document.close()
        return file
    }
}
