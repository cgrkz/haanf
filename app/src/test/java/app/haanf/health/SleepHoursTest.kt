package app.haanf.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SleepHoursTest {
    private val zone = ZoneOffset.UTC

    private fun at(text: String): Instant = Instant.parse("2026-11-${text}:00Z")
    private fun span(start: String, end: String) = TimeSpan(at(start), at(end))
    private fun hours(sessions: List<SleepSession>) = SleepHours.daily(sessions, zone)

    @Test
    fun `the same night saved by two apps counts once`() {
        val night = SleepSession(span("07T23:00", "08T06:00"))
        val days = hours(listOf(night, night.copy()))
        assertEquals(listOf(DayValue(LocalDate.of(2026, 11, 8), 7.0)), days)
    }

    @Test
    fun `time marked awake is left out, even when only one copy has it`() {
        val withStages = SleepSession(span("07T23:00", "08T06:00"), listOf(span("08T02:00", "08T02:30")))
        val bare = SleepSession(span("07T23:00", "08T06:00"))
        assertEquals(6.5, hours(listOf(withStages, bare)).single().value, 0.001)
    }

    @Test
    fun `copies that disagree by a few minutes still count once`() {
        val days = hours(listOf(SleepSession(span("07T23:00", "08T06:00")), SleepSession(span("07T23:10", "08T06:20"))))
        assertEquals(7.0 + 20 / 60.0, days.single().value, 0.001)
    }

    @Test
    fun `a nap adds to the day it ends on`() {
        val days = hours(listOf(SleepSession(span("07T23:00", "08T06:00")), SleepSession(span("08T14:00", "08T14:45"))))
        assertEquals(7.75, days.single().value, 0.001)
    }

    @Test
    fun `each night lands on the day woken up on`() {
        val days = hours(listOf(SleepSession(span("08T22:30", "09T05:30")), SleepSession(span("07T23:00", "08T06:00"))))
        assertEquals(listOf(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 9)), days.map { it.date })
    }
}
