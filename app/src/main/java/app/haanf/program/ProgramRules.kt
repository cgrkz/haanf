package app.haanf.program

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What is saved for an enrolment. [week] is 1-based and [session] is 0-based. */
data class ProgramProgress(
    val week: Int = 1,
    val session: Int = 0,
    val lastWeekTrained: Int? = null,
    val lastWorkoutDate: LocalDate? = null,
    val completed: Boolean = false,
)

sealed interface ProgramNotice {
    data object None : ProgramNotice
    data class RepeatingWeek(val week: Int, val daysAway: Long) : ProgramNotice
    data class RestartedProgram(val daysAway: Long) : ProgramNotice
}

data class ProgramPosition(
    val week: Int,
    val session: Int,
    val notice: ProgramNotice,
    val daysUntilRepeat: Long?,
    val daysUntilRestart: Long?,
)

/**
 * The reset rules from the product brief. Resets are worked out from the saved progress
 * each time, so nothing is lost or written until a workout actually counts. Only program
 * workouts reach [afterWorkout], which is why other workouts cannot stop the clock.
 */
object ProgramRules {
    const val REPEAT_WEEK_AFTER_DAYS = 7L
    const val RESTART_AFTER_DAYS = 30L

    fun position(progress: ProgramProgress, today: LocalDate): ProgramPosition {
        val last = progress.lastWorkoutDate
        val lastWeek = progress.lastWeekTrained
        if (last == null || lastWeek == null) {
            return ProgramPosition(progress.week, progress.session, ProgramNotice.None, null, null)
        }
        val daysAway = ChronoUnit.DAYS.between(last, today).coerceAtLeast(0)
        return when {
            daysAway >= RESTART_AFTER_DAYS ->
                ProgramPosition(1, 0, ProgramNotice.RestartedProgram(daysAway), null, null)
            daysAway >= REPEAT_WEEK_AFTER_DAYS -> ProgramPosition(
                week = lastWeek,
                session = 0,
                notice = ProgramNotice.RepeatingWeek(lastWeek, daysAway),
                daysUntilRepeat = null,
                daysUntilRestart = RESTART_AFTER_DAYS - daysAway,
            )
            else -> ProgramPosition(
                week = progress.week,
                session = progress.session,
                notice = ProgramNotice.None,
                daysUntilRepeat = REPEAT_WEEK_AFTER_DAYS - daysAway,
                daysUntilRestart = RESTART_AFTER_DAYS - daysAway,
            )
        }
    }

    /** Progress after a counted program workout done at today's position. */
    fun afterWorkout(
        progress: ProgramProgress,
        program: ProgramDefinition,
        today: LocalDate,
    ): ProgramProgress {
        val done = position(progress, today)
        val sessionsInWeek = program.weeks[done.week - 1].sessions.size
        val weekFinished = done.session + 1 >= sessionsInWeek
        val programFinished = weekFinished && done.week >= program.weeks.size
        return ProgramProgress(
            week = if (weekFinished && !programFinished) done.week + 1 else done.week,
            session = if (weekFinished) 0 else done.session + 1,
            lastWeekTrained = done.week,
            lastWorkoutDate = today,
            completed = programFinished,
        )
    }
}
