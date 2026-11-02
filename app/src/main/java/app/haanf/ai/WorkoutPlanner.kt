package app.haanf.ai

import android.util.Log
import app.haanf.ai.CoachTools.text
import app.haanf.talktest.TrainingActivity

data class CheckIn(
    val minutes: Int,
    val feeling: Int,
    val note: String,
    val activity: TrainingActivity,
)

sealed interface PlanOutcome {
    data class Approved(val workout: ProposedWorkout) : PlanOutcome

    /** The AI could not satisfy the rules, so this is Haanf's own safe offer. */
    data class Fallback(val workout: ProposedWorkout) : PlanOutcome
    data class Benchmark(val reason: String) : PlanOutcome
    data class Failed(val message: String) : PlanOutcome
}

/** Asks the AI for today's workout, and lets nothing through without the rule check. */
class WorkoutPlanner(
    private val llm: LlmProvider,
    private val data: CoachDataSource,
    private val tone: () -> CoachTone,
) {
    suspend fun plan(checkIn: CheckIn): PlanOutcome {
        val client = llm.client() ?: return PlanOutcome.Failed("Add an API key first.")
        val snapshot = data.snapshot() ?: return PlanOutcome.Failed("Finish your profile first.")
        val rules = data.ruleContext(checkIn.minutes, checkIn.feeling)
        val lastBenchmark = data.lastBenchmarkDay()
        var outcome: PlanOutcome? = null

        val request = LlmRequest(
            system = CoachPrompts.planner(tone()),
            items = listOf(LlmItem.User(CoachContext.render(snapshot) + "\n\n" + checkInText(checkIn))),
            tools = listOf(CoachTools.PROPOSE_WORKOUT, CoachTools.SUGGEST_BENCHMARK),
            effort = LlmEffort.MEDIUM,
        )
        return try {
            runToolLoop(client, request, maxRounds = MAX_ATTEMPTS, isDone = { outcome != null }) { call ->
                when (call.name) {
                    CoachTools.PROPOSE_WORKOUT.name -> {
                        val workout = CoachTools.readWorkout(call.input)
                        val problems = workout?.let { AiWorkoutRules.check(it, rules) }
                            ?: listOf("The workout could not be read. Follow the schema.")
                        if (problems.isEmpty()) outcome = PlanOutcome.Approved(requireNotNull(workout))
                        Log.i(TAG, "Proposal: ${workout?.title}, problems: $problems")
                        ToolOutcome(call.id, resultText(problems), isError = problems.isNotEmpty())
                    }
                    CoachTools.SUGGEST_BENCHMARK.name -> {
                        val blocked = AiWorkoutRules.benchmarkBlockedBy(lastBenchmark, rules.today)
                        if (blocked == null) outcome = PlanOutcome.Benchmark(call.input.text("reason").orEmpty())
                        ToolOutcome(call.id, blocked ?: "Approved.", isError = blocked != null)
                    }
                    else -> ToolOutcome(call.id, "Unknown tool.", isError = true)
                }
            }
            outcome ?: PlanOutcome.Fallback(AiWorkoutRules.fallback(checkIn.minutes))
        } catch (e: LlmException) {
            PlanOutcome.Failed(e.failure.userMessage)
        }
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
    }
}
