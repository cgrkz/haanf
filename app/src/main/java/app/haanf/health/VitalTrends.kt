package app.haanf.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class DayValue(val date: LocalDate, val value: Double)

data class VitalTrend(
    val latest: DayValue,
    val recentAverage: Double?,
    val previousAverage: Double?,
    val daysSinceLatest: Long,
    /** Every day with a value, oldest first, for drawing the trend. */
    val days: List<DayValue> = emptyList(),
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
            days = days.sortedBy { it.date },
            recentAverage = average(0, WINDOW_DAYS - 1),
            previousAverage = average(WINDOW_DAYS, WINDOW_DAYS * 2 - 1),
            daysSinceLatest = ChronoUnit.DAYS.between(latest.date, today),
        )
    }
}

/** A stretch of time, such as one sleep session or one spell awake within it. */
data class TimeSpan(val start: Instant, val end: Instant)

/** One sleep session as Health Connect holds it, with the spells marked awake or out of bed. */
data class SleepSession(val span: TimeSpan, val awake: List<TimeSpan> = emptyList())

object SleepHours {
    /**
     * Hours asleep for each day woken up on. Sessions that overlap count once, because two apps
     * can save the same night, and time marked awake is left out.
     */
    fun daily(sessions: List<SleepSession>, zone: ZoneId): List<DayValue> {
        val nights = merge(sessions.map { it.span })
        val awake = merge(sessions.flatMap { it.awake })
        return nights
            .map { night ->
                val awakeSeconds = awake.sumOf { overlapSeconds(it, night) }
                DayValue(night.end.atZone(zone).toLocalDate(), (seconds(night) - awakeSeconds) / 3600.0)
            }
            .groupBy { it.date }
            .map { (date, values) -> DayValue(date, values.sumOf { it.value }) }
            .sortedBy { it.date }
    }

    /** Joins spans that overlap or touch, oldest first. */
    private fun merge(spans: List<TimeSpan>): List<TimeSpan> {
        val merged = mutableListOf<TimeSpan>()
        spans.filter { it.end > it.start }.sortedBy { it.start }.forEach { span ->
            val last = merged.lastOrNull()
            if (last != null && span.start <= last.end) {
                merged[merged.lastIndex] = TimeSpan(last.start, maxOf(last.end, span.end))
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun seconds(span: TimeSpan): Long = span.end.epochSecond - span.start.epochSecond

    private fun overlapSeconds(a: TimeSpan, b: TimeSpan): Long =
        (minOf(a.end, b.end).epochSecond - maxOf(a.start, b.start).epochSecond).coerceAtLeast(0)
}
