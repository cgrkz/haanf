package app.haanf.training

import app.haanf.data.WorkoutEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WeekTotalsTest {
    private val wednesday: LocalDate = LocalDate.of(2026, 11, 4)

    private fun workout(day: LocalDate, easyMinutes: Int, hardMinutes: Int = 0) = WorkoutEntity(
        startedAtMs = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + 3_600_000,
        title = "w",
        activity = "TREADMILL",
        origin = "FREE",
        zone2Seconds = easyMinutes * 60,
        zone4Seconds = hardMinutes * 60,
    )

    @Test
    fun `counts Monday to today only`() {
        val minutes = WeekTotals.zoneMinutes(
            listOf(
                workout(wednesday, 30, 8),
                workout(wednesday.minusDays(2), 20),
                workout(wednesday.minusDays(3), 99),
                workout(wednesday.plusDays(1), 99),
            ),
            wednesday,
            ZoneOffset.UTC,
        )
        assertEquals(50, minutes[Zone.ZONE_2])
        assertEquals(8, minutes[Zone.ZONE_4])
        assertEquals(0, minutes[Zone.ZONE_5])
    }

    @Test
    fun `an empty week is all zeros`() {
        assertEquals(0, WeekTotals.zoneMinutes(emptyList(), wednesday, ZoneOffset.UTC).values.sum())
    }
}
