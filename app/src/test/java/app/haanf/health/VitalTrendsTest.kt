package app.haanf.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VitalTrendsTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 2)

    private fun day(daysAgo: Long, value: Double) = DayValue(today.minusDays(daysAgo), value)

    @Test
    fun `compares the last 7 days with the 7 before`() {
        val trend = VitalTrends.trend(listOf(day(0, 60.0), day(6, 62.0), day(7, 66.0), day(13, 68.0)), today)!!
        assertEquals(61.0, trend.recentAverage!!, 0.0)
        assertEquals(67.0, trend.previousAverage!!, 0.0)
        assertEquals(-6.0, trend.change!!, 0.0)
        assertEquals(today, trend.latest.date)
    }

    @Test
    fun `marks old data as stale, as with HRV stopping days ago`() {
        val trend = VitalTrends.trend(listOf(day(4, 40.0), day(8, 44.0)), today)!!
        assertEquals(4L, trend.daysSinceLatest)
        assertTrue(trend.stale)
    }

    @Test
    fun `recent data is not stale`() {
        assertFalse(VitalTrends.trend(listOf(day(2, 40.0)), today)!!.stale)
    }

    @Test
    fun `leaves out the change when one window is empty`() {
        val trend = VitalTrends.trend(listOf(day(1, 60.0)), today)!!
        assertNull(trend.previousAverage)
        assertNull(trend.change)
    }

    @Test
    fun `no readings means no trend`() {
        assertNull(VitalTrends.trend(emptyList(), today))
    }

    @Test
    fun `combines each day's readings`() {
        val readings = listOf(day(1, 40.0), day(1, 50.0), day(0, 30.0))
        assertEquals(listOf(day(1, 45.0), day(0, 30.0)), VitalTrends.daily(readings) { it.average() })
        assertEquals(listOf(day(1, 90.0), day(0, 30.0)), VitalTrends.daily(readings) { it.sum() })
    }

    @Test
    fun `keeps every day, oldest first, for the sparkline`() {
        val trend = VitalTrends.trend(listOf(day(0, 60.0), day(10, 64.0), day(3, 61.0)), today)!!
        assertEquals(listOf(64.0, 61.0, 60.0), trend.days.map { it.value })
    }
}
