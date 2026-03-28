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

        val allowancesBase = config.monthlyAllowances
            .filter { it.type != MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS }
            .sumOf { SalaryCalculatorEngine.computeAllowance(it, month, config) }
        val bandsPay = SalaryCalculatorEngine.progressiveBlockPay(month, config)
        val housing = config.housingAllowance
        val allowances = allowancesBase + bandsPay
        val deductions = config.deductions.sumOf { SalaryCalculatorEngine.computeDeduction(it, month, config) }
        val basic = config.basicSalary
        val total = basic + housing + allowances - deductions

        findViewById<TextView>(R.id.payslipMonth).text = month.title
        findViewById<TextView>(R.id.payslipBasic).text = MoneyFormatter.format(basic, config.currencyCode)
        val housingRow = findViewById<View>(R.id.payslipHousingRow)
        val housingText = findViewById<TextView>(R.id.payslipHousing)
        if (housing > 0.0) {
            housingRow.visibility = View.VISIBLE
            housingText.text = MoneyFormatter.format(housing, config.currencyCode)
        } else {
            housingRow.visibility = View.GONE
        }
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
        val allowanceItems = config.monthlyAllowances
            .filter { it.type != MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS }
            .map { allowance ->
                val amount = SalaryCalculatorEngine.computeAllowance(allowance, month, config)
                AllowanceRowData(
                    name = allowance.name,
                    detail = allowanceTypeLabel(allowance.type),
                    amount = amount
                )
            }
            .filter { it.amount > 0.0 }

        val bandTitle = config.monthlyAllowances
            .firstOrNull { it.type == MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS }
            ?.name
            ?: getString(R.string.allowance_type_progressive_block_bands)
        val bandSegments = SalaryCalculatorEngine.progressiveBandsBreakdown(month, config, bandTitle)
            .filter { it.amount > 0.0 }
            .map {
                val rateText = MoneyFormatter.format(it.ratePerHour, config.currencyCode)
                val label = getString(
                    R.string.salary_band_label_format,
                    bandTitle,
                    it.fromHours,
                    it.toHours,
                    rateText
                )
                AllowanceRowData(
                    name = label,
                    detail = "",
                    amount = it.amount
                )
            }

        val rows = allowanceItems + bandSegments
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

    private data class AllowanceRowData(
        val name: String,
        val detail: String,
        val amount: Double
    )

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
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas: Canvas = page.canvas

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

        fun drawLine() {
            canvas.drawLine(margin, y, pageInfo.pageWidth - margin, y, linePaint)
            y += 12f
        }

        canvas.drawText(getString(R.string.payslip_title), margin, y, titlePaint)
        y += 24f
        canvas.drawText(month.title, margin, y, subtitlePaint)
        y += 16f
        drawLine()

        val basic = config.basicSalary
        val allowancesBase = config.monthlyAllowances
            .filter { it.type != MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS }
            .sumOf { SalaryCalculatorEngine.computeAllowance(it, month, config) }
        val bandsPay = SalaryCalculatorEngine.progressiveBlockPay(month, config)
        val housing = config.housingAllowance
        val allowances = allowancesBase + bandsPay
        val deductions = config.deductions.sumOf { SalaryCalculatorEngine.computeDeduction(it, month, config) }
        val total = basic + housing + allowances - deductions

        canvas.drawText(getString(R.string.payslip_basic), margin, y, textPaint)
        canvas.drawText(MoneyFormatter.format(basic, config.currencyCode), pageInfo.pageWidth - margin - 120f, y, textPaint)
        y += 18f
        if (housing > 0.0) {
            canvas.drawText(getString(R.string.salary_housing_allowance), margin, y, textPaint)
            canvas.drawText(MoneyFormatter.format(housing, config.currencyCode), pageInfo.pageWidth - margin - 120f, y, textPaint)
            y += 18f
        }
        canvas.drawText(getString(R.string.payslip_allowances), margin, y, textPaint)
        canvas.drawText(MoneyFormatter.format(allowances, config.currencyCode), pageInfo.pageWidth - margin - 120f, y, textPaint)
        y += 18f

        if (deductions > 0.0) {
            canvas.drawText(getString(R.string.payslip_deductions), margin, y, textPaint)
            canvas.drawText(MoneyFormatter.format(-deductions, config.currencyCode), pageInfo.pageWidth - margin - 120f, y, textPaint)
            y += 18f
        }

        val dutyTimeTotal = config.monthlyAllowances
            .filter { it.type == MonthlyAllowanceType.PER_DUTY_HOUR }
            .sumOf { SalaryCalculatorEngine.computeAllowance(it, month, config) }
        if (dutyTimeTotal > 0.0) {
            canvas.drawText(getString(R.string.salary_duty_time_pay), margin, y, textPaint)
            canvas.drawText(MoneyFormatter.format(dutyTimeTotal, config.currencyCode), pageInfo.pageWidth - margin - 120f, y, textPaint)
            y += 18f
        }

        val sectorTotal = config.monthlyAllowances
            .filter { it.type == MonthlyAllowanceType.PER_FLIGHT_SECTOR }
            .sumOf { SalaryCalculatorEngine.computeAllowance(it, month, config) }
        if (sectorTotal > 0.0) {
            canvas.drawText(getString(R.string.salary_sector_pay), margin, y, textPaint)
            canvas.drawText(MoneyFormatter.format(sectorTotal, config.currencyCode), pageInfo.pageWidth - margin - 120f, y, textPaint)
            y += 18f
        }

        if (bandsPay > 0.0) {
            canvas.drawText(getString(R.string.allowance_type_progressive_block_bands), margin, y, textPaint)
            canvas.drawText(MoneyFormatter.format(bandsPay, config.currencyCode), pageInfo.pageWidth - margin - 120f, y, textPaint)
            y += 18f
        }

        drawLine()
        canvas.drawText(getString(R.string.payslip_total), margin, y, boldPaint)
        canvas.drawText(MoneyFormatter.format(total, config.currencyCode), pageInfo.pageWidth - margin - 120f, y, boldPaint)
        y += 24f

        drawLine()

        // Flights table
        val headerDate = getString(R.string.payslip_header_date)
        val headerRoute = getString(R.string.payslip_header_route)
        val headerTime = getString(R.string.payslip_header_time)

        val dateCol = margin
        val routeCol = margin + 120f
        val timeCol = pageInfo.pageWidth - margin - 80f

        canvas.drawText(headerDate, dateCol, y, boldPaint)
        canvas.drawText(headerRoute, routeCol, y, boldPaint)
        canvas.drawText(headerTime, timeCol, y, boldPaint)
        y += 16f
        drawLine()

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val flights = month.flightLogs.sortedBy { it.date }
        var totalMinutes = 0

        flights.forEach { f ->
            if (y > pageInfo.pageHeight - 60f) {
                document.finishPage(page)
            }
            totalMinutes += maxOf(0, f.minutes)
            val dateText = f.date.format(formatter)
            val routeText = if (f.route.isBlank()) "—" else f.route
            val timeText = SalaryCalculatorEngine.hhmm(f.minutes)

            canvas.drawText(dateText, dateCol, y, textPaint)
            canvas.drawText(routeText, routeCol, y, textPaint)
            canvas.drawText(timeText, timeCol, y, textPaint)
            y += 16f
        }

        if (flights.isNotEmpty()) {
            drawLine()
            canvas.drawText("", dateCol, y, textPaint)
            canvas.drawText(getString(R.string.payslip_flights_total), routeCol, y, boldPaint)
            canvas.drawText(SalaryCalculatorEngine.hhmm(totalMinutes), timeCol, y, boldPaint)
            y += 16f
        }

        document.finishPage(page)
        FileOutputStream(file).use { output ->
            document.writeTo(output)
        }
        document.close()
        return file
    }
}
