package app.haanf.ai

import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutStep
import app.haanf.training.Zone
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class ProposedStep(val label: String, val minutes: Double, val target: Zone) {
    val isHard: Boolean get() = target == Zone.HARD || target == Zone.VERY_HARD
}

data class ProposedWorkout(
    val title: String,
    val reason: String,
    val steps: List<ProposedStep>,
) {
    val isHard: Boolean get() = steps.any { it.isHard }
    val totalMinutes: Double get() = steps.sumOf { it.minutes }

    fun toPlan() = WorkoutPlan(
        title = title,
        steps = steps.map { WorkoutStep(it.label, (it.minutes * 60).roundToInt(), it.target) },
    )
}

data class RuleContext(
    val today: LocalDate,
    val minutesAvailable: Int,
    val feeling: Int,
    val workoutsLogged: Int,
    val hardDays: List<LocalDate>,
    val hardProgramDays: List<LocalDate>,
    val nextProgramSessionHard: Boolean,
)

/**
 * The separate check that approves every AI workout. There is no AI in it, so a model cannot
 * argue its way past it. docs/ai-mode-spec.md marks which rules are defaults. Injuries are
 * deliberately not here: by the user's decision, the AI adapts to them.
 */
object AiWorkoutRules {
    private const val MIN_MINUTES = 5.0
    private const val MAX_STEPS = 40
    private const val MIN_STEP_MINUTES = 0.5
    private const val MAX_HARD_PER_WEEK = 2
    private const val LOWEST_FEELING_FOR_HARD = 3
    private const val WORKOUTS_BEFORE_HARD = 6
    private const val WORKOUTS_BEFORE_VERY_HARD = 12
    private const val WARM_UP_MINUTES = 5.0
    private const val COOL_DOWN_MINUTES = 3.0
    private const val MAX_VERY_HARD_STEP_MINUTES = 4.0
    private const val BENCHMARK_GAP_DAYS = 28L
    private const val ROUNDING_MINUTES = 0.1

    /** Every broken rule, in words the AI can act on. Empty means approved. */
    fun check(workout: ProposedWorkout, context: RuleContext): List<String> = buildList {
        if (workout.steps.isEmpty()) add("The workout has no steps.")
        if (workout.steps.size > MAX_STEPS) add("Use at most $MAX_STEPS steps.")
        if (workout.steps.any { it.minutes < MIN_STEP_MINUTES }) add("Each step must be at least 30 seconds.")
        if (workout.totalMinutes > context.minutesAvailable) {
            add("It lasts ${workout.totalMinutes.roundToInt()} minutes, but only ${context.minutesAvailable} are available.")
        }
        if (workout.totalMinutes < MIN_MINUTES) add("It must last at least 5 minutes.")
        addAll(intensityProblems(workout, context))
    }

    private fun intensityProblems(workout: ProposedWorkout, context: RuleContext): List<String> {
        if (!workout.isHard) return emptyList()
        return buildList {
            if (context.feeling < LOWEST_FEELING_FOR_HARD) add("No hard work when feeling ${context.feeling} out of 5.")
            if (context.workoutsLogged < WORKOUTS_BEFORE_HARD) {
                add("No hard work until $WORKOUTS_BEFORE_HARD workouts are logged. There are ${context.workoutsLogged}.")
            }
            if (workout.steps.any { it.target == Zone.VERY_HARD }) {
                if (context.workoutsLogged < WORKOUTS_BEFORE_VERY_HARD) {
                    add("No very hard work until $WORKOUTS_BEFORE_VERY_HARD workouts are logged.")
                }
                if (workout.steps.any { it.target == Zone.VERY_HARD && it.minutes > MAX_VERY_HARD_STEP_MINUTES }) {
                    add("A very hard step can last at most 4 minutes.")
                }
            }
            val nearProgram = context.hardProgramDays.any { daysBetween(it, context.today) in 0..1 }
            if (nearProgram) add("No hard work the day after a hard program session.")
            if (context.nextProgramSessionHard) add("No hard work the day before a hard program session.")
            val thisWeek = context.hardDays.count { daysBetween(it, context.today) in 0..6 }
            if (thisWeek >= MAX_HARD_PER_WEEK) add("There are already $thisWeek hard sessions in the last 7 days.")
            addAll(warmUpProblems(workout))
        }
    }

    private fun warmUpProblems(workout: ProposedWorkout): List<String> = buildList {
        val firstHard = workout.steps.indexOfFirst { it.isHard }
        val lastHard = workout.steps.indexOfLast { it.isHard }
        if (workout.steps.take(firstHard).sumOf { it.minutes } < WARM_UP_MINUTES) {
            add("Start with at least 5 easy minutes.")
        }
        if (workout.steps.drop(lastHard + 1).sumOf { it.minutes } < COOL_DOWN_MINUTES) {
            add("End with at least 3 easy minutes.")
        }
    }

    /**
     * Approves a change to a running AI workout. Easing off can only be checked for really
     * being easier. An increase has to pass the same rules as a new workout, apart from step
     * length, since splitting a running step can leave a short piece behind.
     */
    fun checkChange(before: ProposedWorkout, after: ProposedWorkout, increase: Boolean, context: RuleContext): List<String> {
        if (!increase) {
            return buildList {
                if (after.totalMinutes > before.totalMinutes + ROUNDING_MINUTES) add("Easing off cannot add time.")
                if (hardMinutes(after) > hardMinutes(before) + ROUNDING_MINUTES) add("Easing off cannot add hard work.")
            }
        }
        val fullSteps = after.copy(steps = after.steps.filter { it.minutes >= MIN_STEP_MINUTES })
        return check(fullSteps, context)
    }

    private fun hardMinutes(workout: ProposedWorkout): Double =
        workout.steps.filter { it.isHard }.sumOf { it.minutes }

    private fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    /** Null means a repeat is allowed. Otherwise, the reason it is not. */
    fun benchmarkBlockedBy(lastBenchmark: LocalDate?, today: LocalDate): String? {
        if (lastBenchmark == null) return null
        val days = daysBetween(lastBenchmark, today)
        return "The last benchmark was $days days ago. Wait until $BENCHMARK_GAP_DAYS."
            .takeIf { days < BENCHMARK_GAP_DAYS }
    }

    /** What Haanf offers when the AI cannot produce an approved workout. */
    fun fallback(minutesAvailable: Int): ProposedWorkout {
        val minutes = minutesAvailable.coerceIn(MIN_MINUTES.toInt(), 60).toDouble()
        return ProposedWorkout(
            title = "Easy ${minutes.toInt()} minutes",
            reason = "A simple easy session, because the coach could not plan one that passed the safety rules.",
            steps = listOf(ProposedStep("Easy", minutes, Zone.EASY)),
        )
    }
}
