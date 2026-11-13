package app.haanf.ai

import android.util.Log
import app.haanf.Features
import app.haanf.ai.CoachTools.text
import app.haanf.talktest.TrainingActivity

data class CheckIn(
    val minutes: Int,
    val feeling: Int,
    val note: String,
    val activity: TrainingActivity,
)

/** How the check-in asks "How do you feel?". Tired and sore count as low, so no hard work. */
enum class Feel(val label: String, val score: Int) {
    GREAT("Great", 5),
    OK("OK", 4),
    TIRED("Tired", 2),
    SORE("Sore", 2),
    UNWELL("Unwell", 1),
    ;

    /** Haanf does not plan a workout for someone who is unwell. */
    val canTrain: Boolean get() = this != UNWELL
}

sealed interface PlanOutcome {
    data class Approved(val workout: ProposedWorkout) : PlanOutcome

    /** The AI could not satisfy the rules, so this is Haanf's own safe offer. */
    data class Fallback(val workout: ProposedWorkout) : PlanOutcome
    data class Benchmark(val reason: String) : PlanOutcome
    data class Failed(val message: String) : PlanOutcome
}

/**
 * Asks the AI for today's workout, and lets nothing through without the rule check. It may also
 * suggest a repeat benchmark, but only while [offersBenchmark], which follows [Features.BENCHMARK].
 */
class WorkoutPlanner(
    private val llm: LlmProvider,
    private val data: CoachDataSource,
    private val offersBenchmark: Boolean = Features.BENCHMARK,
    private val tone: () -> CoachTone,
) {
    /**
     * Plans today's workout. With [previous], plans a replacement that may not raise intensity:
     * an easier one when [easier] is true, otherwise something different.
     */
    suspend fun plan(checkIn: CheckIn, previous: ProposedWorkout? = null, easier: Boolean = false): PlanOutcome {
        val client = llm.client() ?: return PlanOutcome.Failed("Add an API key first.")
        val snapshot = data.snapshot() ?: return PlanOutcome.Failed("Finish your profile first.")
        val rules = data.ruleContext(checkIn.minutes, checkIn.feeling)
        val lastBenchmark = data.lastBenchmarkDay()
        var outcome: PlanOutcome? = null

        val request = LlmRequest(
            system = CoachPrompts.planner(tone(), offersBenchmark),
            items = listOf(
                LlmItem.User(CoachContext.render(snapshot) + "\n\n" + checkInText(checkIn) + variationText(previous, easier)),
            ),
            tools = if (offersBenchmark) listOf(CoachTools.PROPOSE_WORKOUT, CoachTools.SUGGEST_BENCHMARK) else listOf(CoachTools.PROPOSE_WORKOUT),
            effort = LlmEffort.MEDIUM,
        )
        return try {
            runToolLoop(client, request, maxRounds = MAX_ATTEMPTS, isDone = { outcome != null }) { call ->
                when (call.name) {
                    CoachTools.PROPOSE_WORKOUT.name -> {
                        val workout = CoachTools.readWorkout(call.input)
                        val problems = workout?.let {
                            AiWorkoutRules.check(it, rules) +
                                previous?.let { before -> AiWorkoutRules.checkAlternative(before, it, easier) }.orEmpty()
                        } ?: listOf("The workout could not be read. Follow the schema.")
                        if (problems.isEmpty()) outcome = PlanOutcome.Approved(requireNotNull(workout))
                        Log.i(TAG, "Proposal: ${workout?.title}, problems: $problems")
                        ToolOutcome(call.id, resultText(problems), isError = problems.isNotEmpty())
                    }
                    CoachTools.SUGGEST_BENCHMARK.name.takeIf { offersBenchmark } -> {
                        val blocked = AiWorkoutRules.benchmarkBlockedBy(lastBenchmark, rules.today)
                        if (blocked == null) outcome = PlanOutcome.Benchmark(call.input.text("reason").orEmpty())
                        ToolOutcome(call.id, blocked ?: "Approved.", isError = blocked != null)
                    }
                    else -> ToolOutcome(call.id, "Unknown tool.", isError = true)
                }
            }
            outcome ?: PlanOutcome.Fallback(fallback(checkIn, previous, easier))
        } catch (e: LlmException) {
            PlanOutcome.Failed(e.failure.userMessage)
        }
    }

    /** Haanf's own offer, which is never harder than what the athlete was already shown. */
    private fun fallback(checkIn: CheckIn, previous: ProposedWorkout?, easier: Boolean): ProposedWorkout {
        val limit = if (easier && previous != null) {
            (previous.totalMinutes.toInt() - EASIER_FALLBACK_CUT_MINUTES).coerceAtLeast(MIN_FALLBACK_MINUTES)
        } else {
            checkIn.minutes
        }
        return AiWorkoutRules.fallback(minOf(limit, checkIn.minutes))
    }

    private fun variationText(previous: ProposedWorkout?, easier: Boolean): String {
        if (previous == null) return ""
        val shown = previous.steps.joinToString { "${it.label} ${it.minutes} min in ${it.target.label}" }
        val ask = if (easier) {
            "They asked for something easier than this: shorter or gentler, never harder."
        } else {
            "They asked for something different from this, and no harder."
        }
        return "\n\nYou suggested: ${previous.title} ($shown). $ask"
    }

    private fun resultText(problems: List<String>): String =
        if (problems.isEmpty()) "Approved." else "Rejected by the safety check: ${problems.joinToString(" ")}"

    private fun checkInText(checkIn: CheckIn): String = buildString {
        append("Today's check-in: ${checkIn.minutes} minutes available, ")
        append("feeling ${checkIn.feeling} out of 5, activity ${checkIn.activity.label.lowercase()}.")
        if (checkIn.note.isNotBlank()) append(" They said: ${checkIn.note}")
    }

    private companion object {
        const val TAG = "HaanfPlanner"
        const val MAX_ATTEMPTS = 3
        const val EASIER_FALLBACK_CUT_MINUTES = 10
        const val MIN_FALLBACK_MINUTES = 5
    }
}
