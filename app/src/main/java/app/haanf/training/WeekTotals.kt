package app.haanf.training

import app.haanf.data.WorkoutEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Minutes in each zone for the week so far, Monday to today. */
object WeekTotals {
    fun zoneMinutes(workouts: List<WorkoutEntity>, today: LocalDate, zone: ZoneId): Map<Zone, Int> {
        val monday = today.with(DayOfWeek.MONDAY)
        val thisWeek = workouts.filter {
            val day = Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate()
            !day.isBefore(monday) && !day.isAfter(today)
        }
        return mapOf(
            Zone.RECOVERY to thisWeek.sumOf { it.recoverySeconds } / 60,
            Zone.EASY to thisWeek.sumOf { it.easySeconds } / 60,
            Zone.HARD to thisWeek.sumOf { it.hardSeconds } / 60,
            Zone.VERY_HARD to thisWeek.sumOf { it.veryHardSeconds } / 60,
        )
    }
}
