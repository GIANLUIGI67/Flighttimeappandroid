@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class BlockTimeBase {
    SCHEDULED,
    ACTUAL
}

enum class MonthlyAllowanceType {
    FIXED_MONTHLY,
    PER_DUTY,
    PER_DUTY_HOUR,
    PER_OVERTIME_DAY,
    PER_DOMESTIC_LAYOVER_DAY,
    PER_INTERNATIONAL_LAYOVER_DAY,
    PER_FLIGHT_SECTOR,
    PER_BLOCK_HOURS_BANDS,
    OTHER
}

data class MonthlyAllowance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: MonthlyAllowanceType,
    val amount: Double
)

enum class DeductionType {
    MONTHLY,
    DAILY,
    GOSI_PERSONAL_SAUDI
}

data class SalaryDeduction(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: DeductionType,
    val amount: Double
)

data class BlockPayBand(
    val id: String = UUID.randomUUID().toString(),
    val fromHours: Int,
    val ratePerHour: Double
)

data class SalaryConfiguration(
    val currencyCode: String,
    val blockTimeBase: BlockTimeBase,
    val monthlyAllowances: List<MonthlyAllowance>,
    val deductions: List<SalaryDeduction> = emptyList(),
    val blockPayBands: List<BlockPayBand>,
    val blockPayBandsMaxHours: Int = SalaryCalculatorEngine.DEFAULT_BAND_HOURS,
    val basicSalary: Double,
    val housingAllowance: Double = 0.0
) {
    companion object {
        fun default(): SalaryConfiguration {
            return SalaryConfiguration(
                currencyCode = "SAR",
                blockTimeBase = BlockTimeBase.SCHEDULED,
                monthlyAllowances = listOf(
                    MonthlyAllowance(
                        name = "Housing",
                        type = MonthlyAllowanceType.FIXED_MONTHLY,
                        amount = 8333.0
                    ),
                    MonthlyAllowance(
                        name = "Transport",
                        type = MonthlyAllowanceType.FIXED_MONTHLY,
                        amount = 1200.0
                    ),
                    MonthlyAllowance(
                        name = "Domestic layover",
                        type = MonthlyAllowanceType.PER_DOMESTIC_LAYOVER_DAY,
                        amount = 150.0
                    ),
                    MonthlyAllowance(
                        name = "International layover",
                        type = MonthlyAllowanceType.PER_INTERNATIONAL_LAYOVER_DAY,
                        amount = 300.0
                    )
                ),
                deductions = emptyList(),
                blockPayBands = listOf(
                    BlockPayBand(fromHours = 0, ratePerHour = 150.0),
                    BlockPayBand(fromHours = 50, ratePerHour = 300.0),
                    BlockPayBand(fromHours = 75, ratePerHour = 500.0)
                ),
                blockPayBandsMaxHours = SalaryCalculatorEngine.DEFAULT_BAND_HOURS,
                basicSalary = 0.0,
                housingAllowance = 0.0
            )
        }
    }
}

enum class LayoverKind {
    DOMESTIC,
    INTERNATIONAL
}

data class FlightLog(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val route: String,
    val minutes: Int,
    val isScheduled: Boolean
)

data class OvertimeLog(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate
)

data class LayoverLog(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val location: String,
    val kind: LayoverKind
)

data class SalaryMonth(
    val id: String = UUID.randomUUID().toString(),
    val year: Int,
    val month: Int,
    val dutyCount: Int = 0,
    val overtimeDays: Int = 0,
    val layoverDomesticDays: Int = 0,
    val layoverInternationalDays: Int = 0,
    val sectorsCount: Int = 0,
    val scheduledBlockMinutes: Int = 0,
    val actualBlockMinutes: Int = 0,
    val dutyMinutes: Int = 0,
    val flightLogs: List<FlightLog> = emptyList(),
    val overtimeLogs: List<OvertimeLog> = emptyList(),
    val layoverLogs: List<LayoverLog> = emptyList()
) {
    val monthKey: String
        get() = String.format("%04d-%02d", year, month)

    val title: String
        get() = String.format("%04d-%02d", year, month)

    fun syncCountsAlways(): SalaryMonth {
        val newOvertime = overtimeLogs.size.coerceAtLeast(0)
        val newDomestic = layoverLogs.count { it.kind == LayoverKind.DOMESTIC }.coerceAtLeast(0)
        val newInternational = layoverLogs.count { it.kind == LayoverKind.INTERNATIONAL }.coerceAtLeast(0)
        val newSectors = SalaryCalculatorEngine.sectorsForFlights(flightLogs).coerceAtLeast(0)
        val hasFlights = flightLogs.isNotEmpty()
        val scheduledFromFlights = if (hasFlights) {
            flightLogs.filter { it.isScheduled }.sumOf { it.minutes.coerceAtLeast(0) }
        } else {
            scheduledBlockMinutes
        }
        val actualFromFlights = if (hasFlights) {
            flightLogs.filter { !it.isScheduled }.sumOf { it.minutes.coerceAtLeast(0) }
        } else {
            actualBlockMinutes
        }
        return copy(
            overtimeDays = newOvertime,
            layoverDomesticDays = newDomestic,
            layoverInternationalDays = newInternational,
            sectorsCount = newSectors,
            scheduledBlockMinutes = scheduledFromFlights,
            actualBlockMinutes = actualFromFlights
        )
    }

    companion object {
        fun forCurrentMonth(now: LocalDate = LocalDate.now()): SalaryMonth {
            return SalaryMonth(year = now.year, month = now.monthValue)
        }

        fun fromYearMonth(yearMonth: YearMonth): SalaryMonth {
            return SalaryMonth(year = yearMonth.year, month = yearMonth.monthValue)
        }
    }
}
