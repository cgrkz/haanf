package app.haanf.program

import java.time.LocalDate

/** A reset is close. [lastDay] is the last day a program workout keeps the athlete's place. */
sealed interface ProgramWarning {
    val daysLeft: Long
    val lastDay: LocalDate

    data class WeekWillRepeat(val week: Int, override val daysLeft: Long, override val lastDay: LocalDate) : ProgramWarning
    data class ProgramWillRestart(override val daysLeft: Long, override val lastDay: LocalDate) : ProgramWarning
}

object ProgramWarnings {
    const val WARN_BEFORE_REPEAT_DAYS = 2L
    const val WARN_BEFORE_RESTART_DAYS = 5L

    /** A warning when 2 or fewer days remain before a repeat, or 5 or fewer before a restart. */
    fun upcoming(progress: ProgramProgress, position: ProgramPosition, today: LocalDate): ProgramWarning? {
        if (progress.completed) return null
        val toRepeat = position.daysUntilRepeat
        val toRestart = position.daysUntilRestart
        val lastWeek = progress.lastWeekTrained
        return when {
            toRepeat != null && lastWeek != null && toRepeat <= WARN_BEFORE_REPEAT_DAYS ->
                ProgramWarning.WeekWillRepeat(lastWeek, toRepeat, today.plusDays(toRepeat - 1))
            toRestart != null && toRestart <= WARN_BEFORE_RESTART_DAYS ->
                ProgramWarning.ProgramWillRestart(toRestart, today.plusDays(toRestart - 1))
            else -> null
        }
    }
}
