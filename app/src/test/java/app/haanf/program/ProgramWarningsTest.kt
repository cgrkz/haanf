package app.haanf.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ProgramWarningsTest {
    private val monday: LocalDate = LocalDate.of(2026, 11, 2)
    private val progress = ProgramProgress(week = 5, session = 1, lastWeekTrained = 5, lastWorkoutDate = monday)

    private fun warning(daysLater: Long) = monday.plusDays(daysLater).let { today ->
        ProgramWarnings.upcoming(progress, ProgramRules.position(progress, today), today)
    }

    @Test
    fun `stays quiet with more than 2 days before a repeat`() {
        assertNull(warning(4))
    }

    @Test
    fun `warns with 2 days left and names the last day`() {
        val warning = warning(5) as ProgramWarning.WeekWillRepeat
        assertEquals(2L, warning.daysLeft)
        assertEquals(5, warning.week)
        assertEquals(monday.plusDays(6), warning.lastDay)
    }

    @Test
    fun `on the last day, the last day is today`() {
        val warning = warning(6) as ProgramWarning.WeekWillRepeat
        assertEquals(1L, warning.daysLeft)
        assertEquals(monday.plusDays(6), warning.lastDay)
    }

    @Test
    fun `once the week repeats, warns before a restart from 5 days out`() {
        assertNull(warning(24))
        val warning = warning(25) as ProgramWarning.ProgramWillRestart
        assertEquals(5L, warning.daysLeft)
        assertEquals(monday.plusDays(29), warning.lastDay)
    }

    @Test
    fun `a new enrolment has nothing to warn about`() {
        val fresh = ProgramProgress()
        assertNull(ProgramWarnings.upcoming(fresh, ProgramRules.position(fresh, monday), monday))
    }

    @Test
    fun `a finished program has nothing to warn about`() {
        val done = progress.copy(completed = true)
        assertNull(ProgramWarnings.upcoming(done, ProgramRules.position(done, monday.plusDays(6)), monday.plusDays(6)))
    }
}
