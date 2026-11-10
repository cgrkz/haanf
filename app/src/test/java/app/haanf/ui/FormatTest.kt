package app.haanf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FormatTest {
    private val today = LocalDate.of(2026, 11, 5) // a Thursday

    @Test
    fun `a workout under a minute is given in seconds`() {
        assertEquals("12 s", Format.duration(12))
        assertEquals("1 min", Format.duration(60))
        assertEquals("23 min", Format.duration(23 * 60 + 20))
    }

    @Test
    fun `rounds seconds to whole minutes`() {
        assertEquals("0 min", Format.minutes(29))
        assertEquals("1 min", Format.minutes(30))
        assertEquals("32 min", Format.minutes(32 * 60 + 10))
    }

    @Test
    fun `names recent days, then dates`() {
        assertEquals("today", Format.relativeDay(today, today))
        assertEquals("yesterday", Format.relativeDay(today.minusDays(1), today))
        assertEquals("Monday", Format.relativeDay(today.minusDays(3), today))
        assertEquals("29 Oct", Format.relativeDay(today.minusDays(7), today))
    }

    @Test
    fun `names deadlines in the coming week`() {
        assertEquals("today", Format.deadline(today, today))
        assertEquals("tomorrow", Format.deadline(today.plusDays(1), today))
        assertEquals("Sunday", Format.deadline(today.plusDays(3), today))
        assertEquals("12 Nov", Format.deadline(today.plusDays(7), today))
    }

    @Test
    fun `writes hours and minutes with padded minutes`() {
        assertEquals("6 h 40 min", Format.hoursAndMinutes(6 + 40 / 60.0))
        assertEquals("7 h 05 min", Format.hoursAndMinutes(7 + 5 / 60.0))
    }

    @Test
    fun `spells small day counts`() {
        assertEquals("One day", Format.daysWord(1))
        assertEquals("Two days", Format.daysWord(2))
        assertEquals("Five days", Format.daysWord(5))
        assertEquals("9 days", Format.daysWord(9))
    }

    @Test
    fun `describes a change by direction`() {
        assertEquals("down 3", Format.change(-3.2))
        assertEquals("up 5", Format.change(4.6))
        assertEquals("steady", Format.change(0.3))
        assertNull(Format.change(null))
    }
}
