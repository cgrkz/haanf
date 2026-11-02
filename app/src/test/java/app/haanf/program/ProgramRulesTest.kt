package app.haanf.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgramRulesTest {
    private val program = Programs.FOUNDATION_8
    private val monday: LocalDate = LocalDate.of(2026, 11, 2)
    private val trainedWeek3 = ProgramProgress(week = 3, session = 1, lastWeekTrained = 3, lastWorkoutDate = monday)

    private fun position(progress: ProgramProgress, daysLater: Long) =
        ProgramRules.position(progress, monday.plusDays(daysLater))

    @Test
    fun `a new enrolment starts at week 1 with no clock running`() {
        val position = ProgramRules.position(ProgramProgress(), monday.plusDays(90))
        assertEquals(1, position.week)
        assertEquals(0, position.session)
        assertEquals(ProgramNotice.None, position.notice)
        assertNull(position.daysUntilRepeat)
    }

    @Test
    fun `stays in place up to 6 days away and counts down`() {
        val position = position(trainedWeek3, 6)
        assertEquals(3, position.week)
        assertEquals(1, position.session)
        assertEquals(ProgramNotice.None, position.notice)
        assertEquals(1L, position.daysUntilRepeat)
        assertEquals(24L, position.daysUntilRestart)
    }

    @Test
    fun `7 days away repeats the last week trained from its first session`() {
        val position = position(trainedWeek3, 7)
        assertEquals(3, position.week)
        assertEquals(0, position.session)
        assertEquals(ProgramNotice.RepeatingWeek(3, 7), position.notice)
        assertEquals(23L, position.daysUntilRestart)
    }

    @Test
    fun `finishing a week then missing 7 days repeats that week, not the next one`() {
        val finishedWeek3 = ProgramProgress(week = 4, session = 0, lastWeekTrained = 3, lastWorkoutDate = monday)
        assertEquals(3, position(finishedWeek3, 7).week)
    }

    @Test
    fun `29 days away still repeats the week`() {
        assertEquals(3, position(trainedWeek3, 29).week)
    }

    @Test
    fun `30 days away restarts at week 1`() {
        val position = position(trainedWeek3, 30)
        assertEquals(1, position.week)
        assertEquals(0, position.session)
        assertEquals(ProgramNotice.RestartedProgram(30), position.notice)
    }

    @Test
    fun `a workout moves to the next session and restarts the clock`() {
        val after = ProgramRules.afterWorkout(trainedWeek3, program, monday.plusDays(2))
        assertEquals(3, after.week)
        assertEquals(2, after.session)
        assertEquals(3, after.lastWeekTrained)
        assertEquals(monday.plusDays(2), after.lastWorkoutDate)
    }

    @Test
    fun `the last session of a week moves to the next week`() {
        val lastOfWeek = trainedWeek3.copy(session = 2)
        val after = ProgramRules.afterWorkout(lastOfWeek, program, monday.plusDays(1))
        assertEquals(4, after.week)
        assertEquals(0, after.session)
        assertEquals(3, after.lastWeekTrained)
    }

    @Test
    fun `a workout after a reset continues from the reset position`() {
        val after = ProgramRules.afterWorkout(trainedWeek3, program, monday.plusDays(8))
        assertEquals(3, after.week)
        assertEquals(1, after.session)

        val afterRestart = ProgramRules.afterWorkout(trainedWeek3, program, monday.plusDays(31))
        assertEquals(1, afterRestart.week)
        assertEquals(1, afterRestart.session)
        assertEquals(1, afterRestart.lastWeekTrained)
    }

    @Test
    fun `missing time after a reset does not lose the repeat`() {
        val afterGap = ProgramRules.afterWorkout(trainedWeek3, program, monday.plusDays(8))
        assertEquals(ProgramNotice.None, ProgramRules.position(afterGap, monday.plusDays(10)).notice)
    }

    @Test
    fun `the final session completes the program`() {
        val last = ProgramProgress(week = 8, session = 2, lastWeekTrained = 8, lastWorkoutDate = monday)
        assertTrue(ProgramRules.afterWorkout(last, program, monday.plusDays(1)).completed)
        val notLast = ProgramProgress(week = 8, session = 1, lastWeekTrained = 8, lastWorkoutDate = monday)
        assertFalse(ProgramRules.afterWorkout(notLast, program, monday.plusDays(1)).completed)
    }

    @Test
    fun `walks the whole program from start to finish`() {
        var progress = ProgramProgress()
        var day = monday
        var sessions = 0
        while (!progress.completed) {
            val position = ProgramRules.position(progress, day)
            program.session(position.week, position.session)
            progress = ProgramRules.afterWorkout(progress, program, day)
            day = day.plusDays(2)
            sessions++
        }
        assertEquals(24, sessions)
    }
}
