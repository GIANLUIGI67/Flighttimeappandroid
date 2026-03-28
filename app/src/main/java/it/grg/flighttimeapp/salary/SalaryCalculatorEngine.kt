package it.grg.flighttimeapp.salary

import kotlin.math.max
import kotlin.math.min

object SalaryCalculatorEngine {

    const val DEFAULT_BAND_HOURS = 100
    const val MAX_BAND_HOURS_LIMIT = 120

    private fun effectiveMaxBandHours(config: SalaryConfiguration): Int {
        val normalized = max(config.blockPayBandsMaxHours, DEFAULT_BAND_HOURS)
        return min(normalized, MAX_BAND_HOURS_LIMIT)
    }

    fun hhmm(minutes: Int): String {
        val m = max(0, minutes)
        val h = m / 60
        val mm = m % 60
        return String.format("%02d:%02d", h, mm)
    }

    fun baseBlockMinutes(month: SalaryMonth, config: SalaryConfiguration): Int {
        val scheduled = max(0, month.scheduledBlockMinutes)
        val actual = max(0, month.actualBlockMinutes)
        val scheduledFromLogs = if (month.flightLogs.isNotEmpty()) {
            month.flightLogs.filter { it.isScheduled }.sumOf { it.minutes.coerceAtLeast(0) }
        } else {
            0
        }
        val actualFromLogs = if (month.flightLogs.isNotEmpty()) {
            month.flightLogs.filter { !it.isScheduled }.sumOf { it.minutes.coerceAtLeast(0) }
        } else {
            0
        }
        val effectiveScheduled = if (scheduled > 0) scheduled else scheduledFromLogs
        val effectiveActual = if (actual > 0) actual else actualFromLogs
        val primary = when (config.blockTimeBase) {
            BlockTimeBase.SCHEDULED -> effectiveScheduled
            BlockTimeBase.ACTUAL -> effectiveActual
        }
        return if (primary > 0) primary else max(effectiveScheduled, effectiveActual)
    }

    fun sectorsForRoute(route: String): Int {
        val cleaned = route.trim()
        if (cleaned.isEmpty()) return 0
        val parts = cleaned.split("-").map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size <= 1 -> 1
            else -> parts.size - 1
        }
    }

    fun sectorsForFlights(logs: List<FlightLog>): Int {
        return logs.sumOf { sectorsForRoute(it.route) }.coerceAtLeast(0)
    }

    fun computeAllowance(
        allowance: MonthlyAllowance,
        month: SalaryMonth,
        config: SalaryConfiguration
    ): Double {
        return when (allowance.type) {
            MonthlyAllowanceType.FIXED_MONTHLY -> allowance.amount
            MonthlyAllowanceType.PER_DUTY -> month.dutyCount * allowance.amount
            MonthlyAllowanceType.PER_DUTY_HOUR -> (month.dutyMinutes.coerceAtLeast(0) / 60.0) * allowance.amount
            MonthlyAllowanceType.PER_OVERTIME_DAY -> month.overtimeDays * allowance.amount
            MonthlyAllowanceType.PER_DOMESTIC_LAYOVER_DAY -> month.layoverDomesticDays * allowance.amount
            MonthlyAllowanceType.PER_INTERNATIONAL_LAYOVER_DAY -> month.layoverInternationalDays * allowance.amount
            MonthlyAllowanceType.PER_FLIGHT_SECTOR -> month.sectorsCount * allowance.amount
            MonthlyAllowanceType.PER_BLOCK_HOURS_BANDS -> {
                val minutes = baseBlockMinutes(month, config)
                val maxHours = effectiveMaxBandHours(config)
                computeProgressiveBlockPay(minutes, config.blockPayBands, maxHours)
            }
            MonthlyAllowanceType.OTHER -> allowance.amount
        }
    }

    fun computeDeduction(
        deduction: SalaryDeduction,
        month: SalaryMonth,
        config: SalaryConfiguration
    ): Double {
        return when (deduction.type) {
            DeductionType.MONTHLY -> deduction.amount
            DeductionType.DAILY -> month.dutyCount * deduction.amount
            DeductionType.GOSI_PERSONAL_SAUDI -> {
                val base = config.basicSalary + config.housingAllowance
                base * 0.0975
            }
        }
    }

    fun progressiveBlockPay(month: SalaryMonth, config: SalaryConfiguration): Double {
        if (config.blockPayBands.isEmpty()) return 0.0
        val minutes = baseBlockMinutes(month, config)
        val maxHours = effectiveMaxBandHours(config)
        return computeProgressiveBlockPay(minutes, config.blockPayBands, maxHours)
    }

    fun computeProgressiveBlockPay(
        totalBlockMinutes: Int,
        bands: List<BlockPayBand>,
        maxBandHours: Int
    ): Double {
        val segments = computeProgressiveSegments(totalBlockMinutes, bands, maxBandHours)
        return segments.sumOf { it.amount }
    }

    fun progressiveBandsBreakdown(
        month: SalaryMonth,
        config: SalaryConfiguration,
        title: String
    ): List<ProgressiveBandSegment> {
        val baseMinutes = baseBlockMinutes(month, config)
        val maxBandHours = effectiveMaxBandHours(config)
        val capped = min(max(0, baseMinutes), maxBandHours * 60)

        val cleanTitle = title.trim()
        val baseTitle = if (cleanTitle.isEmpty()) "Flying allowance" else cleanTitle

        val internals = computeProgressiveSegments(capped, config.blockPayBands, maxBandHours)
        return internals.map { s ->
            var label = "$baseTitle ${s.fromHours}–${s.toHours}h"
            if (s.toHours == maxBandHours) {
                label += " (MAX)"
            }
            ProgressiveBandSegment(
                fromHours = s.fromHours,
                toHours = s.toHours,
                minutesInBand = s.minutesInBand,
                ratePerHour = s.ratePerHour,
                amount = s.amount,
                label = label
            )
        }
    }

    private fun computeProgressiveSegments(
        totalBlockMinutes: Int,
        bands: List<BlockPayBand>,
        maxBandHours: Int
    ): List<ProgressiveBandSegmentInternal> {
        val total = min(max(0, totalBlockMinutes), maxBandHours * 60)

        var sorted = bands
            .map { b ->
                BlockPayBand(
                    id = b.id,
                    fromHours = min(max(b.fromHours, 0), maxBandHours),
                    ratePerHour = b.ratePerHour
                )
            }
            .sortedBy { it.fromHours }

        if (sorted.firstOrNull()?.fromHours != 0) {
            sorted = listOf(BlockPayBand(fromHours = 0, ratePerHour = 0.0)) + sorted
        }

        sorted = sorted.filter { it.fromHours < maxBandHours }
        if (sorted.isEmpty()) {
            sorted = listOf(BlockPayBand(fromHours = 0, ratePerHour = 0.0))
        }

        val result = mutableListOf<ProgressiveBandSegmentInternal>()
        for (i in sorted.indices) {
            val fromH = sorted[i].fromHours
            val toH = if (i < sorted.size - 1) min(sorted[i + 1].fromHours, maxBandHours) else maxBandHours

            val fromM = fromH * 60
            val toM = toH * 60
            if (toM <= fromM) continue

            val minutesInBand = min(max(total - fromM, 0), toM - fromM)
            if (minutesInBand <= 0) continue

            val hours = minutesInBand / 60.0
            val rate = sorted[i].ratePerHour
            val amount = hours * rate

            result.add(
                ProgressiveBandSegmentInternal(
                    fromHours = fromH,
                    toHours = toH,
                    minutesInBand = minutesInBand,
                    ratePerHour = rate,
                    amount = amount
                )
            )
        }

        return result
    }
}

data class ProgressiveBandSegment(
    val fromHours: Int,
    val toHours: Int,
    val minutesInBand: Int,
    val ratePerHour: Double,
    val amount: Double,
    val label: String
)

private data class ProgressiveBandSegmentInternal(
    val fromHours: Int,
    val toHours: Int,
    val minutesInBand: Int,
    val ratePerHour: Double,
    val amount: Double
)
