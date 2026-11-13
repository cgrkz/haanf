package app.haanf.training

import app.haanf.data.WorkoutEntity
import app.haanf.data.zoneSeconds
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
        return Zone.entries.associateWith { zone -> thisWeek.sumOf { it.zoneSeconds().getValue(zone) } / 60 }
    }
}
