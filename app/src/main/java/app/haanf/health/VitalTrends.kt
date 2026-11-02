package app.haanf.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class DayValue(val date: LocalDate, val value: Double)

data class VitalTrend(
    val latest: DayValue,
    val recentAverage: Double?,
    val previousAverage: Double?,
    val daysSinceLatest: Long,
) {
    /** Recovery data can stop for days, so anything using it must see how old it is. */
    val stale: Boolean get() = daysSinceLatest >= STALE_AFTER_DAYS

    /** Change from the previous 7 days to the last 7, when both have data. */
    val change: Double?
        get() = if (recentAverage != null && previousAverage != null) recentAverage - previousAverage else null

    companion object {
        const val STALE_AFTER_DAYS = 3L
    }
}

data class Vitals(
    val restingHeartRate: VitalTrend?,
    val hrv: VitalTrend?,
    val sleepHours: VitalTrend?,
)

object VitalTrends {
    private const val WINDOW_DAYS = 7L

    /** One value per day, combining a day's readings with [combine]. */
    fun daily(
        readings: List<DayValue>,
        combine: (List<Double>) -> Double,
    ): List<DayValue> =
        readings.groupBy { it.date }
            .map { (date, values) -> DayValue(date, combine(values.map { it.value })) }
            .sortedBy { it.date }

    fun trend(days: List<DayValue>, today: LocalDate): VitalTrend? {
        val latest = days.maxByOrNull { it.date } ?: return null
        fun average(fromDaysAgo: Long, toDaysAgo: Long): Double? =
            days.filter { ChronoUnit.DAYS.between(it.date, today) in fromDaysAgo..toDaysAgo }
                .map { it.value }
                .takeIf { it.isNotEmpty() }
                ?.average()
        return VitalTrend(
            latest = latest,
            recentAverage = average(0, WINDOW_DAYS - 1),
            previousAverage = average(WINDOW_DAYS, WINDOW_DAYS * 2 - 1),
            daysSinceLatest = ChronoUnit.DAYS.between(latest.date, today),
        )
    }
}
