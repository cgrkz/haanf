package app.haanf.program

import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutStep
import app.haanf.training.Zone

data class ProgramSession(
    val plan: WorkoutPlan,
    val hard: Boolean,
)

data class ProgramWeek(val sessions: List<ProgramSession>)

/** Fixed by design: the AI may coach a session but can never change these. */
data class ProgramDefinition(
    val id: String,
    val name: String,
    val summary: String,
    val weeks: List<ProgramWeek>,
) {
    fun session(week: Int, session: Int): ProgramSession = weeks[week - 1].sessions[session]
}

object Programs {
    private const val MINUTE = 60

    private fun easy(minutes: Int) = ProgramSession(
        plan = WorkoutPlan(
            title = "Easy $minutes minutes",
            steps = listOf(WorkoutStep("Easy", minutes * MINUTE, Zone.EASY)),
        ),
        hard = false,
    )

    private fun intervals(reps: Int, hardMinutes: Int, target: Zone) = ProgramSession(
        plan = WorkoutPlan(
            title = "$reps x $hardMinutes minutes ${target.label.lowercase()}",
            steps = buildList {
                add(WorkoutStep("Warm up", 10 * MINUTE, Zone.EASY))
                repeat(reps) { rep ->
                    add(WorkoutStep("${target.label} ${rep + 1} of $reps", hardMinutes * MINUTE, target))
                    if (rep < reps - 1) add(WorkoutStep("Recover", 3 * MINUTE, Zone.EASY))
                }
                add(WorkoutStep("Cool down", 5 * MINUTE, Zone.EASY))
            },
        ),
        hard = true,
    )

    private fun week(vararg sessions: ProgramSession) = ProgramWeek(sessions.toList())

    /** A draft for review. docs/phase-1-plan.md says what the evidence does and does not cover. */
    val FOUNDATION_8 = ProgramDefinition(
        id = "foundation-8",
        name = "Foundation 8",
        summary = "Eight weeks, three sessions a week. Easy aerobic base first, then one " +
            "interval session a week building to 4 x 4 minutes.",
        weeks = listOf(
            week(easy(20), easy(20), easy(25)),
            week(easy(25), easy(25), easy(30)),
            week(easy(30), easy(30), easy(35)),
            week(easy(25), easy(25), easy(30)),
            week(easy(30), intervals(4, 2, Zone.HARD), easy(35)),
            week(easy(30), intervals(4, 3, Zone.HARD), easy(35)),
            week(easy(30), intervals(4, 4, Zone.HARD), easy(40)),
            week(easy(30), intervals(4, 4, Zone.VERY_HARD), easy(40)),
        ),
    )

    val all: List<ProgramDefinition> = listOf(FOUNDATION_8)

    fun byId(id: String): ProgramDefinition? = all.firstOrNull { it.id == id }
}
